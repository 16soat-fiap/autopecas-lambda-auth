package br.com.autopecas.domain;

import java.util.Set;

public record FuncionarioAcesso(
        String id, boolean ativo, Set<String> roles) {
    public FuncionarioAcesso {
        roles = Set.copyOf(roles);
    }

    @Override
    public String toString() {
        return "FuncionarioAcesso[redacted]";
    }
}
