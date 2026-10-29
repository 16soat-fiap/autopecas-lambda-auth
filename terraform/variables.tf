variable "region" {
  type    = string
  default = "us-east-1"
}

variable "environment" {
  type = string
  validation {
    condition     = contains(["homologacao", "producao"], var.environment)
    error_message = "Use homologacao ou producao."
  }
}

variable "lambda_execution_role_arn" {
  description = "Role existente do Learner Lab, com trust para Lambda, logs e acesso VPC."
  type        = string
  validation {
    condition     = can(regex("^arn:aws:iam::[0-9]{12}:role/.+$", var.lambda_execution_role_arn))
    error_message = "Informe o ARN da role de execucao existente."
  }
}

variable "lambda_subnet_ids" {
  description = "Sub-redes com conectividade ao banco gerenciado."
  type        = set(string)
  validation {
    condition     = length(var.lambda_subnet_ids) > 0 && alltrue([for id in var.lambda_subnet_ids : startswith(id, "subnet-")])
    error_message = "Informe ao menos uma subnet da VPC do banco."
  }
}

variable "lambda_security_group_ids" {
  description = "Security groups da Lambda com saida para PostgreSQL; o SG do banco deve permitir essa origem."
  type        = set(string)
  validation {
    condition     = length(var.lambda_security_group_ids) > 0 && alltrue([for id in var.lambda_security_group_ids : startswith(id, "sg-")])
    error_message = "Informe ao menos um security group."
  }
}

variable "db_url" {
  type = string
  validation {
    condition     = startswith(var.db_url, "jdbc:postgresql://")
    error_message = "DB_URL deve ser uma URL JDBC PostgreSQL."
  }
}

variable "db_user" {
  type = string
  validation {
    condition     = length(trimspace(var.db_user)) > 0
    error_message = "Informe o usuario de leitura do banco."
  }
}

variable "db_password" {
  type      = string
  sensitive = true
  validation {
    condition     = length(trimspace(var.db_password)) > 0
    error_message = "Informe a senha do banco."
  }
}

variable "jwt_private_key" {
  description = "Chave privada RSA >=2048 bits, DER PKCS#8 em Base64. Nunca versionar."
  type        = string
  sensitive   = true
  validation {
    condition     = can(regex("^[A-Za-z0-9+/]+={0,2}$", var.jwt_private_key)) && length(var.jwt_private_key) >= 1000
    error_message = "Forneca uma chave privada RSA PKCS#8 em Base64; a Lambda valida seu conteudo."
  }
}

variable "jwt_audience" {
  type    = string
  default = "autopecas-api"
  validation {
    condition     = length(trimspace(var.jwt_audience)) > 0
    error_message = "Informe a audience das APIs protegidas."
  }
}

variable "application_url" {
  description = "URL HTTPS publica do ingresso da aplicacao Kubernetes. Alternativa a integracao privada (vpc_link_id + application_listener_arn)."
  type        = string
  default     = null
  nullable    = true
  validation {
    condition     = var.application_url == null ? true : can(regex("^https://[^/]+$", var.application_url))
    error_message = "Use uma origem HTTPS sem caminho nem barra final."
  }
}

variable "vpc_link_id" {
  description = "VPC Link do repositorio autopecas-infra-k8s (output vpc_link_id). Com ele, as rotas protegidas chegam a aplicacao sem ingresso publico."
  type        = string
  default     = null
  nullable    = true
}

variable "application_listener_arn" {
  description = "Listener do NLB interno para este ambiente (output listener_arns[environment] do autopecas-infra-k8s). Usado junto com vpc_link_id."
  type        = string
  default     = null
  nullable    = true
  validation {
    condition     = var.application_listener_arn == null ? true : can(regex("^arn:aws:elasticloadbalancing:[a-z0-9-]+:[0-9]{12}:listener/net/.+$", var.application_listener_arn))
    error_message = "Informe o ARN de um listener de Network Load Balancer."
  }
}

variable "public_health_check" {
  description = "Expoe GET /actuator/health sem JWT, pela mesma integracao da aplicacao, para o monitor de disponibilidade. A aplicacao responde apenas UP ou DOWN, sem detalhes."
  type        = bool
  default     = false
}

variable "protected_routes" {
  description = "Rotas explicitas da aplicacao. Catalogo revisado em routes/application.json; nao e carregado automaticamente."
  type        = set(string)
  default     = []
  validation {
    condition = alltrue([
      for route in var.protected_routes :
      can(regex("^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|ANY) /[^ ]*$", route)) &&
      !contains(["POST /auth", "ANY /auth", "POST /auth/funcionarios", "ANY /auth/funcionarios", "ANY /{proxy+}", "ANY /"], route) &&
      !strcontains(route, "/.well-known")
    ])
    error_message = "Use rotas explicitas sem conflitar com POST /auth ou criar um proxy global."
  }
}
