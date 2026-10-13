package br.com.autopecas;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2CustomAuthorizerEvent;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizerHandlerTest {
    private static final String SECRET = "segredo-de-teste-com-mais-de-32-caracteres!!";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TokenService tokens = new TokenService(SECRET, Duration.ofMinutes(30), CLOCK);
    private final AuthorizerHandler handler = new AuthorizerHandler(tokens);

    private Map<String, Object> autorizar(String valor) {
        var event = new APIGatewayV2CustomAuthorizerEvent();
        event.setVersion("2.0");
        event.setHeaders(Collections.singletonMap("Authorization", valor));
        return handler.handleRequest(event, null);
    }

    private String token() {
        return tokens.gerar(new Cliente("cliente-1", true), Documento.Tipo.CPF);
    }

    @Test
    void permiteTokenEEntregaContextoSerializavel() throws Exception {
        var response = autorizar("bEaReR " + token());
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));
        assertTrue(json.path("isAuthorized").asBoolean());
        assertEquals("cliente-1", json.path("context").path("clienteId").asText());
        assertEquals("PF", json.path("context").path("tipo").asText());
        assertFalse(json.toString().contains(token()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Bearer", "Bearer ", "Basic abc", "Bearer abc", "Bearer a b", "Bearer a,b"})
    void negaCabecalhoInvalido(String valor) {
        assertEquals(Map.of("isAuthorized", false, "context", Map.of()), autorizar(valor));
    }

    @Test
    void negaEventoAusenteVersaoInvalidaECabecalhosAmbiguos() {
        assertEquals(false, handler.handleRequest(null, null).get("isAuthorized"));
        var event = new APIGatewayV2CustomAuthorizerEvent();
        assertEquals(false, handler.handleRequest(event, null).get("isAuthorized"));
        event.setVersion("2.0");
        assertEquals(false, handler.handleRequest(event, null).get("isAuthorized"));
        event.setHeaders(Map.of("authorization", "Bearer " + token(), "Authorization", "Bearer " + token()));
        assertEquals(false, handler.handleRequest(event, null).get("isAuthorized"));
        event.setHeaders(Map.of("authorization", "Bearer " + token()));
        event.setVersion("1.0");
        assertEquals(false, handler.handleRequest(event, null).get("isAuthorized"));
        event.setVersion("2.0");
        assertEquals(true, handler.handleRequest(event, null).get("isAuthorized"));
    }

    @Test
    void negaTokenExpiradoOuComAssinaturaDiferente() {
        var later = new TokenService(SECRET, Duration.ofMinutes(30), Clock.fixed(NOW.plusSeconds(1800), ZoneOffset.UTC));
        var event = new APIGatewayV2CustomAuthorizerEvent();
        event.setVersion("2.0");
        event.setHeaders(Map.of("authorization", "Bearer " + token()));
        assertEquals(false, new AuthorizerHandler(later).handleRequest(event, null).get("isAuthorized"));
        var other = new TokenService("outro-segredo-com-mais-de-32-caracteres", Duration.ofMinutes(30), CLOCK);
        assertEquals(false, autorizar("Bearer " + other.gerar(new Cliente("id", true), Documento.Tipo.CPF)).get("isAuthorized"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "sub", "iat", "exp", "tipo", "blank", "unknown", "future", "order"})
    void negaClaimsInvalidasMesmoComAssinaturaCorreta(String caso) {
        var builder = JWT.create();
        if (!caso.equals("iss")) builder.withIssuer(TokenService.ISSUER);
        if (!caso.equals("sub")) builder.withSubject(caso.equals("blank") ? " " : "cliente-1");
        if (!caso.equals("iat")) builder.withIssuedAt(NOW.plusSeconds(caso.equals("future") ? 60 : 0));
        if (!caso.equals("exp")) builder.withExpiresAt(NOW.plusSeconds(caso.equals("order") ? 0 : 1800));
        if (!caso.equals("tipo")) builder.withClaim("tipo", caso.equals("unknown") ? "ADMIN" : "PF");
        assertEquals(false, autorizar("Bearer " + builder.sign(Algorithm.HMAC256(SECRET))).get("isAuthorized"));
    }

    @Test
    void permitePj() {
        var token = tokens.gerar(new Cliente("pj-1", true), Documento.Tipo.CNPJ);
        assertEquals(Map.of("clienteId", "pj-1", "tipo", "PJ"), autorizar("Bearer " + token).get("context"));
    }

    @Test
    void rejeitaConfiguracaoInvalida() {
        assertThrows(IllegalArgumentException.class, () -> new TokenService(" ".repeat(32), Duration.ofMinutes(30), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> new TokenService(SECRET, Duration.ZERO, CLOCK));
        assertThrows(IllegalArgumentException.class, () -> new TokenService(SECRET, Duration.ofMillis(500), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> new TokenService(SECRET, null, CLOCK));
        assertThrows(NullPointerException.class, () -> new TokenService(SECRET, Duration.ofMinutes(30), null));
        assertThrows(IllegalArgumentException.class, () -> new JdbcClienteRepository(null, "user", "password"));
        assertThrows(IllegalArgumentException.class, () -> new JdbcClienteRepository("jdbc:postgresql://localhost/db", "", "password"));
        assertThrows(IllegalArgumentException.class, () -> new JdbcClienteRepository("jdbc:postgresql://localhost/db", "user", ""));
        assertThrows(IllegalArgumentException.class, () -> tokens.gerar(new Cliente(" ", true), Documento.Tipo.CPF));
        assertThrows(IllegalArgumentException.class, () -> tokens.gerar(new Cliente("id", false), Documento.Tipo.CPF));
        assertThrows(NullPointerException.class, () -> tokens.gerar(new Cliente("id", true), null));
    }

}
