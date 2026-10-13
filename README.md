# autopecas-lambda-auth

Autenticação de clientes por CPF/CNPJ, com Java 21, PostgreSQL e JWT RS256.

## Estrutura do código

Os testes em `src/test/java` espelham os pacotes de `src/main/java`:

| Pacote em `br.com.autopecas` | Responsabilidade |
| --- | --- |
| `domain` | Cliente, documento e contrato do repositório |
| `domain.validation` | Validadores de CPF e CNPJ |
| `application` | Fluxo e resultados da autenticação |
| `infrastructure.persistence` | Consulta JDBC ao PostgreSQL |
| `infrastructure.security` | Emissão e validação de JWT |
| `handler` | Entradas e respostas das Lambdas |

As fixtures SQL ficam em `src/test/resources/db`.

## Execução e artefato

Execute com Java 21 e Maven:

    mvn clean verify

O artefato da função é `target/autopecas-lambda.jar`, com as dependências.
Os seis testes JDBC só são executados quando DB_URL está definida e exigem banco com
schema e clientes de teste preparados. O build sem banco não valida as consultas.

## Testes de integração com PostgreSQL

Requisitos: Bash, Java 21, Maven, Docker CLI, Docker Engine acessível e
Docker Compose v2 com suporte a --wait e --wait-timeout.
Confirme que docker compose version e docker info funcionam no mesmo terminal.

O Docker Engine deve estar na mesma máquina em que o Maven executa, pois a
conexão JDBC usa 127.0.0.1 e a fixture SQL é montada a partir do checkout local.
Daemons Docker remotos não são suportados por esta configuração.

Na raiz do projeto, execute:

    bash scripts/test-integration.sh

O script cria um PostgreSQL 16 temporário, carrega a fixture, aguarda o healthcheck
e executa mvn clean verify com os seis testes JDBC habilitados. Depois remove
os contêineres e volumes desse ambiente, inclusive em caso de falha.
Não usa credenciais DB_* previamente exportadas. As credenciais fixas do Compose
são exclusivas de teste, e a porta fica publicada somente em 127.0.0.1.

A porta padrão é 5433. Se estiver ocupada:

    TEST_DB_PORT=55433 bash scripts/test-integration.sh

O resultado esperado é a execução de todos os testes, sem falhas.
O build comum, sem DB_URL, continua ignorando os seis testes JDBC.

A fixture em src/test/resources/db/schema-e-dados.sql representa o schema
JOINED descrito no histórico. A correspondência com as entidades/migrations
atuais da aplicação principal ainda precisa ser confirmada; ela não é uma migration.

Para conferir o formato no banco da aplicação principal, execute o SQL somente
de leitura em scripts/verificar-documentos.sql. As contagens total e normalizados devem coincidir.
Uma tabela vazia não comprova o formato dos dados. O SQL verifica o formato,
não os dígitos verificadores, e não altera nem revela documentos completos.

Referências: [Compose up](https://docs.docker.com/reference/cli/docker/compose/up/)
e [imagem oficial PostgreSQL](https://hub.docker.com/_/postgres).

## Lambda de autenticação

Handler: `br.com.autopecas.handler.AuthHandler::handleRequest`.

Variáveis obrigatórias: DB_URL (jdbc:postgresql://host:5432/autopecas),
DB_USER, DB_PASSWORD, JWT_PRIVATE_KEY (chave RSA privada PKCS#8 em Base64),
JWT_ISSUER (URL HTTPS do emissor) e JWT_AUDIENCE (identificador da API).
A chave deve ter pelo menos 2048 bits e nunca deve ser versionada.
Veja a geração da chave e o deploy em [terraform](terraform/README.md).
O repositório consulta clientes, clientes_pf e clientes_pj;
os documentos devem estar normalizados, sem pontuação, com letras maiúsculas no CNPJ.
A configuração JDBC usa connectTimeout=5 e socketTimeout=10 segundos como padrões
(parâmetros explícitos na URL prevalecem), e queryTimeout=5 segundos.
Configure o timeout da Lambda com margem para conexão e consulta.

POST /auth, payload HTTP API 2.0:

    {"documento":"529.982.247-25"}

| Status | Resultado |
| --- | --- |
| 200 | {"token":"<JWT>"} |
| 400 | Documento ou corpo inválido |
| 401 | Cliente não encontrado |
| 403 | Cliente inativo |
| 500 | Falha interna, sem detalhes sensíveis |

Corpos Base64 são decodificados quando isBase64Encoded=true.
Logs de autenticação contêm requestId e resultado, sem documento ou token.

## Valida??o nativa no API Gateway

Existe somente uma Lambda: ela autentica e emite tokens RS256. O API Gateway
usa um authorizer JWT nativo para validar assinatura, issuer, audience,
expira??o e o escopo `api:access` exigido nas rotas protegidas.

A mesma Lambda atende duas rotas p?blicas de metadados, sem consultar o banco:

- `GET /.well-known/openid-configuration`: issuer e endere?o do JWKS.
- `GET /.well-known/jwks.json`: chave p?blica RSA, com `kid`, `n` e `e`.

O issuer inclui o stage do ambiente, por exemplo
`https://<api-id>.execute-api.us-east-1.amazonaws.com/homologacao`.
Os metadados fornecem descoberta para o gateway; n?o implementam um servidor
OAuth/OIDC completo nem exigem Cognito. A chave privada nunca aparece no JWKS.
O header `kid` identifica a chave, enquanto `aud` identifica a API de destino.

Envie `Authorization: Bearer <JWT>` nas rotas protegidas. Nas integra??es
Lambda, as claims ficam em `requestContext.authorizer.jwt.claims`.
Na integra??o HTTP, a aplica??o recebe o token no header Authorization;
n?o h? contexto do antigo authorizer Lambda.
O gateway n?o verifica o status atual do cliente nem aplica regras de neg?cio
de acesso aos recursos; essas regras continuam na aplica??o.

Tokens HS256 antigos deixam de ser aceitos: os clientes precisam autenticar
novamente. A segunda Lambda authorizer foi removida.

Refer?ncia: [JWT authorizers no API Gateway](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-jwt-authorizer.html).

## Limita??es

Os tokens expiram em 30 minutos. Desativar um cliente bloqueia novas emiss?es,
mas tokens existentes continuam v?lidos at? expirar. N?o h? revoga??o imediata.
O gateway pode manter chaves p?blicas em cache por at? duas horas.
A implementa??o publica uma chave por vez; uma rota??o sem interrup??o
exigir? publicar chaves antigas e novas simultaneamente durante a transi??o.

Autenticar apenas por documento n?o comprova posse da identidade. As respostas
401/403 permitem inferir cadastro e status. O uso real exigiria prova adicional
de identidade e controle de tentativas.

## Infraestrutura AWS

O Terraform da Lambda e do API Gateway está em [terraform](terraform/README.md),
com configurações separadas para homologação e produção no AWS Academy Learner Lab.
O deploy depende da role existente, rede e banco configurados.
