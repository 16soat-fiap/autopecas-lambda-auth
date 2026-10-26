output "api_url" {
  value = aws_apigatewayv2_stage.this.invoke_url
}

output "auth_url" {
  value = "${aws_apigatewayv2_stage.this.invoke_url}/auth"
}

output "lambda_name" {
  value = aws_lambda_function.auth.function_name
}

output "jwt_issuer" {
  value = local.issuer
}

output "jwks_url" {
  value = "${local.issuer}/.well-known/jwks.json"
}

output "protected_routes" {
  value = var.protected_routes
}
