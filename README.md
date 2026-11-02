# autopecas-lambda-auth

Autenticação serverless do Autopeças: valida CPF/CNPJ de clientes ou CPF de funcionários, consulta o cadastro no PostgreSQL e emite JWT RS256 para acesso às APIs protegidas.

Este repositório contém **uma Lambda por ambiente**, o **API Gateway HTTP API** e sua configuração Terraform. Não provisiona o banco nem o cluster Kubernetes.

## Tecnologias

| Componente | Tecnologia |
| --- | --- |
| Função serverless | AWS Lambda, Java 21 e AWS Lambda Java Events |
| Entrada e proteção das APIs | Amazon API Gateway HTTP API e authorizer JWT nativo |
| Tokens | JWT RS256, java-jwt e discovery/JWKS |
| Consulta ao banco | JDBC e PostgreSQL 16 |
| JSON | Jackson |
| Build e testes | Maven, JUnit 5, Docker e Compose v2 |
| Infraestrutura | Terraform >= 1.10 e < 2.0, AWS provider 6.x |
| Estado e logs | Backend S3 com lockfile; CloudWatch Logs |

## Arquitetura

~~~mermaid
flowchart LR
  U["Cliente ou funcionário"] -->|"HTTPS"| G

  subgraph REPO["Este repositório"]
    G["API Gateway HTTP API<br/>rotas e authorizer JWT nativo"]
    L["Lambda Java 21<br/>validação de documento, cadastro e roles<br/>emissão de JWT RS256"]
    CW["CloudWatch<br/>logs de acesso e autenticação"]
    G -->|"POST /auth e /auth/funcionarios<br/>GET discovery/JWKS"| L
    G -.->|"access logs"| CW
    L -.->|"logs JSON sem documento/token"| CW
  end

  subgraph EXTERNO["Infraestrutura dos outros repositórios"]
    DB[("RDS PostgreSQL privado<br/>TLS e usuário lambda_auth")]
    V["VPC Link"]
    N["NLB interno"]
    K["Aplicação Spring Boot no EKS<br/>validação JWT, roles e propriedade por UUID"]
    V --> N --> K
  end

  L -->|"SELECT das colunas necessárias"| DB
  G -->|"rotas protegidas: JWT + api:access"| V
  G -.->|"GET /actuator/health, se habilitado"| V
~~~

A mesma função atende clientes, funcionários e metadados. A validação do token no Gateway é nativa; não existe uma segunda Lambda authorizer e não há dependência de Keycloak ou Cognito.

