# Migração privada do banco no Learner Lab

Artefato administrativo separado da Lambda de autenticação.
Empacota as migrations originais da aplicação, preserva checksums e histórico
Flyway, aplica os GRANTs de leitura e verifica que `lambda_auth` não pode escrever.
Não cria funcionários ou permissões de ADMIN.

Na raiz do repositório:

~~~bash
mvn -B -ntp -f scripts/db-migration/pom.xml clean package
python3 scripts/db-migration/migrate-lab.py \
  --auth-config terraform/environments/homologacao.tfvars.json \
  --database-secrets ../autopecas-infra-db/terraform/environments/homologacao.secrets.tfvars.json
~~~

O projeto da aplicação fica por padrão em `../16SOAT-TechChallenge1`.
Para outro caminho, passe `-Dapp.project.dir=/caminho/absoluto` ao Maven.
O comando acima apenas confere configurações e sessão AWS. Acrescente
`--execute` para iniciar o RDS e aplicar as migrations.
Use `--admin-user` se o proprietário do schema não for `autopecas_admin`.

O executor usa uma função temporária na mesma rede e role da Lambda existente,
sem URL pública, EC2 ou NAT. O `finally` remove a função e seus logs e para
o RDS, inclusive em caso de erro. Interrupção forçada do processo ou falha
da AWS pode impedir a limpeza; nesse caso, confira os recursos no console.
Arquivos temporários de configuração têm permissão 0600 e são removidos.
Nenhum segredo é exibido pelo executor ou passado como argumento literal.

A role `lambda_auth` deve existir antes da execução. Não há `clean`,
`repair`, baseline automático ou carga de seeds. Apenas `db/migration` é
empacotado; `db/demo` fica excluído. A V4 foi ajustada antes de sua primeira aplicação
para o login por CPF e cria somente `funcionario_roles`.
Ela já foi aplicada no Lab: futuras alterações de schema devem usar uma nova
migration, preservando o arquivo e o checksum da V4.
A ferramenta não substitui a pipeline sob responsabilidade do outro integrante.

## Validação completa na AWS

Depois de publicar o artefato da Lambda e o Gateway via Terraform, execute
na raiz do repositório:

~~~bash
python3 scripts/validate-aws.py --api-id ID-DO-GATEWAY
python3 scripts/validate-aws.py --api-id ID-DO-GATEWAY --execute
~~~

A primeira chamada verifica a conta e o Gateway sem alterar recursos.
A segunda inicia o RDS, aplica as migrations, verifica os GRANTs, cria
sete cadastros sintéticos e valida os JWTs e o authorizer nativo.
Requer AWS CLI, Java/Maven para compilar o artefato administrativo e Python 3
com `cryptography`. Credenciais administrativas e chave RSA são lidas apenas
dos arquivos locais ignorados pelo Git; não são exibidas.

`ValidationFixtureHandler` pertence somente ao artefato administrativo.
As fixtures recebem UUIDs aleatórios e um marcador único por execução.
Nenhum cadastro existente é alterado. Uma rota protegida temporária encaminha
à Lambda existente: seu 404 de rota desconhecida confirma que o JWT passou
pelo authorizer. Isso não testa autorização de negócio na aplicação Kubernetes.

O `finally` remove a rota e sua permissão de invocação, os cadastros criados,
a função temporária e seus logs, e confirma o RDS parado.
O relatório fica em `target/aws-validation-report.json`, sem tokens ou CPFs.
As contas temporárias, inclusive ADMIN, são removidas; nenhum administrador
permanente é criado. A concessão ao funcionário real depende de seu cadastro.
