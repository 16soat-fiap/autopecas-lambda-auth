# Lambda e API Gateway na AWS

O Terraform cria uma Lambda Java 21 para autentica??o, um HTTP API Gateway
com authorizer JWT nativo e logs CloudWatch. Usa a role existente do Learner Lab.
N?o cria banco, cluster Kubernetes, roles ou policies IAM.

## Arquitetura

~~~mermaid
flowchart LR
  C[Cliente] --> G[API Gateway]
  G -->|POST /auth| A[Lambda de autentica??o]
  A --> B[(Banco gerenciado)]
  G -->|Discovery e JWKS p?blicos| A
  G --> V[Valida??o JWT nativa]
  V -->|RS256 e escopo api:access| K[Aplica??o Kubernetes]
~~~

A mesma Lambda pública metadados de descoberta e sua chave p?blica.
Somente POST /auth consulta o banco. O issuer ? a URL do gateway incluindo o
stage. Terraform usa essa mesma URL na Lambda e no authorizer, sem c?pia manual.
As rotas de descoberta s?o publicadas antes da cria??o do authorizer.

## Pr?-requisitos

- Java 21, Maven, Python 3 e Terraform >= 1.10 e < 2.0; OpenSSL para gerar a chave RSA.
- Sess?o ativa do Learner Lab e credenciais tempor?rias AWS_ACCESS_KEY_ID,
  AWS_SECRET_ACCESS_KEY e AWS_SESSION_TOKEN.
- Role existente (por exemplo LabRole), com trust para Lambda, permiss?es de logs
  e interfaces de rede para VPC. A identidade de deploy precisa de iam:PassRole.