A integração utilizada pelo projeto é privada: **Gateway → VPC Link → NLB interno → NodePort → aplicação**. O VPC Link e o listener são outputs de [autopecas-infra-k8s](https://github.com/16soat-fiap/autopecas-infra-k8s). O banco e os grants pertencem a [autopecas-infra-db](https://github.com/16soat-fiap/autopecas-infra-db). As regras de negócio e migrations ficam em [16SOAT-TechChallenge1](https://github.com/16soat-fiap/16SOAT-TechChallenge1).

## Execução local e testes

Requisitos de build: Java 21 e Maven. Na raiz:

~~~bash
mvn clean verify
~~~

O artefato é **target/autopecas-lambda.jar**, com as dependências. O comando não inicia um servidor HTTP local: o handler é invocado pela Lambda na AWS.

Para executar também as consultas JDBC, use Docker Engine local, Compose v2 com --wait/--wait-timeout e Bash:

~~~bash
bash scripts/test-integration.sh
~~~

O script cria PostgreSQL 16 descartável, carrega a fixture, executa os testes e remove containers/volumes ao terminar. Usa credenciais exclusivas de teste e porta 5433 em 127.0.0.1. Se necessário:

~~~bash
TEST_DB_PORT=55433 bash scripts/test-integration.sh
~~~

Sem DB_URL, o build comum ignora os testes JDBC. O script de integração exige os **6 testes de clientes e 4 de funcionários**, sem skips. O Docker daemon precisa estar na máquina do Maven; essa configuração não suporta daemon remoto. A fixture de testes não deve ser aplicada no RDS.

Para validar Terraform sem provisionar:

~~~bash
export TF_DATA_DIR="$PWD/terraform/.terraform/validacao"
terraform -chdir=terraform init -backend=false -input=false
terraform -chdir=terraform fmt -check -recursive
terraform -chdir=terraform validate
terraform -chdir=terraform test
~~~

Gere o JAR antes: o Terraform calcula seu hash. Os testes Terraform usam provider simulado.

## Configuração

Handler: **br.com.autopecas.handler.AuthHandler::handleRequest**.

| Variável da Lambda | Finalidade |
| --- | --- |
| DB_URL | URL JDBC PostgreSQL, com sslmode=require no RDS |
| DB_USER / DB_PASSWORD | Credenciais de leitura, normalmente lambda_auth |
| JWT_PRIVATE_KEY | RSA de pelo menos 2048 bits, PKCS#8 DER em Base64 |
| JWT_ISSUER | URL HTTPS do Gateway incluindo o stage; calculada pelo Terraform |
| JWT_AUDIENCE | Destinatário do JWT; padrão Terraform autopecas-api |

As consultas usam timeouts de conexão/socket e de query. A configuração Terraform atual define 512 MiB e timeout de 25 segundos para a função.

O schema precisa conter as migrations da aplicação, incluindo **V4__permissoes_funcionarios.sql**. O usuário lambda_auth deve ter SELECT nas colunas usadas de clientes, clientes_pf, clientes_pj, funcionarios e funcionario_roles. Não use o administrador na Lambda.

Documentos de clientes devem seguir a normalização esperada pelos repositórios. Para conferir o armazenamento, há uma consulta somente de leitura em [scripts/verificar-documentos.sql](scripts/verificar-documentos.sql).

## Deploy na AWS

### Pré-requisitos

- Sessão AWS válida e região definida, com permissão para Terraform, Lambda, Gateway e uso da role existente.
- No Learner Lab, role como LabRole com trust para Lambda, logs e interfaces de rede; identidade de deploy com iam:PassRole.
- RDS acessível nas sub-redes e SGs informados; saída/entrada TCP 5432 autorizada por SG.
- Bucket S3 existente, com criptografia, versionamento e acesso restrito.
- Migrations e grants executados pelo responsável pelo banco.
- OpenSSL para preparar a chave; Python 3 para a espera de discovery/JWKS.

A Lambda não precisa de NAT apenas para acessar o RDS privado.

### 1. Preparar o ambiente

Os exemplos são separados por homologacao/producao. Execute em Bash e revise os valores antes do plano:

~~~bash
export AMBIENTE=homologacao
cp -n "terraform/environments/${AMBIENTE}.tfvars.example" "terraform/environments/${AMBIENTE}.tfvars"
cp -n "terraform/environments/${AMBIENTE}.s3.tfbackend.example" "terraform/environments/${AMBIENTE}.s3.tfbackend"
~~~

Preencha role, subnets, SGs, URL JDBC e usuário. Configure a chave S3 de state do ambiente. Arquivos .tfvars, .tfvars.json, .tfbackend, planos, states e chaves locais são ignorados pelo Git.

Se você já utiliza arquivos .tfvars.json, mantenha-os e ajuste o nome no comando de plan; não sobrescreva a configuração existente.

### 2. Fornecer os segredos

Na primeira implantação, gere a chave **uma vez por ambiente**. O teste de existência evita substituir o arquivo existente:

~~~bash
umask 077
export JWT_KEY_FILE="jwt-private-${AMBIENTE}.pem"
test -f "$JWT_KEY_FILE" || openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$JWT_KEY_FILE"
export TF_VAR_jwt_private_key="$(openssl pkcs8 -topk8 -nocrypt -in "$JWT_KEY_FILE" -outform DER | openssl base64 -A)"
read -rs -p "Senha do usuario de leitura do banco: " TF_VAR_db_password
printf '\n'
export TF_VAR_db_password
~~~

Para um ambiente existente, reutilize sua chave já configurada. Não gere uma chave nova a cada deploy. Segredos também podem vir de arquivo local protegido, passado como outro -var-file.

Senha e chave são sensíveis na saída, mas fazem parte do state e de planos salvos. Não os publique.

### 3. Configurar a integração com a aplicação

No arquivo de variáveis do ambiente, informe os outputs reais do Kubernetes:

~~~hcl
vpc_link_id              = "ID_REAL_DO_VPC_LINK"
application_listener_arn  = "ARN_REAL_DO_LISTENER_DO_AMBIENTE"
public_health_check      = true
~~~

Use listener_arns["homologacao"] ou listener_arns["producao"], conforme o ambiente. public_health_check é opcional e seu padrão é false.

Como alternativa ao modo privado, application_url aceita uma origem HTTPS pública, sem caminho ou barra final. **Não configure application_url junto com vpc_link_id.**

### 4. Gerar e aplicar o plano

~~~bash
export TF_DATA_DIR="$PWD/terraform/.terraform/${AMBIENTE}"
terraform -chdir=terraform init -reconfigure -backend-config="environments/${AMBIENTE}.s3.tfbackend"
terraform -chdir=terraform plan -var-file="environments/${AMBIENTE}.tfvars" -var-file=routes/application.json -out="${AMBIENTE}.tfplan"
terraform -chdir=terraform apply "${AMBIENTE}.tfplan"
~~~

Revise o plano antes do apply. Se usar .tfvars.json ou um arquivo local de segredos, passe os nomes correspondentes no plan.

O [catálogo de rotas](terraform/routes/application.json) possui 44 rotas de negócio explícitas. Ele não é carregado automaticamente: mantenha-o em todos os planos da aplicação integrada, para não planejar remoção de rotas existentes. Seu valor padrão é vazio.

Para produção, repita com AMBIENTE=producao, state, credenciais, chave e listener próprios. O Terraform espera discovery/JWKS ficarem disponíveis antes de criar o authorizer e cria a rota de funcionários antes de aplicar seu throttling.

### 5. Obter os endpoints

~~~bash
terraform -chdir=terraform output -raw api_url
terraform -chdir=terraform output -raw auth_url
terraform -chdir=terraform output -raw jwt_issuer
terraform -chdir=terraform output -raw jwks_url
~~~

Use a URL do ambiente fornecida pelos outputs. IDs do Gateway podem mudar em recriações; endereços de validações antigas não são tratados como URLs permanentes.

O procedimento acima é o deploy manual disponível neste checkout. Não há workflow CI/CD versionado aqui; a automação de deploy deve preservar os mesmos parâmetros e o catálogo.

## API e Postman

**[Coleção Postman da autenticação e do Gateway](postman/autopecas-auth.postman_collection.json).**

Importe o arquivo em Postman → Import. Na aba Variables da coleção, configure base_url com o output api_url, sem barra final, e informe os documentos de cadastros existentes. Os logins bem-sucedidos preenchem access_token automaticamente para a requisição protegida de exemplo.

A coleção versionada mantém documentos, identificadores e token vazios. A rota protegida também depende da integração com a aplicação e das permissões do usuário.

| Método | Endpoint | Entrada ou resposta |
| --- | --- | --- |
| POST | /auth | {"documento":"CPF_OU_CNPJ"} → {"token":"JWT"} |
| POST | /auth/funcionarios | {"cpf":"CPF_DO_FUNCIONARIO"} → {"token":"JWT"} |
| GET | /.well-known/openid-configuration | Issuer e endereço de JWKS |
| GET | /.well-known/jwks.json | RSA pública, sem chave privada |
| GET | /actuator/health | Público somente se public_health_check=true |

A Lambda não possui Swagger próprio. Para a aplicação de negócio, veja o [README da aplicação](https://github.com/16soat-fiap/16SOAT-TechChallenge1#-endpoints-da-api). Executada localmente, ela expõe [Swagger UI](http://localhost:8080/swagger-ui.html) e [OpenAPI JSON](http://localhost:8080/v3/api-docs). Esses links exigem a aplicação local em execução.

Swagger e /v3/api-docs **não fazem parte do catálogo de 44 rotas do Gateway**. Não se deve presumir que /swagger-ui.html esteja publicado na URL externa do Gateway.

### Respostas e identidade

| Situação | Status |
| --- | --- |
| Identidade habilitada | 200 com token |
| Documento/corpo inválido | 400 |
| Cliente ausente | 401 |
| Cliente inativo | 403 |
| Funcionário ausente, inativo ou sem roles | 401 |
| Falha interna | 500, sem detalhes do banco |

Funcionários aceitam CPF com ou sem máscara. Identidades ambíguas e roles desconhecidas não são habilitadas. O corpo do login não concede permissões.

O token contém sub=UUID, perfil CLIENTE/FUNCIONARIO, roles e scope=api:access, além de issuer, audience e validade. tipo=PF/PJ aparece somente para clientes; kid identifica a chave no cabeçalho. CPF/CNPJ não entram no JWT nem nos logs.

Envie Authorization: Bearer TOKEN nas rotas protegidas. O Gateway verifica assinatura/claims/escopo; a aplicação revalida o token e decide acesso a recursos e operações. Logs do Gateway/Lambda possuem requestId e resultado, sem documento ou token.

## Administração e validação

### Permissões de funcionários

Não há ADMIN padrão nem endpoint público de bootstrap. Depois de cadastrar um funcionário ativo, um operador com acesso ao banco privado pode conceder roles:

~~~bash
export DB_URL="jdbc:postgresql://HOST_PRIVADO:5432/autopecas?sslmode=require"
export ADMIN_DB_USER="USUARIO_ADMINISTRATIVO"
read -rs -p "Senha administrativa do banco: " ADMIN_DB_PASSWORD
printf '\n'
export ADMIN_DB_PASSWORD
java -cp target/autopecas-lambda.jar br.com.autopecas.tools.ProvisionarFuncionario UUID-DO-FUNCIONARIO ADMIN
unset ADMIN_DB_PASSWORD
~~~

O comando substitui as roles em transação; para combinar, use ADMIN,ATENDENTE ou outra combinação permitida. A senha é do banco administrativo, não do login do funcionário. As credenciais administrativas não devem ser usadas pela Lambda.

### Migração privada e teste AWS

O artefato em scripts/db-migration é administrativo e separado da Lambda pública. Empacota migrations da aplicação, preserva histórico Flyway, aplica grants e verifica leitura restrita. Não cria administrador permanente.

~~~bash
mvn -B -ntp -f scripts/db-migration/pom.xml clean package
python3 scripts/db-migration/migrate-lab.py --auth-config terraform/environments/homologacao.tfvars.json --database-secrets ../autopecas-infra-db/terraform/environments/homologacao.secrets.tfvars.json
python3 scripts/validate-aws.py --api-id ID-DO-GATEWAY
~~~

As chamadas Python acima fazem pré-checagens. **Adicionar --execute altera a AWS:** a migração inicia o RDS e aplica Flyway/grants; a validação cria cadastros sintéticos e uma rota temporária. Ambos os executores tentam limpar seus recursos e parar o RDS ao terminar. Não execute --execute se pretende deixá-lo ligado sem reativação posterior.

Os executores são voltados à homologação do Lab; não substituem o Job Flyway de produção.

O projeto da aplicação é procurado em ../16SOAT-TechChallenge1. Outro caminho pode ser passado ao Maven com -Dapp.project.dir=/caminho/absoluto. O executor de migração aceita --admin-user quando necessário. A validação exige Python com cryptography e lê os arquivos locais de configuração/segredos esperados pelo script.

Interrupção forçada ou falha da AWS pode impedir limpeza: confira cadastros, função, logs, rota e permissão temporários. O relatório fica em target/aws-validation-report.json. Esses testes comprovam autenticação e authorizer; não substituem testes de negócio e propriedade no EKS.

### Registro das validações anteriores

Em 22/10/2026 foram validados autenticação de clientes, metadados e authorizer. Em 25/10/2026 foram aplicadas V4/grants e aprovadas 34 verificações, incluindo funcionários, assinatura, issuer, audience, validade e escopo. Cadastros e recursos temporários foram removidos.

São resultados históricos, não uma confirmação de disponibilidade atual do Lab ou de um endpoint permanente.

## Limitações do fluxo

CPF/CNPJ identifica cadastro, mas não comprova posse da identidade. Conhecer o CPF de um funcionário habilitado permite obter suas permissões; esse é o limite do contrato solicitado.

Tokens duram 30 minutos. Desativação ou mudança de roles impede novas emissões, sem revogar imediatamente tokens existentes. A rota de funcionários tem limite de 2 requisições/s e burst 5, sem bloqueio individual por conta.

O JWKS publica uma chave por vez; rotação sem interrupção exigiria sobreposição de chaves e planejamento de cache. JWT HS256 do contrato antigo não é aceito. O banco precisa estar disponível para novos logins; discovery/JWKS não consultam o RDS.
