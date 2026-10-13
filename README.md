# autopecas-lambda-auth

Autenticação de clientes por CPF/CNPJ, com Java 21, PostgreSQL e JWT HS256.

## Execução e artefato

Execute com Java 21 e Maven:

    mvn clean verify

O artefato para ambas as funções é `target/autopecas-lambda.jar`, com as dependências.
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

Handler: `br.com.autopecas.AuthHandler::handleRequest`.

Variáveis obrigatórias: DB_URL (jdbc:postgresql://host:5432/autopecas),
DB_USER, DB_PASSWORD e JWT_SECRET (segredo aleatório, com pelo menos 32 caracteres).
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

## Lambda authorizer

Handler: `br.com.autopecas.AuthorizerHandler::handleRequest`.
Requer somente JWT_SECRET, igual ao da função de autenticação; não acessa o banco.

Na futura configuração do HTTP API Gateway:

- Usar authorizer REQUEST, payload 2.0 e enableSimpleResponses=true.
- Usar identity source `$request.header.Authorization`.
- Configurar authorizerResultTtlInSeconds=0 para desativar o cache.
- Manter POST /auth público e associar o authorizer às rotas protegidas.

Enviar `Authorization: Bearer <JWT>`. A resposta usa isAuthorized e context,
com clienteId e tipo (PF/PJ) quando autorizado; a integração Lambda recebe
esses campos em requestContext.authorizer.lambda.
Headers ambíguos, tokens inválidos e claims ausentes são negados.
A validação exige HS256, issuer autopecas-auth, sub não vazio, iat, exp e tipo PF/PJ.
O mesmo relógio é usado na emissão e validação, permitindo testes determinísticos.
A resposta do authorizer é uma decisão de acesso, não uma resposta HTTP;
o API Gateway determina o status HTTP da rejeição.

Contrato AWS:
https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-lambda-authorizer.html
