package br.com.autopecas;

import java.util.Optional;

/** Regra de autenticação: documento válido, cliente existente e ativo, então emite o token. */
public class AuthService {

    private final ClienteRepository clientes;
    private final TokenService tokens;

    public AuthService(ClienteRepository clientes, TokenService tokens) {
        this.clientes = clientes;
        this.tokens = tokens;
    }

    public ResultadoAuth autenticar(String documentoBruto) {
        Optional<Documento> documento = Documento.parse(documentoBruto);
        if (documento.isEmpty()) {
            return new ResultadoAuth.DocumentoInvalido();
        }

        Optional<Cliente> cliente = clientes.buscarPorDocumento(documento.get());
        if (cliente.isEmpty()) {
            return new ResultadoAuth.ClienteNaoEncontrado();
        }
        if (!cliente.get().ativo()) {
            return new ResultadoAuth.ClienteInativo();
        }

        return new ResultadoAuth.Sucesso(tokens.gerar(cliente.get(), documento.get().tipo()));
    }
}