- Sub-redes, security groups e banco acess?vel, com schema e usu?rio de leitura.
- Bucket S3 existente, criptografado, com versionamento e acesso restrito.
  O backend usa lockfile S3. [Documenta??o do backend](https://developer.hashicorp.com/terraform/language/backend/s3).

## Validar sem provisionar

Na raiz do projeto:

~~~bash
mvn clean verify
terraform -chdir=terraform init -backend=false
terraform -chdir=terraform fmt -check -recursive
terraform -chdir=terraform validate
terraform -chdir=terraform test
~~~

Os testes Terraform usam provider simulado; n?o acessam a AWS.
Execute bash scripts/test-integration.sh para testar tamb?m o PostgreSQL.
Versione .terraform.lock.hcl. O hash da Lambda exige que o JAR esteja gerado.

## Preparar configura??o e segredos

Copie os exemplos de um ambiente e substitua role, rede, URL JDBC e usu?rio.
Os exemplos cont?m valores fict?cios; .tfvars e .tfbackend locais s?o ignorados.

~~~bash
cp terraform/environments/homologacao.tfvars.example terraform/environments/homologacao.tfvars
cp terraform/environments/homologacao.s3.tfbackend.example terraform/environments/homologacao.s3.tfbackend
~~~

Em Bash, gere uma chave RSA de 2048 bits por ambiente. O umask protege o arquivo.
A convers?o produz DER PKCS#8 em Base64, sem cabe?alhos PEM nem quebras de linha:

~~~bash
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
export TF_VAR_jwt_private_key="$(openssl pkcs8 -topk8 -nocrypt -in jwt-private.pem -outform DER | openssl base64 -A)"
read -rs -p "Senha do banco: " TF_VAR_db_password; echo
export TF_VAR_db_password
~~~

Guarde a chave em local seguro para os pr?ximos deploys; n?o gere uma nova em
cada pipeline. JWT_AUDIENCE ? configurada por jwt_audience, com padr?o autopecas-api.
JWT_SECRET n?o ? mais usado. JWT_ISSUER ? calculado pelo Terraform.

Chave privada e senha ficam ocultas na sa?da normal, mas s?o armazenadas no
state e em planos salvos, pois configuram as vari?veis da Lambda. Proteja o
bucket e esses arquivos. Secrets Manager requereria alterar o carregamento Java.
A chave p?blica derivada ? publicada em JWKS; a privada nunca ? retornada.

## Plano e deploy

Use configura??es, chaves e state diferentes por ambiente. TF_DATA_DIR evita
misturar a configura??o local dos backends; as chaves S3 tamb?m s?o distintas.

~~~bash
export TF_DATA_DIR="$PWD/terraform/.terraform/homologacao"
terraform -chdir=terraform init -reconfigure -backend-config=environments/homologacao.s3.tfbackend
terraform -chdir=terraform plan -var-file=environments/homologacao.tfvars -out=homologacao.tfplan
~~~

Revise o plano. O comando seguinte cria recursos e pode gerar custos no Lab:

~~~bash
terraform -chdir=terraform apply homologacao.tfplan
terraform -chdir=terraform output auth_url
terraform -chdir=terraform output jwt_issuer
terraform -chdir=terraform output jwks_url
~~~

Para produ??o, use os exemplos, TF_DATA_DIR e segredos correspondentes.
As credenciais do Lab expiram e precisam ser renovadas.
Nenhum recurso foi aplicado durante a prepara??o desta configura??o.

## Rotas e integra??o

POST /auth e os GETs de /.well-known/openid-configuration e
/.well-known/jwks.json s?o p?blicos. A Lambda fica na VPC do banco:
configure sa?da TCP 5432 e a entrada correspondente no SG do banco.
N?o precisa de NAT apenas para consultar um banco na rede privada.

Quando o ingresso Kubernetes existir, configure application_url e
protected_routes, incluindo explicitamente cole??es e itens. Todas essas
rotas exigem JWT e escopo api:access. O gateway confere RSA, kid, issuer,
audience e validade; n?o consulta o status do cliente a cada requisi??o.
A aplica??o continua respons?vel pela autoriza??o de acesso aos seus recursos.

A integração HTTP_PROXY aceita duas origens: uma URL HTTPS pública
(application_url) ou, sem ingresso público, o VPC Link e o listener do NLB
interno do repositório autopecas-infra-k8s (vpc_link_id e
application_listener_arn, este com o listener do ambiente).
Com public_health_check = true, GET /actuator/health fica público (sem JWT)
para o monitor de disponibilidade; nenhuma outra rota da aplicação muda.
Antes da entrega, impe?a acesso direto ao ingresso p?blico ou valide o token
tamb?m na aplica??o. O header Authorization ? encaminhado ? aplica??o;
o contexto do antigo authorizer Lambda n?o existe mais.

## Expira??o, migra??o e rota??o

Tokens duram 30 minutos; desativa??o de cliente n?o revoga tokens emitidos.
Tokens HS256 antigos n?o s?o aceitos; autentique novamente ap?s a migra??o.
O gateway pode guardar chaves p?blicas em cache por at? duas horas.
Esta implementa??o pública uma chave por vez. Trocar a chave imediatamente
pode interromper acessos, inclusive tokens novos enquanto a chave est? em cache.
Rota??o sem interrup??o exige suporte a sobreposi??o de chaves no JWKS
durante a transi??o; isso ainda n?o est? implementado.

A infraestrutura anterior de duas Lambdas n?o foi aplicada. Caso tenha sido
provisionada externamente, revise cuidadosamente a remo??o da fun??o antiga
antes de aplicar está configura??o.

Refer?ncia: [JWT authorizers na AWS](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-jwt-authorizer.html).

## Pr?ximas etapas

Confirmar recursos do Lab, validar um plano real, testar discovery/JWKS e fluxo
protegido na AWS, criar CI/CD por PR para os dois ambientes e concluir
correla??o de logs, RFC/ADR e especifica??o OpenAPI/Postman.

## Rotas da aplicação

O catálogo com 44 endpoints e as pendências de segurança para integrar
o Kubernetes estão em [rotas do API Gateway](../docs/rotas-api-gateway.md).
As rotas ainda não foram aplicadas na AWS.

## Login de funcionários

O novo fluxo POST /auth/funcionarios, o login por CPF e a migração
sem Keycloak estão documentados em [login de funcionários](../docs/login-funcionarios.md).
A Lambda e o Gateway foram atualizados e validados na AWS em 25/10/2026.
Veja [resultados e limites](../docs/validacao-aws-cpf.md).
