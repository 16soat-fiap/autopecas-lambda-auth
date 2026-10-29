# Rotas da aplicação no API Gateway

Mapeamento preparado a partir dos oito controllers de 16SOAT-TechChallenge1,
revisados em 22/10/2026. Referência local da aplicação: f703eb8.
São 44 rotas explícitas, sem ANY ou proxy global.

O arquivo terraform/routes/application.json contém apenas protected_routes.
Ele é versionável, não contém segredos e não é carregado automaticamente.
Assim, preparar o catálogo não altera o gateway existente.

## Autenticação e acesso

No gateway, todas as rotas do catálogo exigem JWT RS256 válido para o issuer
e audience configurados, com escopo api:access. Permanecem públicos somente
POST /auth e os dois endpoints de discovery/JWKS da Lambda.

Os controllers têm autorização adicional por @PreAuthorize:
clientes só podem acessar recursos próprios nas operações permitidas;
dashboard, cadastros, funcionários e operações internas exigem roles
de funcionários. O catálogo não atribui essas roles nem substitui essas regras.

A migração local retirou a dependencia operacional do antigo Identity Provider.
Cliente e funcionário usam o mesmo issuer da Lambda. A aplicação interpreta
perfil/roles e UUID de sub, mantendo as regras de propriedade e os bloqueios
administrativos. O novo login ainda precisa de deploy coordenado nos dois repositorios.

POST /auth/funcionarios também e público e não integra as 44 rotas de negocio.
Veja [login de funcionários](login-funcionarios.md) para migrations, grants e bootstrap.

## Catálogo

### ClienteController.java

| Método | Caminho |
| --- | --- |
| GET | `/api/clientes` |
| GET | `/api/clientes/{id}` |
| GET | `/api/clientes/buscarDOC` |
| POST | `/api/clientes/pf` |
| POST | `/api/clientes/pj` |
| PUT | `/api/clientes/{id}` |
| DELETE | `/api/clientes/{id}` |

### DashboardController.java

| Método | Caminho |
| --- | --- |
| GET | `/api/dashboard` |
| GET | `/api/dashboard/tempo-medio-execucao` |

### FuncionarioController.java

| Método | Caminho |
| --- | --- |
| GET | `/api/funcionarios/{id}` |
| GET | `/api/funcionarios` |
| POST | `/api/funcionarios/mecanico` |
| POST | `/api/funcionarios/atendente` |
| DELETE | `/api/funcionarios/{id}` |

### OrcamentoController.java

| Método | Caminho |
| --- | --- |
| POST | `/api/ordens-servico/{osId}/orcamentos` |
| GET | `/api/ordens-servico/{osId}/orcamentos` |
| PATCH | `/api/ordens-servico/{osId}/orcamentos/{id}/enviar` |
| PATCH | `/api/ordens-servico/{osId}/orcamentos/{id}/aprovar` |
| PATCH | `/api/ordens-servico/{osId}/orcamentos/{id}/rejeitar` |

### OrdemServicoController.java

| Método | Caminho |
| --- | --- |
| GET | `/api/ordens-servico` |
| GET | `/api/ordens-servico/{numero}` |
| POST | `/api/ordens-servico` |
| PATCH | `/api/ordens-servico/{id}/status` |
| PATCH | `/api/ordens-servico/{id}/diagnostico` |
| PATCH | `/api/ordens-servico/{id}/mecanico` |

### PecaController.java

| Método | Caminho |
| --- | --- |
| GET | `/api/pecas` |
| GET | `/api/pecas/{id}` |
| GET | `/api/pecas/buscar` |
| POST | `/api/pecas` |
| PUT | `/api/pecas/{id}` |
| DELETE | `/api/pecas/{id}` |
| POST | `/api/pecas/{id}/movimentacoes` |

### ServicoController.java

| Método | Caminho |
| --- | --- |
| GET | `/api/servicos` |
| GET | `/api/servicos/{id}` |
| POST | `/api/servicos` |
| PUT | `/api/servicos/{id}` |
| DELETE | `/api/servicos/{id}` |

### VeiculoController.java

| Método | Caminho |
| --- | --- |
| GET | `/api/veiculos` |
| GET | `/api/veiculos/{id}` |
| GET | `/api/veiculos/placa/{placa}` |
| GET | `/api/veiculos/cliente/{clienteId}` |
| POST | `/api/veiculos` |
| PUT | `/api/veiculos/{id}` |
| DELETE | `/api/veiculos/{id}` |

## Habilitar quando o Kubernetes estiver publicado

Esta configuração suporta uma origem HTTPS pública sem caminho ou barra final.
Preencha application_url no tfvars local, por exemplo https://api.exemplo.com,
e revise os IDs da rede e os segredos. Não use localhost nem endereço fictício
em um apply.

Para ingresso privado, sem URL pública, informe no lugar de application_url
os outputs do repositório autopecas-infra-k8s: vpc_link_id e
application_listener_arn = listener_arns["homologacao"] (ou ["producao"]).
O catálogo de rotas é o mesmo nos dois modos.

Com public_health_check = true, o gateway expõe também GET /actuator/health,
sem JWT, pela mesma integração: é o que o monitor de disponibilidade do New
Relic consulta, pelo mesmo caminho do cliente. É a única rota da aplicação sem
authorizer, e exata — o resto do Actuator continua inalcançável pelo gateway.
A aplicação responde só {"status":"UP"} ou DOWN, sem detalhes.

Na raiz do repositório, com o backend de homologação já configurado:

~~~bash
export TF_DATA_DIR="$PWD/terraform/.terraform/homologacao"
terraform -chdir=terraform plan -var-file=environments/homologacao.tfvars.json -var-file=environments/homologacao.secrets.tfvars.json -var-file=routes/application.json -out=homologacao.tfplan
~~~

Se você usa .tfvars em HCL em vez de .tfvars.json, ajuste o nome do arquivo.
O catálogo deve ser o último -var-file para que protected_routes seja carregado.
O plano falhará sem application_url, impedindo integração incompleta.

Revise o plano antes de aplicar. A integração HTTP_PROXY preserva o caminho
/api/... sem o stage homologação, assim como método, corpo e parâmetros de query.
Authorization é encaminhado sem sobrescrita; a aplicação precisa validar e
interpretar o token. Parâmetros de query, como clienteId, não integram o nome
da rota e precisam ser autorizados pela aplicação.

O responsável pela pipeline deve usar esse catálogo de forma consistente
nos planos e deploys da aplicação integrada. Sem o arquivo, o valor padrão
de protected_routes é vazio é um apply futuro pode remover rotas já criadas.

## Verificações antes da entrega

- Sem token, assinatura inválida ou token expirado: rejeição no gateway.
- Sem api:access: rejeição por escopo.
- Cliente válido: acesso permitido somente aos seus recursos.
- Cliente tentando acessar outro cliente ou operação administrativa: rejeição pela aplicação.
- Caminho e parâmetros de query preservados no backend.
- Nenhuma forma de contornar o gateway pelo ingresso.
- Atualizar o catálogo quando os controllers mudarem.

Swagger, Actuator e login do Keycloak não foram incluídos no catálogo.
As probes do Kubernetes devem continuar alcançando o serviço internamente.

Referências: [rotas HTTP API](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-routes.html)
e [mapeamento de parâmetros](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-parameter-mapping.html).

Nenhuma rota de negócio foi aplicada na AWS nesta preparação.
O RDS não foi religado.
