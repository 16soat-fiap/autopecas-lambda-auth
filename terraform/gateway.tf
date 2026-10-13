resource "aws_apigatewayv2_api" "this" {
  name          = local.name
  protocol_type = "HTTP"
}

resource "aws_cloudwatch_log_group" "gateway" {
  name              = "/aws/apigateway/${local.name}"
  retention_in_days = 14
}

resource "aws_apigatewayv2_stage" "this" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = var.environment
  auto_deploy = true

  default_route_settings {
    throttling_burst_limit = 20
    throttling_rate_limit  = 10
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

resource "aws_apigatewayv2_authorizer" "jwt" {
  api_id           = aws_apigatewayv2_api.this.id
  name             = "jwt"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  jwt_configuration {
    issuer   = local.issuer
    audience = [var.jwt_audience]
  }

  # Publicacao dos metadados antes de configurar sua descoberta pelo gateway.
  depends_on = [
    aws_apigatewayv2_route.discovery,
    aws_lambda_permission.discovery,
    aws_apigatewayv2_stage.this
  ]
}

resource "aws_apigatewayv2_integration" "application" {
  count                  = length(var.protected_routes) > 0 ? 1 : 0
  api_id                 = aws_apigatewayv2_api.this.id
  integration_type       = "HTTP_PROXY"
  integration_method     = "ANY"
  integration_uri        = var.application_url
  payload_format_version = "1.0"
  timeout_milliseconds   = 30000

  request_parameters = {
    "overwrite:path" = "$request.path"
  }

  lifecycle {
    precondition {
      condition     = var.application_url != null
      error_message = "Informe application_url antes de habilitar rotas protegidas."
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
