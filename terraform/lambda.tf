locals {
  name     = "autopecas-auth-${var.environment}"
  jar_path = "${path.module}/../target/autopecas-lambda.jar"
  issuer   = "${aws_apigatewayv2_api.this.api_endpoint}/${var.environment}"
}

resource "aws_cloudwatch_log_group" "lambda" {
  name              = "/aws/lambda/${local.name}"
  retention_in_days = 14
}

resource "aws_lambda_function" "auth" {
  function_name    = local.name
  role             = var.lambda_execution_role_arn
  runtime          = "java21"
  architectures    = ["x86_64"]
  handler          = "br.com.autopecas.handler.AuthHandler::handleRequest"
  filename         = local.jar_path
  source_code_hash = filebase64sha256(local.jar_path)
  memory_size      = 512
  timeout          = 25

  environment {
    variables = {
      DB_URL          = var.db_url
      DB_USER         = var.db_user
      DB_PASSWORD     = var.db_password
      JWT_PRIVATE_KEY = var.jwt_private_key
      JWT_ISSUER      = local.issuer
      JWT_AUDIENCE    = var.jwt_audience
    }
  }

  vpc_config {
    subnet_ids         = var.lambda_subnet_ids
    security_group_ids = var.lambda_security_group_ids
  }

  depends_on = [aws_cloudwatch_log_group.lambda]
}
