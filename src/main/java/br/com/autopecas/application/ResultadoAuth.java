package br.com.autopecas.application;

/** Desfechos possíveis de uma tentativa de autenticação. */
public sealed interface ResultadoAuth {

    record Sucesso(String token) implements ResultadoAuth {}

    record DocumentoInvalido() implements ResultadoAuth {}

    record ClienteNaoEncontrado() implements ResultadoAuth {}

    record ClienteInativo() implements ResultadoAuth {}
}
