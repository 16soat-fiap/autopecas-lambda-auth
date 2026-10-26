# RFC: autenticação sem Keycloak

Status: Lambda e Gateway publicados e validados na AWS; aplicação validada localmente. Veja [login de funcionários](login-funcionarios.md).

## Decisão proposta

Manter uma única Lambda como emissor de JWT RS256 para clientes e funcionários.
O API Gateway valida assinatura, issuer, audience, validade e api:access.
A aplicação valida o token e aplica roles e propriedade dos recursos.

| Identidade | Entrada | Endpoint |
| --- | --- | --- |
| Cliente | CPF/CNPJ e cadastro ativo | POST /auth |
| Funcionário | CPF válido, cadastro ativo e roles concedidas | POST /auth/funcionarios |

O login de funcionários fica público no gateway para receber credenciais;
as APIs de negócio continuam protegidas. Não há autopromoção, cadastro público
de funcionários nem criação pública de administrador.

## Identidade no token

Manter issuer, audience, kid, iat, exp e scope=api:access.
sub é sempre o UUID interno do cliente ou funcionario.
Adicionar perfil=CLIENTE ou FUNCIONARIO e roles como array.
Clientes recebem somente CLIENTE. Funcionarios recebem roles carregadas do banco:
ATENDENTE, MECANICO e/ou ADMIN. Nenhuma role vem do corpo do login.

tipo=PF/PJ continua somente para clientes.
A aplicação não usa email ou preferred_username para resolver propriedade;
usa perfil e sub, verificando o cliente ativo e o dono do recurso.
Os pontos que hoje identificam funcionários por email também precisam migrar para UUID.
Tokens anteriores precisarao de nova autenticação ao ativar o novo contrato.

## Identificação e privilégios

Por decisão do projeto, funcionários fazem login somente com CPF, sem email
ou senha. O CPF é validado e consultado no cadastro; as roles são concedidas
explicitamente na tabela funcionario_roles, nunca recebidas do login.
ADMIN é permissão e não altera o tipo profissional.

Conhecer o CPF de uma pessoa habilitada permite obter um token em seu nome.
Esse fluxo não comprova identidade e fica limitado ao requisito adotado.
CPF inválido retorna 400; cadastro ausente, inativo ou sem roles retorna 401.
A Lambda usa banco somente de leitura. Não há administrador padrão,
autopromoção ou endpoint público de concessão de permissões.

## Migração coordenada

1. Criar o contrato de identidade e as migration de permissões na aplicação.
2. Implementar login de funcionários na Lambda e emissao das duas identidades.
3. Atualizar decoder, converter e regras de propriedade da aplicação.
4. Retirar Keycloak do Compose, configurações, Swagger, manifests e documentação.
5. Coordenar com o responsável pela pipeline a retirada das etapas de Keycloak;
   a pipeline de deploy não será implementada neste trabalho.
6. Atualizar gateway, permissões de invocação e testes para POST /auth/funcionarios.
7. Testar clientes, funcionários e administrador sem acesso indevido cruzado.

Preservar roles e @PreAuthorize; não liberar tudo para fazer os testes passarem.
Manter probes de saúde internas sem exigir JWT.
Remover converter e artefatos do Keycloak somente junto com seus substitutos.
Os resultados da validação no Lab estão em [validação AWS](validacao-aws-cpf.md).

## Critérios de verificação

- Cliente autentica e acessa apenas recursos proprios.
- Cliente não acessa dashboard ou operacoes de funcionários.
- Atendente e mecânico tem permissões distintas.
- Administrador precisa de atribuição explícita no banco.
- CPF inválido, cadastro sem roles e funcionário inativo não emitem token.
- Roles fornecidas no corpo de login não concedem privilégios.
- Assinatura, issuer, audience, expiração, scope e tipo de identidade sao validados.
- Não há dependencia operacional de Keycloak.

Referência: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
