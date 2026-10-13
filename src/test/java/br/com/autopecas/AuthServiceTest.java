package br.com.autopecas;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {

    private static final String SEGREDO = "segredo-de-teste-com-mais-de-32-caracteres!!";
    private final TokenService tokens =
        new TokenService(SEGREDO, Duration.ofMinutes(30), Clock.systemUTC());

    private AuthService servico(ClienteRepository repo) {
        return new AuthService(repo, tokens);
    }

    @Test
    void documentoInvalidoNaoConsultaOBanco() {
        var service = servico(d -> { throw new AssertionError("não deveria consultar o banco"); });
        assertInstanceOf(ResultadoAuth.DocumentoInvalido.class, service.autenticar("123"));
    }

    @Test
    void clienteNaoEncontrado() {
        var service = servico(d -> Optional.empty());
        assertInstanceOf(ResultadoAuth.ClienteNaoEncontrado.class, service.autenticar("52998224725"));
    }

    @Test
    void clienteInativo() {
        var service = servico(d -> Optional.of(new Cliente("id-1", false)));
        assertInstanceOf(ResultadoAuth.ClienteInativo.class, service.autenticar("52998224725"));
    }

    @Test
    void sucessoParaPfConsultandoPeloCpfNormalizado() {
        var consultado = new AtomicReference<Documento>();
        var service = servico(d -> {
            consultado.set(d);
            return Optional.of(new Cliente("id-1", true));
        });

        var sucesso = assertInstanceOf(ResultadoAuth.Sucesso.class, service.autenticar("529.982.247-25"));

        assertEquals(new Documento(Documento.Tipo.CPF, "52998224725"), consultado.get());
        var jwt = tokens.validar(sucesso.token()).orElseThrow();
        assertEquals("id-1", jwt.getSubject());
        assertEquals("PF", jwt.getClaim("tipo").asString());
    }

    @Test
    void sucessoParaPjComCnpjAlfanumerico() {
        var consultado = new AtomicReference<Documento>();
        var service = servico(d -> {
            consultado.set(d);
            return Optional.of(new Cliente("id-2", true));
        });

        var sucesso = assertInstanceOf(ResultadoAuth.Sucesso.class, service.autenticar("12.abc.345/01de-35"));

        assertEquals(new Documento(Documento.Tipo.CNPJ, "12ABC34501DE35"), consultado.get());
        assertEquals("PJ", tokens.validar(sucesso.token()).orElseThrow().getClaim("tipo").asString());
    }
}
