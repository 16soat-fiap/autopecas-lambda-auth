mock_provider "aws" {}

variables {
  environment               = "homologacao"
  lambda_execution_role_arn = "arn:aws:iam::123456789012:role/LabRole"
  lambda_subnet_ids         = ["subnet-test"]
  lambda_security_group_ids = ["sg-test"]
  db_url                    = "jdbc:postgresql://db.test:5432/autopecas"
  db_user                   = "lambda_auth"
  db_password               = "senha-exclusiva-de-teste"
  jwt_private_key           = join("", [for i in range(800) : "AA"]) # Somente mock; nao e uma chave real.
}

run "auth_without_application" {
  command = plan

  assert {
    condition     = aws_apigatewayv2_route.auth.authorization_type == "NONE"
    error_message = "POST /auth deve ser publico."
  }
  assert {
    condition     = aws_apigatewayv2_authorizer.jwt.authorizer_type == "JWT"
    error_message = "O gateway deve validar JWT nativamente."
  }
  assert {
    condition     = length(aws_lambda_function.auth.vpc_config) == 1
    error_message = "A unica Lambda deve acessar a VPC do banco."
  }
  assert {
    condition     = alltrue([for route in aws_apigatewayv2_route.discovery : route.authorization_type == "NONE"])
    error_message = "Discovery e JWKS precisam ser publicos."
  }
  assert {
    condition     = contains(aws_apigatewayv2_authorizer.jwt.jwt_configuration[0].audience, var.jwt_audience)
    error_message = "A audience do gateway deve corresponder a da Lambda."
  }
  assert {
    condition     = length(aws_apigatewayv2_route.protected) == 0
    error_message = "Nao devem existir rotas protegidas sem configurar a aplicacao."
  }
}

run "protected_application" {
  command = plan
  variables {
    environment      = "producao"
    application_url  = "https://api.example.com"
    protected_routes = ["GET /ordens-servico", "POST /ordens-servico"]
  }
  assert {
    condition     = alltrue([for route in aws_apigatewayv2_route.protected : route.authorization_type == "JWT" && contains(route.authorization_scopes, "api:access")])
    error_message = "Todas as rotas da aplicacao devem usar o authorizer."
  }
  assert {
    condition     = aws_apigatewayv2_stage.this.name == "producao"
    error_message = "O stage deve corresponder ao ambiente."
  }
}

run "reject_missing_application" {
  command = plan
  variables {
    protected_routes = ["GET /ordens-servico"]
  }
  expect_failures = [aws_apigatewayv2_integration.application]
}

run "reject_auth_override" {
  command = plan
  variables {
    protected_routes = ["POST /auth"]
    application_url  = "https://api.example.com"
  }
  expect_failures = [var.protected_routes]
}


run "reject_discovery_override" {
  command = plan
  variables {
    protected_routes = ["GET /.well-known/jwks.json"]
    application_url  = "https://api.example.com"
  }
  expect_failures = [var.protected_routes]
}
