package br.com.autopecas;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class TokenServiceTest {

    private static final String SEGREDO = "segredo-de-teste-com-mais-de-32-caracteres!!";
    private final Cliente cliente = new Cliente("id-1", true);

    private TokenService servico(String segredo, Clock relogio) {
        return new TokenService(segredo, Duration.ofMinutes(30), relogio);
    }

    @Test
    void geraTokenComAsClaimsEsperadas() {
        var tokens = servico(SEGREDO, Clock.systemUTC());
        var jwt = tokens.validar(tokens.gerar(cliente, Documento.Tipo.CPF)).orElseThrow();

        assertEquals("id-1", jwt.getSubject());
        assertEquals(TokenService.ISSUER, jwt.getIssuer());
        assertEquals("PF", jwt.getClaim("tipo").asString());
        assertTrue(jwt.getExpiresAt().after(jwt.getIssuedAt()));
    }

    @Test
    void tokenDePjTemTipoPj() {
        var tokens = servico(SEGREDO, Clock.systemUTC());
        var jwt = tokens.validar(tokens.gerar(cliente, Documento.Tipo.CNPJ)).orElseThrow();
        assertEquals("PJ", jwt.getClaim("tipo").asString());
    }

    @Test
    void rejeitaTokenExpirado() {
        var passado = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
        var tokens = servico(SEGREDO, passado);
        assertTrue(servico(SEGREDO, Clock.systemUTC()).validar(tokens.gerar(cliente, Documento.Tipo.CPF)).isEmpty());
    }

    @Test
    void rejeitaTokenAssinadoComOutroSegredo() {
        var emissor = servico(SEGREDO, Clock.systemUTC());
        var outro = servico("outro-segredo-tambem-com-mais-de-32-caracteres", Clock.systemUTC());
        assertTrue(outro.validar(emissor.gerar(cliente, Documento.Tipo.CPF)).isEmpty());
    }

    @Test
    void rejeitaTokenMalformadoOuNulo() {
        var tokens = servico(SEGREDO, Clock.systemUTC());
        assertTrue(tokens.validar("isso-nao-e-um-jwt").isEmpty());
        assertTrue(tokens.validar(null).isEmpty());
    }

    @Test
    void recusaSegredoCurtoOuAusente() {
        assertThrows(IllegalArgumentException.class, () -> servico("curto", Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> servico(null, Clock.systemUTC()));
    }
}
