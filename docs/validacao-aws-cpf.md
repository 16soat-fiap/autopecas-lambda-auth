# Validação AWS do login por CPF

Executada em 25/10/2026, no Learner Lab, região us-east-1.

## Componentes publicados

- Lambda existente `autopecas-auth-homologacao`, Java 21, 512 MiB.
- HTTP API Gateway existente, com authorizer JWT nativo.
- Nova rota pública `POST /auth/funcionarios`, com limite de 2 requisições/s e burst 5.
- Migration `V4__permissoes_funcionarios.sql`, preservando V1/V2 e o histórico Flyway.
- GRANTs de leitura de funcionários e roles para `lambda_auth`.

A verificação confirmou que `lambda_auth` não possui permissões de escrita
nas tabelas do schema public. A chave RSA, a role IAM, a VPC e o tamanho
dos recursos existentes foram preservados. Não houve criação de banco,
EC2, NAT ou infraestrutura de produção.

## Endereços

- Clientes: https://7dn1wv9xv9.execute-api.us-east-1.amazonaws.com/homologacao/auth
- Funcionários: https://7dn1wv9xv9.execute-api.us-east-1.amazonaws.com/homologacao/auth/funcionarios
- Issuer: https://7dn1wv9xv9.execute-api.us-east-1.amazonaws.com/homologacao

Funcionários enviam `{"cpf":"CPF-DO-CADASTRO"}`. Clientes usam
`{"documento":"CPF-OU-CNPJ"}`. Ambos exigem cadastro ativo; funcionários
precisam de roles concedidas explicitamente no banco.

## Resultados

| Cenário | Resultado |
| --- | --- |
| ADMIN, ATENDENTE e MECANICO ativos | 200, JWT com a role correspondente |
| CPF com máscara ou somente dígitos | 200 |
| Cliente ativo | 200, JWT com perfil CLIENTE |
| Funcionário inativo ou sem roles | 401 |
| Cliente inativo | 403 |
| CPF exclusivo de cliente no login de funcionários | 401 |
| CPF inválido, campo numérico, campo ausente ou JSON malformado | 400 |
| Apenas email e senha no login de funcionários | 400 |
| Role ADMIN informada no corpo de outro perfil | Ignorada; roles do banco preservadas |
| Discovery e JWKS | 200, chave pública RSA sem material privado |
| Rota protegida sem token | 401 |
| JWT válido de cliente ou funcionário | Aceito pelo authorizer nativo |
| Assinatura incorreta, issuer ou audience incorretos, token expirado | 401 |
| Escopo api:access ausente | 403 |

As assinaturas foram verificadas localmente com a chave pública publicada
em JWKS. Os tokens contêm sub=UUID, perfil e roles coerentes, issuer,
audience, iat, exp e scope. CPF, documento, email e senha não entram no JWT.
A duração verificada foi de até 30 minutos.

## Correção encontrada no deploy

O API Gateway rejeitou a primeira atualização de RouteSettings porque a rota
ainda estava sendo criada. O stage agora depende explicitamente de staff_auth.
Os nove testes Terraform passaram e a reaplicação concluiu a atualização.

## Limpeza e limites

Os sete cadastros sintéticos foram removidos, incluindo suas roles.
Nenhum ADMIN permanente foi criado: o funcionário real ainda precisa ser
cadastrado e receber permissão por procedimento administrativo.

A rota protegida usada no teste apontava para a Lambda existente. Seu 404
`Rota inexistente` após apresentar JWT válido comprovou a passagem pelo
authorizer; não representa teste de uma operação de negócio.
A aplicação Kubernetes ainda não está integrada ao Gateway.
A autorização de recursos e roles da aplicação continua validada localmente.

CPF sozinho não comprova identidade: quem conhece o CPF de um funcionário
habilitado pode obter suas permissões. Esse é o limite do fluxo solicitado.

A execução reproduzível está em `scripts/validate-aws.py`; o relatório
sem tokens ou CPFs fica em `target/aws-validation-report.json`.
A pipeline de deploy permanece sob responsabilidade do outro integrante.
Nenhum commit ou push foi realizado nesta validação.

Resultado final: **34 verificações aprovadas**, sem erros de limpeza.
A função temporária, seus logs, a rota e a permissão de invocação foram
removidos e conferidos na AWS. Um grupo de logs reapareceu por envio tardio;
foi removido novamente, e o executor passou a conferir esse caso ao final.
O hash do JAR publicado corresponde ao artefato local validado.
O plano final Terraform retornou `No changes`.

Estado final confirmado do RDS: **stopped**. Os logins ficam indisponíveis
até reativar o banco; o armazenamento continua sendo cobrado.
