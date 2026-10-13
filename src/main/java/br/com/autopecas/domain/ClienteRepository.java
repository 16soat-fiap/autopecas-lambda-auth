package br.com.autopecas.domain;

import java.util.Optional;

public interface ClienteRepository {

    /** Procura o cliente pelo documento já validado e normalizado. */
    Optional<Cliente> buscarPorDocumento(Documento documento);
}
