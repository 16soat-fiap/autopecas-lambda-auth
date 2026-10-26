# Login de funcionários por CPF

A mesma Lambda atende clientes e funcionários. O fluxo de funcionários usa
somente CPF, conforme a decisão do projeto; não utiliza email nem senha.
A implementação foi publicada e validada na AWS em 25/10/2026.
Veja [resultados da validação](validacao-aws-cpf.md).

## Contrato

~~~http
POST /auth/funcionarios
Content-Type: application/json

{"cpf":"529.982.247-25"}
~~~

O CPF pode ter máscara ou apenas dígitos. A Lambda valida os dígitos
verificadores, consulta `funcionarios.cpf`, exige cadastro ativo e carrega
as permissões de `funcionario_roles`. Cadastro ausente, inativo ou sem
permissões retorna 401; CPF inválido ou JSON malformado retorna 400.
Falhas internas retornam 500 sem detalhes do banco. Cadastros com o mesmo CPF
em formatos diferentes são rejeitados, evitando identidade ambígua.

A resposta 200 contém `token`: JWT RS256 com `perfil=FUNCIONARIO`,
`sub=UUID`, `scope=api:access` e `roles` vindas exclusivamente do banco.
CPF, email e senha não são incluídos no token nem registrados nos logs.
As roles informadas no corpo da requisição não concedem acesso.

Clientes continuam em `POST /auth`, com `documento` CPF/CNPJ,
`perfil=CLIENTE`, `roles=[CLIENTE]` e `tipo=PF/PJ`.
A aplicação valida assinatura, issuer, audience, exp/iat, escopo e
consistência de perfil/roles. As regras de propriedade usam o UUID de `sub`.
Os tokens anteriores exigem nova autenticação.

## Banco e permissões

1. Aplicar `V4__permissoes_funcionarios.sql` pelas migrations da aplicação.
   A V3 de demonstração permanece preservada; a V4 cria apenas a tabela de roles.
2. Aplicar os GRANTs de `autopecas-infra-db/scripts/criar-usuario-lambda.sql`.
   A Lambda recebe SELECT de `funcionarios(id,cpf,ativo)` e `funcionario_roles`.
3. Cadastrar o funcionário e conceder explicitamente suas permissões.

Não há tabela de senhas, contas padrão ou endpoint público de bootstrap.
ADMIN é uma permissão, distinta do tipo profissional ATENDENTE ou MECANICO.
O primeiro administrador precisa de concessão controlada no banco.

## Concessão administrativa

Na raiz deste repositório, compile com `mvn clean verify`.
Execute em uma máquina que alcance o banco privado. Configure `DB_URL` e
`ADMIN_DB_USER` com um usuário autorizado a gravar as permissões.
A senha abaixo pertence ao banco administrativo; o funcionário não usa senha.

~~~bash
read -rs -p "Senha administrativa do banco: " ADMIN_DB_PASSWORD; echo
export ADMIN_DB_PASSWORD
java -cp target/autopecas-lambda.jar br.com.autopecas.tools.ProvisionarFuncionario UUID-DO-FUNCIONARIO ADMIN
unset ADMIN_DB_PASSWORD
~~~

Para combinar permissões, use `ADMIN,ATENDENTE`, por exemplo.
O comando exige funcionário existente e ativo e substitui suas roles em
transação. A role de banco da Lambda permanece somente de leitura.

## Publicação coordenada

- Banco: Flyway V4, GRANTs e concessão de roles ao funcionário escolhido.
- Lambda/gateway: novo artefato, rota pública e permissão de invocação.
- Aplicação: converter JWT e `JWT_ISSUER`, `JWT_JWKS_URI`, `JWT_AUDIENCE`.
- Clientes: obter tokens do contrato novo.

O utilitário administrativo de migração está em `scripts/db-migration`.
Ele é um artefato separado, sem rota pública, e não faz parte da Lambda
permanente de autenticação. A pipeline continua com o outro integrante;
o handoff está em `docs/autenticacao-lambda.md` no repositório da aplicação.

## Limites

Conhecer o CPF de um funcionário habilitado permite obter suas permissões:
o CPF identifica a pessoa, mas não comprova sua identidade. Esta é uma
limitação do fluxo solicitado, especialmente para ADMIN.
O gateway limita a rota a 2 requisições/s e burst 5; isso não é
bloqueio por conta nem substitui um fator de autenticação.

Desativar funcionário ou remover roles impede novas emissões, mas não
revoga tokens já emitidos, que duram até 30 minutos.
Os testes locais não precisam do RDS. O teste inicial de Lab está em `lab-homologacao.md`; a validação atual
de funcionários está em `validacao-aws-cpf.md`.
