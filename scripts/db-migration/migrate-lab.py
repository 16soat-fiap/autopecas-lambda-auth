#!/usr/bin/env python3
"""Temporary private Flyway runner for the Learner Lab; no deployment pipeline."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--auth-config", type=Path, required=True)
    parser.add_argument("--database-secrets", type=Path, required=True)
    parser.add_argument("--admin-user", default="autopecas_admin")
    parser.add_argument("--execute", action="store_true", help="Actually start RDS and run migrations")
    args = parser.parse_args()
    config = json.loads(args.auth_config.read_text())
    if config.get("environment") != "homologacao":
        raise ValueError("This tool is restricted to homologacao")
    region = config.get("region", "us-east-1")
    database = "autopecas-db-homologacao"
    name = "autopecas-migration-" + uuid.uuid4().hex[:12]
    artifact = Path(__file__).resolve().parent / "target/database-migration.jar"
    if not artifact.is_file():
        raise ValueError("Build scripts/db-migration/pom.xml first")
    secrets = json.loads(args.database_secrets.read_text())
    if not secrets.get("db_password"):
        raise ValueError("Missing administrative db_password")
    if not config.get("db_url", "").startswith("jdbc:postgresql://"):
        raise ValueError("Invalid JDBC URL")

    def aws(*command, raw=False):
        result = subprocess.run(["aws", "--region", region, "--no-cli-pager", *command],
                                capture_output=True, text=True)
        if result.returncode:
            # AWS errors can repeat request fields, including environment secrets.
            raise RuntimeError("AWS command failed: " + " ".join(command[:2]))
        return result.stdout if raw else json.loads(result.stdout or "{}")

    def status():
        return aws("rds", "describe-db-instances", "--db-instance-identifier", database)["DBInstances"][0]["DBInstanceStatus"]

    def wait_for(read, ready, label, timeout=1200):
        deadline = time.monotonic() + timeout
        attempt = 0
        while time.monotonic() < deadline:
            value = read()
            if value == ready:
                return
            if value in {"failed", "incompatible-network", "incompatible-parameters"}:
                raise RuntimeError(label + " failed")
            if attempt % 6 == 0:
                print(label + ": " + value, flush=True)
            attempt += 1
            time.sleep(10)
        raise TimeoutError(label + " timed out")

    identity = aws("sts", "get-caller-identity")
    role = config["lambda_execution_role_arn"]
    if role.split(":")[4] != identity["Account"]:
        raise ValueError("AWS account does not match the existing Lambda role")
    before = status()
    print("Preflight OK. RDS: " + before + ". Plan: Flyway, SELECT grants, read-only check, cleanup.", flush=True)
    if not args.execute:
        print("No resources changed. Add --execute to run.")
        return

    created = False
    database_touched = False
    try:
        if before not in {"available", "stopped", "starting", "stopping"}:
            raise RuntimeError("Unexpected RDS state: " + before)
        database_touched = True
        if before == "stopping":
            wait_for(status, "stopped", "RDS stopping")
            before = "stopped"
        if before == "stopped":
            aws("rds", "start-db-instance", "--db-instance-identifier", database)
        wait_for(status, "available", "RDS starting")
        request = {
            "FunctionName": name, "Runtime": "java21", "Role": role,
            "Handler": "br.com.autopecas.migration.MigrationHandler::handleRequest",
            "MemorySize": 512, "Timeout": 120,
            "VpcConfig": {"SubnetIds": config["lambda_subnet_ids"],
                          "SecurityGroupIds": config["lambda_security_group_ids"]},
            "Environment": {"Variables": {"DB_URL": config["db_url"],
                            "MIGRATION_DB_USER": args.admin_user,
                            "MIGRATION_DB_PASSWORD": secrets["db_password"]}},
            "Tags": {"Project": "autopecas", "Purpose": "temporary-migration"}}
        # Named temporary files are needed by AWS CLI; only the current user can read them.
        with tempfile.TemporaryDirectory(prefix="autopecas-migration-") as directory:
            request_file = Path(directory) / "request.json"
            descriptor = os.open(request_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w") as stream:
                json.dump(request, stream)
            aws("lambda", "create-function", "--cli-input-json", "file://" + str(request_file),
                "--zip-file", "fileb://" + str(artifact))
            created = True
            wait_for(lambda: aws("lambda", "get-function-configuration", "--function-name", name)["State"].lower(),
                     "active", "Temporary Lambda")
            response_file = Path(directory) / "response.json"
            response = aws("lambda", "invoke", "--function-name", name, str(response_file))
            result = json.loads(response_file.read_text())
            if response.get("FunctionError") or not result.get("ok"):
                raise RuntimeError("Migration failed; errorType=" + result.get("errorType", "LambdaError"))
            print(json.dumps(result), flush=True)
    finally:
        errors = []
        if created:
            for command in [("lambda", "delete-function", "--function-name", name),
                            ("logs", "delete-log-group", "--log-group-name", "/aws/lambda/" + name)]:
                try:
                    aws(*command)
                except Exception:
                    errors.append("Cleanup failed: " + " ".join(command[:2]) + " for " + name)
        if database_touched:
            try:
                current = status()
                if current not in {"available", "stopped", "stopping"}:
                    wait_for(status, "available", "RDS before cleanup")
                    current = "available"
                if current == "available":
                    aws("rds", "stop-db-instance", "--db-instance-identifier", database)
                wait_for(status, "stopped", "RDS stopping")
                print("RDS stopped.", flush=True)
                if created:
                    group_name = "/aws/lambda/" + name
                    groups = aws("logs", "describe-log-groups", "--log-group-name-prefix", group_name)["logGroups"]
                    if any(group["logGroupName"] == group_name for group in groups):
                        aws("logs", "delete-log-group", "--log-group-name", group_name)
            except Exception:
                errors.append("Could not confirm RDS stopped: " + database)
        if errors:
            raise RuntimeError("; ".join(errors))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, TimeoutError, OSError) as error:
        raise SystemExit(str(error))
