resource "aws_apigatewayv2_api" "this" {
  name          = local.name
  protocol_type = "HTTP"
}

resource "aws_cloudwatch_log_group" "gateway" {
  name              = "/aws/apigateway/${local.name}"
  retention_in_days = 14
}

resource "aws_apigatewayv2_stage" "this" {
  # Route settings can only reference routes already created in API Gateway.
  depends_on = [aws_apigatewayv2_route.staff_auth]

  api_id      = aws_apigatewayv2_api.this.id
  name        = var.environment
  auto_deploy = true

  default_route_settings {
    throttling_burst_limit = 20
    throttling_rate_limit  = 10
  }

  route_settings {
    route_key              = "POST /auth/funcionarios"
    throttling_burst_limit = 5
    throttling_rate_limit  = 2
  }

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.gateway.arn
    format = jsonencode({
      requestId         = "$context.requestId"
      integrationId     = "$context.integration.requestId"
      routeKey          = "$context.routeKey"
      status            = "$context.status"
      responseLatency   = "$context.responseLatency"
      integrationStatus = "$context.integration.status"
    })
  }
}

resource "aws_apigatewayv2_integration" "auth" {
  api_id                 = aws_apigatewayv2_api.this.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.auth.invoke_arn
  payload_format_version = "2.0"
  timeout_milliseconds   = 30000
}

resource "aws_apigatewayv2_route" "auth" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "POST /auth"
  authorization_type = "NONE"
  target             = "integrations/${aws_apigatewayv2_integration.auth.id}"
}

resource "aws_apigatewayv2_route" "staff_auth" {
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "POST /auth/funcionarios"
  authorization_type = "NONE"
  target             = "integrations/${aws_apigatewayv2_integration.auth.id}"
}

resource "aws_lambda_permission" "staff_auth" {
  statement_id  = "AllowGatewayStaffAuth"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.auth.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.this.execution_arn}/${var.environment}/POST/auth/funcionarios"
}

resource "aws_lambda_permission" "auth" {
  statement_id  = "AllowGatewayAuth"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.auth.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.this.execution_arn}/${var.environment}/POST/auth"
}

locals {
  discovery_routes = {
    discovery = "/.well-known/openid-configuration"
    jwks      = "/.well-known/jwks.json"
  }
}

resource "aws_apigatewayv2_route" "discovery" {
  for_each           = local.discovery_routes
  api_id             = aws_apigatewayv2_api.this.id
  route_key          = "GET ${each.value}"
  authorization_type = "NONE"
  target             = "integrations/${aws_apigatewayv2_integration.auth.id}"
}

resource "aws_lambda_permission" "discovery" {
  for_each      = local.discovery_routes
  statement_id  = "AllowGateway-${each.key}"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.auth.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.this.execution_arn}/${var.environment}/GET${each.value}"
}

resource "terraform_data" "discovery_ready" {
  triggers_replace = [
    local.issuer,
    aws_lambda_function.auth.source_code_hash,
    filesha256("${path.module}/../scripts/wait-for-jwks.py")
  ]

  provisioner "local-exec" {
    command     = "python3 ../scripts/wait-for-jwks.py"
    working_dir = path.module
    environment = {
      JWT_ISSUER_URL = local.issuer
    }
  }

  depends_on = [
    aws_apigatewayv2_route.discovery,
    aws_lambda_permission.discovery,
    aws_apigatewayv2_stage.this
  ]
}

resource "aws_apigatewayv2_authorizer" "jwt" {
  api_id           = aws_apigatewayv2_api.this.id
  name             = "jwt"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  jwt_configuration {
    issuer   = local.issuer
    audience = [var.jwt_audience]
  }

  depends_on = [terraform_data.discovery_ready]
}

locals {
  # Integracao privada: o gateway chega ao NLB interno pelo VPC Link, e o
  # cluster nao precisa de nenhum ingresso publico que contornaria o authorizer.
  integracao_privada = var.vpc_link_id != null
}

resource "aws_apigatewayv2_integration" "application" {
  count                  = length(var.protected_routes) > 0 ? 1 : 0
  api_id                 = aws_apigatewayv2_api.this.id
  integration_type       = "HTTP_PROXY"
  integration_method     = "ANY"
  connection_type        = local.integracao_privada ? "VPC_LINK" : "INTERNET"
  connection_id          = local.integracao_privada ? var.vpc_link_id : null
  integration_uri        = local.integracao_privada ? var.application_listener_arn : var.application_url
  payload_format_version = "1.0"
  timeout_milliseconds   = 30000

  request_parameters = {
    "overwrite:path" = "$request.path"
  }

  lifecycle {
    precondition {
      condition     = (var.application_url != null) != local.integracao_privada
      error_message = "Informe application_url OU vpc_link_id antes de habilitar rotas protegidas, nao os dois."
    }
    precondition {
      condition     = !local.integracao_privada || var.application_listener_arn != null
      error_message = "Com vpc_link_id, informe tambem application_listener_arn."
    }
  }
}

resource "aws_apigatewayv2_route" "protected" {
  for_each             = var.protected_routes
  api_id               = aws_apigatewayv2_api.this.id
  route_key            = each.value
  authorization_type   = "JWT"
  authorizer_id        = aws_apigatewayv2_authorizer.jwt.id
  authorization_scopes = ["api:access"]
  target               = "integrations/${aws_apigatewayv2_integration.application[0].id}"
}
