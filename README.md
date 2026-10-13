# autopecas-lambda-auth

Autenticação de clientes por CPF/CNPJ, com Java 21, PostgreSQL e JWT HS256.

## Execução e artefato

Execute no WSL, com Java 21 e Maven:

    mvn clean verify

O artefato para ambas as funções é `target/autopecas-lambda.jar`, com as dependências.
Os seis testes JDBC só executam quando DB_URL está definida e exigem banco com
schema e clientes de teste preparados. O build sem banco não valida as consultas.

## Lambda de autenticação

Handler: `br.com.autopecas.AuthHandler::handleRequest`.

Variáveis obrigatórias: DB_URL (jdbc:postgresql://host:5432/autopecas),
DB_USER, DB_PASSWORD e JWT_SECRET (segredo aleatório, com pelo menos 32 caracteres).
Não versione os valores. O repositório consulta clientes, clientes_pf e clientes_pj;
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
Logs de autenticação contém requestId e resultado, sem documento ou token.

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

## Limitações e próximas etapas

Os tokens expiram em 30 minutos. Desativar um cliente bloqueia novas emissões,
mas tokens existentes continuam válidos até expirar, mesmo com cache desativado.
Revogação imediata exigiria consultar o status ou manter uma lista de revogação.

Autenticar apenas por documento não comprova posse da identidade. As respostas
401/403 também permitem inferir cadastro e status. O contrato segue a proposta
do desafio; um uso real exigiria prova adicional de identidade e controle de tentativas.

Terraform, deploy, integração com as rotas da aplicação e preparação automatizada
do banco de teste ainda não fazem parte desta entrega.
