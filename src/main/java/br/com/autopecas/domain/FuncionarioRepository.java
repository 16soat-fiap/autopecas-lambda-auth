package br.com.autopecas.domain;

import java.util.Optional;

public interface FuncionarioRepository {
    Optional<FuncionarioAcesso> buscarPorCpf(String cpfNormalizado);
}
