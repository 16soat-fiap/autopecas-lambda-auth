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


run "application_route_catalog" {
  command = plan
  variables {
    application_url  = "https://application.example.com"
    protected_routes = jsondecode(file("routes/application.json")).protected_routes
  }

  assert {
    condition     = length(aws_apigatewayv2_route.protected) == 44
    error_message = "O catalogo deve mapear os 44 endpoints revisados da aplicacao."
  }
  assert {
    condition     = alltrue([for route in aws_apigatewayv2_route.protected : route.authorization_type == "JWT" && contains(route.authorization_scopes, "api:access")])
    error_message = "Nenhuma rota do catalogo pode ser publicada sem JWT e escopo api:access."
  }
  assert {
    condition     = contains(keys(aws_apigatewayv2_route.protected), "GET /api/ordens-servico") && contains(keys(aws_apigatewayv2_route.protected), "PATCH /api/ordens-servico/{osId}/orcamentos/{id}/aprovar")
    error_message = "Mapear tambem as colecoes e operacoes de orcamentos aninhadas."
  }
  assert {
    condition     = aws_apigatewayv2_integration.application[0].request_parameters["overwrite:path"] == "$request.path"
    error_message = "Preservar o caminho da aplicacao sem prefixar o stage."
  }
}

run "catalog_requires_upstream" {
  command = plan
  variables {
    protected_routes = jsondecode(file("routes/application.json")).protected_routes
  }
  expect_failures = [aws_apigatewayv2_integration.application]
}

run "private_application_via_vpc_link" {
  command = plan
  variables {
    vpc_link_id              = "vlnk-test"
    application_listener_arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:listener/net/autopecas-nlb/abc/def"
    protected_routes         = jsondecode(file("routes/application.json")).protected_routes
  }
  assert {
    condition     = aws_apigatewayv2_integration.application[0].connection_type == "VPC_LINK" && aws_apigatewayv2_integration.application[0].connection_id == "vlnk-test"
    error_message = "Com vpc_link_id, a integracao deve ser privada pelo VPC Link."
  }
  assert {
    condition     = aws_apigatewayv2_integration.application[0].integration_uri == var.application_listener_arn
    error_message = "A integracao privada deve apontar para o listener do NLB do ambiente."
  }
  assert {
    condition     = alltrue([for route in aws_apigatewayv2_route.protected : route.authorization_type == "JWT"])
    error_message = "A integracao privada nao dispensa o authorizer."
  }
}

run "reject_public_and_private_together" {
  command = plan
  variables {
    application_url          = "https://api.example.com"
    vpc_link_id              = "vlnk-test"
    application_listener_arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:listener/net/autopecas-nlb/abc/def"
    protected_routes         = ["GET /api/clientes"]
  }
  expect_failures = [aws_apigatewayv2_integration.application]
}

run "reject_vpc_link_without_listener" {
  command = plan
  variables {
    vpc_link_id      = "vlnk-test"
    protected_routes = ["GET /api/clientes"]
  }
  expect_failures = [aws_apigatewayv2_integration.application]
}

run "staff_login_stays_public" {
  command = plan
  assert {
    condition     = aws_apigatewayv2_route.staff_auth.authorization_type == "NONE" && aws_lambda_permission.staff_auth.action == "lambda:InvokeFunction"
    error_message = "Login de funcionarios precisa invocar a mesma Lambda sem JWT previo."
  }
}
run "reject_staff_auth_override" {
  command = plan
  variables {
    application_url  = "https://application.example.com"
    protected_routes = ["POST /auth/funcionarios"]
  }
  expect_failures = [var.protected_routes]
}

run "health_check_is_private_by_default" {
  command = plan
  variables {
    vpc_link_id              = "vlnk-test"
    application_listener_arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:listener/net/autopecas-nlb/abc/def"
    protected_routes         = ["GET /api/clientes"]
  }
  assert {
    condition     = length(aws_apigatewayv2_route.health) == 0
    error_message = "Sem public_health_check, nenhuma rota da aplicacao fica sem JWT."
  }
}

run "public_health_check_via_vpc_link" {
  command = plan
  variables {
    vpc_link_id              = "vlnk-test"
    application_listener_arn = "arn:aws:elasticloadbalancing:us-east-1:123456789012:listener/net/autopecas-nlb/abc/def"
    protected_routes         = jsondecode(file("routes/application.json")).protected_routes
    public_health_check      = true
  }
  assert {
    condition     = aws_apigatewayv2_route.health[0].route_key == "GET /actuator/health" && aws_apigatewayv2_route.health[0].authorization_type == "NONE"
    error_message = "O healthcheck publico deve ser exatamente GET /actuator/health, sem authorizer."
  }
  assert {
    condition     = alltrue([for route in aws_apigatewayv2_route.protected : route.authorization_type == "JWT"])
    error_message = "Liberar o healthcheck nao pode tirar o JWT das demais rotas."
  }
}

run "public_health_check_requires_upstream" {
  command = plan
  variables {
    public_health_check = true
  }
  expect_failures = [aws_apigatewayv2_integration.application]
}
