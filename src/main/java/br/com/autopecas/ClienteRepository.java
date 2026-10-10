package br.com.autopecas;

import java.util.Optional;

public interface ClienteRepository {

    /** Recebe o CPF só com dígitos (use CpfValidator.normalize antes). */
    Optional<Cliente> buscarPorCpf(String cpf);
}
