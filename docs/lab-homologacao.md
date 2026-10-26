# Teste de homologa??o no Learner Lab

Executado em 22/10/2026, em us-east-1.

## Recursos

- Uma Lambda Java 21: autopecas-auth-homologacao.
- HTTP API Gateway com authorizer JWT nativo, sem Cognito nem Lambda authorizer.
- RDS PostgreSQL 16 privado: autopecas-db-homologacao.
- Banco db.t3.micro Single-AZ com 20 GiB, sem crescimento autom?tico, backups
  ou snapshot final. A prote??o contra exclus?o permanece ativa.
- State separado por componente em S3 com criptografia, lock e versionamento.
- Nenhum ambiente de produ??o, NAT Gateway ou EC2 foi criado.

Endere?o de autentica??o:
https://7dn1wv9xv9.execute-api.us-east-1.amazonaws.com/homologacao/auth

Issuer:
https://7dn1wv9xv9.execute-api.us-east-1.amazonaws.com/homologacao

## Banco e permiss?es

Aplicadas V1 e V2 da aplica??o principal usando Flyway 11.14.1.
O hist?rico de migrations foi preservado no banco.
O usu?rio lambda_auth recebeu somente SELECT das colunas necess?rias;
uma consulta a nome foi rejeitada, enquanto id/ativo puderam ser lidos.

A prepara??o foi feita por uma fun??o tempor?ria na VPC, sem endpoint
p?blico. A fun??o e seus logs foram removidos. O schema completo continua
sob responsabilidade das migrations da aplica??o principal.

## Autentica??o testada

| Cen?rio com dados sint?ticos | Resultado |
| --- | --- |
| CPF ativo 529.982.247-25 | 200 com JWT |
| CPF inv?lido | 400 |
| CPF v?lido n?o cadastrado | 401 |
| CPF inativo 111.444.777-35 | 403 |
| CNPJ ativo | 200 com JWT |
| Corpo JSON null | 400 |
| Discovery e JWKS | 200 |

O token foi conferido: RS256, kid, issuer, audience autopecas-api,
scope api:access e aus?ncia do documento do cliente.

## Authorizer nativo testado

Foi criada uma rota tempor?ria com authorizer JWT e escopo api:access,
encaminhada ? Lambda j? existente. Um JWT v?lido chegou ao handler, que
retornou seu 404 de rota desconhecida. Essa resposta confirmou que a
valida??o do gateway permitiu executar a integra??o.

| Cen?rio | Resposta |
| --- | --- |
| Sem token | 401 |
| JWT v?lido | Handler executado |
| Audience incorreta | 401 |
| Issuer incorreto | 401 |
| Token expirado | 401 |
| Assinatura incorreta | 401 |
| Sem escopo exigido | 403 |

A rota e a permiss?o de invoca??o tempor?rias foram removidas.
N?o existe rota de teste adicional publicada permanentemente.

## Corre??o encontrada no deploy

No primeiro apply, o gateway tentou descobrir o issuer antes da propaga??o
das rotas p?blicas. O Terraform agora executa scripts/wait-for-jwks.py para
aguardar discovery e JWKS antes de criar o authorizer. O novo apply passou.

## Limites deste teste

A aplica??o Kubernetes ainda n?o est? integrada. N?o foi validado o
acesso a ordens de servi?o reais nem o bloqueio de acesso direto ao ingresso.
A pipeline de deploy continua a cargo do outro integrante.
N?o houve commit ou push nesta execu??o.

Os recursos ativos consomem cr?ditos. Parar o RDS interrompe a autentica??o
e reduz cobran?a de computa??o, mas n?o de armazenamento. Verifique o estado
do banco e o saldo no Learner Lab antes de cada sess?o.

## Economia após os testes

A parada do RDS foi solicitada em 22/10/2026, conforme escolha do usuário.
A autenticação fica indisponível enquanto o banco estiver parado.
O armazenamento continua sendo cobrado, e o RDS reinicia automaticamente
após sete dias consecutivos parado. Confira o saldo e o status no Lab.

Para reativar quando precisar testar:

~~~bash
aws rds start-db-instance --db-instance-identifier autopecas-db-homologacao --region us-east-1
aws rds wait db-instance-available --db-instance-identifier autopecas-db-homologacao --region us-east-1
~~~

Referência: [parada temporária do RDS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_StopInstance.html).

Estado confirmado após a parada: stopped.
