#!/usr/bin/env python3
"""Validate the existing homologacao deployment using disposable private fixtures."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-id", required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    config = json.loads((root / "terraform/environments/homologacao.tfvars.json").read_text())
    secrets = json.loads((root.parent / "autopecas-infra-db/terraform/environments/homologacao.secrets.tfvars.json").read_text())
    signing = json.loads((root / "terraform/environments/homologacao.secrets.tfvars.json").read_text())
    if config["environment"] != "homologacao":
        raise ValueError("Only homologacao is supported")
    region = config.get("region", "us-east-1")
    run_id = str(uuid.uuid4())
    helper = "autopecas-validation-" + run_id[:8]
    auth_function = "autopecas-auth-homologacao"
    database = "autopecas-db-homologacao"
    route_path = "/_validacao-" + run_id[:8]
    statement_id = "Validation" + run_id.replace("-", "")
    artifact = root / "scripts/db-migration/target/database-migration.jar"
    checks = []

    def aws(*command):
        response = subprocess.run(["aws", "--region", region, "--no-cli-pager", *command], capture_output=True, text=True)
        if response.returncode:
            match = re.search(r"An error occurred \(([^)]+)\)", response.stderr)
            raise RuntimeError("AWS " + " ".join(command[:2]) + ": " + (match.group(1) if match else "command failed"))
        return json.loads(response.stdout or "{}")

    def wait(read, expected, label, timeout=1500):
        deadline = time.monotonic() + timeout
        attempt = 0
        while time.monotonic() < deadline:
            value = read()
            if value == expected:
                return
            if str(value).lower() == "failed":
                raise RuntimeError(label + " failed")
            if attempt % 6 == 0:
                print(label + ": " + str(value), flush=True)
            attempt += 1
            time.sleep(10)
        raise TimeoutError(label + " timed out")

    def db_status():
        return aws("rds", "describe-db-instances", "--db-instance-identifier", database)["DBInstances"][0]["DBInstanceStatus"]

    def http(path, values=None, token=None, raw=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        data = raw if raw is not None else json.dumps(values).encode() if values is not None else None
        request = urllib.request.Request(issuer + path, data=data, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())

    def check(label, actual, expected):
        if actual != expected:
            raise AssertionError(label + ": expected " + str(expected) + ", got " + str(actual))
        checks.append({"check": label, "result": "passed"})
        print("PASS " + label, flush=True)

    def decode(value):
        return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))

    def encode(value):
        return base64.urlsafe_b64encode(value).decode().rstrip("=")

    def verify(token, identity, profile):
        head, payload, signature = token.split(".")
        header, claims = json.loads(decode(head)), json.loads(decode(payload))
        key = next(key for key in jwks["keys"] if key["kid"] == header["kid"])
        public = rsa.RSAPublicNumbers(int.from_bytes(decode(key["e"]), "big"), int.from_bytes(decode(key["n"]), "big")).public_key()
        public.verify(decode(signature), (head + "." + payload).encode(), padding.PKCS1v15(), hashes.SHA256())
        assert header["alg"] == "RS256"
        assert claims["iss"] == issuer and claims["sub"] == identity["id"]
        assert claims["perfil"] == profile and claims["scope"] == "api:access"
        assert config.get("jwt_audience", "autopecas-api") in ([claims["aud"]] if isinstance(claims["aud"], str) else claims["aud"])
        assert 0 < claims["exp"] - claims["iat"] <= 1800
        assert claims["iat"] <= time.time() + 60 < claims["exp"]
        assert set(claims["roles"]) == (set(identity["roles"]) if profile == "FUNCIONARIO" else {"CLIENTE"})
        assert not any(name in claims for name in ["cpf", "documento", "email", "senha"])
        return header, claims

    identity = aws("sts", "get-caller-identity")
    if config["lambda_execution_role_arn"].split(":")[4] != identity["Account"]:
        raise ValueError("AWS account mismatch")
    api = aws("apigatewayv2", "get-api", "--api-id", args.api_id)
    if api["Name"] != "autopecas-auth-homologacao":
        raise ValueError("Unexpected API")
    issuer = api["ApiEndpoint"] + "/homologacao"
    if not artifact.is_file():
        raise ValueError("Build the administrative artifact first")
    print("Preflight OK: existing homologacao resources only.", flush=True)
    if not args.execute:
        print("No resources changed. Add --execute to validate.")
        return

    created = permission = seeded = False
    route_id = None
    errors = []
    with tempfile.TemporaryDirectory(prefix="autopecas-validation-") as directory:
        temporary = Path(directory)

        def json_file(name, value):
            path = temporary / name
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(descriptor, "w") as stream:
                json.dump(value, stream)
            return path

        def invoke(action=None, migration=False):
            payload = json_file("event.json", {} if migration else {"action": action, "run": run_id})
            output = temporary / "response.json"
            result = aws("lambda", "invoke", "--function-name", helper, "--cli-binary-format", "raw-in-base64-out",
                         "--payload", "file://" + str(payload), "--cli-read-timeout", "180", str(output))
            body = json.loads(output.read_text())
            if result.get("FunctionError") or not body.get("ok"):
                raise RuntimeError("Administrative invocation failed: " + body.get("errorType", "LambdaError"))
            return body

        try:
            before = db_status()
            if before == "stopping":
                wait(db_status, "stopped", "RDS stopping")
                before = "stopped"
            if before == "stopped":
                aws("rds", "start-db-instance", "--db-instance-identifier", database)
            wait(db_status, "available", "RDS starting")
            request = {
                "FunctionName": helper, "Runtime": "java21", "Role": config["lambda_execution_role_arn"],
                "Handler": "br.com.autopecas.migration.MigrationHandler::handleRequest", "MemorySize": 512, "Timeout": 120,
                "VpcConfig": {"SubnetIds": config["lambda_subnet_ids"], "SecurityGroupIds": config["lambda_security_group_ids"]},
                "Environment": {"Variables": {"DB_URL": config["db_url"], "MIGRATION_DB_USER": "autopecas_admin", "MIGRATION_DB_PASSWORD": secrets["db_password"]}},
                "Tags": {"Project": "autopecas", "Purpose": "temporary-validation"}}
            path = json_file("request.json", request)
            aws("lambda", "create-function", "--cli-input-json", "file://" + str(path), "--zip-file", "fileb://" + str(artifact))
            created = True
            wait(lambda: aws("lambda", "get-function-configuration", "--function-name", helper)["State"], "Active", "Temporary Lambda")
            result = invoke(migration=True)
            check("Flyway V4 and read-only grants", (result["version"], result["readOnlyVerified"]), ("4", True))
            print("Migrations applied: " + str(result["migrationsExecuted"]), flush=True)
            aws("lambda", "update-function-configuration", "--function-name", helper, "--handler", "br.com.autopecas.migration.ValidationFixtureHandler::handleRequest")
            wait(lambda: aws("lambda", "get-function-configuration", "--function-name", helper)["LastUpdateStatus"], "Successful", "Fixture handler")
            seeded = True  # Cleanup also runs if invocation succeeds server-side but its response is lost.
            fixtures = invoke("seed")["identities"]
            status, discovery = http("/.well-known/openid-configuration")
            check("Discovery", (status, discovery["issuer"]), (200, issuer))
            status, jwks = http("/.well-known/jwks.json")
            check("JWKS public RSA only", (status, all(key["kty"] == "RSA" and "d" not in key for key in jwks["keys"])), (200, True))
            tokens = {}
            for kind in ["staff_admin", "staff_atendente", "staff_mecanico", "client_active"]:
                fixture = fixtures[kind]
                staff = kind.startswith("staff")
                status, response = http("/auth/funcionarios" if staff else "/auth", {"cpf" if staff else "documento": fixture["cpf"], "roles": ["ADMIN"]})
                check(kind + " login", status, 200)
                tokens[kind] = response["token"]
                verify(tokens[kind], fixture, "FUNCIONARIO" if staff else "CLIENTE")
                check(kind + " signature, UUID and roles", True, True)
                if staff:
                    cpf = fixture["cpf"]
                    mask = cpf[:3] + "." + cpf[3:6] + "." + cpf[6:9] + "-" + cpf[9:]
                    check(kind + " masked CPF", http("/auth/funcionarios", {"cpf": mask})[0], 200)
                time.sleep(0.6)
            for kind in ["staff_inactive", "staff_no_roles"]:
                check(kind + " denied", http("/auth/funcionarios", {"cpf": fixtures[kind]["cpf"]})[0], 401)
                time.sleep(0.6)
            check("Inactive client denied", http("/auth", {"documento": fixtures["client_inactive"]["cpf"]})[0], 403)
            check("Client cannot login as employee", http("/auth/funcionarios", {"cpf": fixtures["client_active"]["cpf"]})[0], 401)
            time.sleep(0.6)
            for value in [{"cpf": "11111111111"}, {}, {"cpf": 52998224725}, {"email": "x@example.test", "senha": "ignored"}]:
                check("Invalid staff input " + str(len(checks)), http("/auth/funcionarios", value)[0], 400)
                time.sleep(0.6)
            check("Malformed staff JSON", http("/auth/funcionarios", raw=b"{")[0], 400)
            authorizers = aws("apigatewayv2", "get-authorizers", "--api-id", args.api_id)["Items"]
            authorizer = next(item for item in authorizers if item["Name"] == "jwt")
            integrations = aws("apigatewayv2", "get-integrations", "--api-id", args.api_id)["Items"]
            integration = next(item for item in integrations if auth_function in item.get("IntegrationUri", ""))
            source = "arn:aws:execute-api:" + region + ":" + identity["Account"] + ":" + args.api_id + "/homologacao/GET" + route_path
            aws("lambda", "add-permission", "--function-name", auth_function, "--statement-id", statement_id,
                "--action", "lambda:InvokeFunction", "--principal", "apigateway.amazonaws.com", "--source-arn", source)
            permission = True
            route = aws("apigatewayv2", "create-route", "--api-id", args.api_id, "--route-key", "GET " + route_path,
                        "--authorization-type", "JWT", "--authorizer-id", authorizer["AuthorizerId"], "--authorization-scopes", "api:access",
                        "--target", "integrations/" + integration["IntegrationId"])
            route_id = route["RouteId"]
            wait(lambda: http(route_path)[0], 401, "JWT route propagation", timeout=120)
            check("Protected route without token", http(route_path)[0], 401)
            for kind, token in tokens.items():
                status, body = http(route_path, token=token)
                check(kind + " accepted by native authorizer", (status, body.get("erro")), (404, "Rota inexistente"))
            header, claims = verify(tokens["staff_admin"], fixtures["staff_admin"], "FUNCIONARIO")
            private = serialization.load_der_private_key(base64.b64decode(signing["jwt_private_key"]), password=None)

            def sign(changes):
                payload = dict(claims)
                payload.update(changes)
                parts = encode(json.dumps(header).encode()) + "." + encode(json.dumps(payload).encode())
                return parts + "." + encode(private.sign(parts.encode(), padding.PKCS1v15(), hashes.SHA256()))

            for label, token, expected in [
                ("Wrong audience", sign({"aud": "other-api"}), 401),
                ("Wrong issuer", sign({"iss": "https://invalid.example.test"}), 401),
                ("Expired token", sign({"iat": int(time.time())-120, "exp": int(time.time())-60}), 401),
                ("Missing scope", sign({"scope": ""}), 403),
                ("Invalid signature", tokens["staff_admin"][:-10] + "AAAAAAAAAA", 401)]:
                check(label, http(route_path, token=token)[0], expected)
        finally:
            if route_id:
                try:
                    aws("apigatewayv2", "delete-route", "--api-id", args.api_id, "--route-id", route_id)
                except Exception:
                    errors.append("Temporary route cleanup failed")
            if permission:
                try:
                    aws("lambda", "remove-permission", "--function-name", auth_function, "--statement-id", statement_id)
                except Exception:
                    errors.append("Temporary invocation permission cleanup failed")
            if seeded:
                try:
                    result = invoke("cleanup")
                    check("Temporary accounts removed", result["removed"], 7)
                except Exception:
                    errors.append("Fixture cleanup failed; run=" + run_id)
            if created:
                for command in [("lambda", "delete-function", "--function-name", helper), ("logs", "delete-log-group", "--log-group-name", "/aws/lambda/" + helper)]:
                    try:
                        aws(*command)
                    except Exception:
                        errors.append("Temporary function/log cleanup failed: " + helper)
            try:
                current = db_status()
                if current not in {"available", "stopped", "stopping"}:
                    wait(db_status, "available", "RDS before stop")
                    current = "available"
                if current == "available":
                    aws("rds", "stop-db-instance", "--db-instance-identifier", database)
                wait(db_status, "stopped", "RDS stopping")
                print("RDS stopped.", flush=True)
                if created:
                    # A final Lambda log flush can recreate a group after the first deletion.
                    name = "/aws/lambda/" + helper
                    groups = aws("logs", "describe-log-groups", "--log-group-name-prefix", name)["logGroups"]
                    if any(group["logGroupName"] == name for group in groups):
                        aws("logs", "delete-log-group", "--log-group-name", name)
            except Exception:
                errors.append("Could not confirm RDS stopped")
            report = {"run": run_id, "issuer": issuer, "checks": checks, "cleanupErrors": errors}
            output = root / "target/aws-validation-report.json"
            output.write_text(json.dumps(report, indent=2) + "\n")
            if errors:
                raise RuntimeError("; ".join(errors))
    print("Validation complete: " + str(len(checks)) + " checks passed.", flush=True)


if __name__ == "__main__":
    main()
