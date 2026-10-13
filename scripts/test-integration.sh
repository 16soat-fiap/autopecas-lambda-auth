#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

for dependency in docker java mvn; do
    if ! command -v "$dependency" >/dev/null 2>&1; then
        echo "Dependencia ausente: $dependency. Instale-a e disponibilize-a no PATH." >&2
        exit 1
    fi
done

if ! docker compose version >/dev/null 2>&1; then
    echo "Docker Compose v2 indisponivel. Instale ou habilite o plugin Compose para o Docker CLI." >&2
    exit 1
fi
if ! docker info >/dev/null 2>&1; then
    echo "Docker Engine inacessivel. Verifique o daemon, o contexto Docker e as permissoes de acesso." >&2
    exit 1
fi
if ! docker compose up --help | grep -q -- '--wait-timeout'; then
    echo "Atualize o Docker Compose v2: este script requer up --wait e --wait-timeout." >&2
    exit 1
fi

export TEST_DB_PORT="${TEST_DB_PORT:-5433}"
compose=(docker compose -p "autopecas-auth-test-$$" -f "$project_dir/compose.test.yaml")

cleanup() {
    result=$?
    trap - EXIT
    if (( result != 0 )); then
        "${compose[@]}" logs --no-color postgres-test >&2 || true
    fi
    if ! "${compose[@]}" down --volumes; then
        echo "Falha ao remover o ambiente de teste." >&2
        if (( result == 0 )); then result=1; fi
    fi
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"${compose[@]}" up --wait --wait-timeout 90 postgres-test

# Sobrescreve credenciais externas para executar somente contra a fixture local.
export DB_URL="jdbc:postgresql://127.0.0.1:${TEST_DB_PORT}/autopecas_test"
export DB_USER=autopecas_test
export DB_PASSWORD=autopecas_test

mvn -B -ntp clean verify

# Evita sucesso silencioso se os testes JDBC forem ignorados.
report=target/surefire-reports/TEST-br.com.autopecas.infrastructure.persistence.JdbcClienteRepositoryTest.xml
if [[ ! -f "$report" ]] ||
   ! grep -Eq 'tests="6"' "$report" ||
   ! grep -Eq 'skipped="0"' "$report"; then
    echo "Os seis testes JDBC precisam executar sem skipped." >&2
    exit 1
fi
