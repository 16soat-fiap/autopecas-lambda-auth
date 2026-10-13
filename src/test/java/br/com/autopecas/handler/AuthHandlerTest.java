package br.com.autopecas.handler;

import br.com.autopecas.application.AuthService;
import br.com.autopecas.domain.Cliente;
import br.com.autopecas.domain.ClienteRepository;
import br.com.autopecas.infrastructure.security.TokenService;
import br.com.autopecas.infrastructure.security.TestTokens;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import java.util.Base64;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AuthHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuthHandler handlerCom(ClienteRepository repo) {
        var tokens = TestTokens.criar(Clock.systemUTC());
        return new AuthHandler(new AuthService(repo, tokens), tokens);
    }

    private static String corpo(String documento) {
        return "{\"documento\":\"" + documento + "\"}";
    }

    @Test
    void retorna200ComTokenParaClienteAtivo() throws Exception {
        var handler = handlerCom(d -> Optional.of(new Cliente("id-1", true)));

        var resposta = handler.processar(corpo("529.982.247-25"), "req-1");

        assertEquals(200, resposta.getStatusCode());
        assertFalse(MAPPER.readTree(resposta.getBody()).path("token").asText().isBlank());
    }

    @Test
    void retorna400ParaDocumentoInvalido() {
        var handler = handlerCom(d -> Optional.empty());
        assertEquals(400, handler.processar(corpo("123"), "req-2").getStatusCode());
    }

    @Test
    void retorna400QuandoOCorpoNaoTemDocumento() {
        var handler = handlerCom(d -> Optional.empty());
        assertEquals(400, handler.processar(null, "req-3").getStatusCode());
        assertEquals(400, handler.processar("{}", "req-3").getStatusCode());
    }

    @Test
    void retorna400ParaJsonMalformado() {
        var handler = handlerCom(d -> Optional.empty());
        assertEquals(400, handler.processar("{isso nao e json", "req-4").getStatusCode());
    }

    @Test
    void retorna401QuandoClienteNaoExiste() {
        var handler = handlerCom(d -> Optional.empty());
        assertEquals(401, handler.processar(corpo("52998224725"), "req-5").getStatusCode());
    }

    @Test
    void retorna403QuandoClienteEstaInativo() {
        var handler = handlerCom(d -> Optional.of(new Cliente("id-1", false)));
        assertEquals(403, handler.processar(corpo("11222333000181"), "req-6").getStatusCode());
    }

    @Test
    void retorna500SemVazarDetalhesQuandoOBancoFalha() {
        var handler = handlerCom(d -> { throw new IllegalStateException("detalhe-interno-secreto"); });

        var resposta = handler.processar(corpo("52998224725"), "req-7");

        assertEquals(500, resposta.getStatusCode());
        assertFalse(resposta.getBody().contains("detalhe-interno-secreto"));
    }
    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "123", "true", "{\"documento\":123}", "{\"documento\":null}"})
    void corpoInvalidoRetorna400SemConsultarBanco(String corpo) {
        var auth = handlerCom(d -> { throw new AssertionError("Banco nao deve ser consultado"); });
        assertEquals(400, auth.processar(corpo, "request-1").getStatusCode());
    }

    @Test
    void aceitaBase64ERejeitaBase64Invalido() {
        var auth = handlerCom(d -> Optional.of(new Cliente("id", true)));
        var event = new APIGatewayV2HTTPEvent();
        event.setIsBase64Encoded(true);
        event.setBody(Base64.getEncoder().encodeToString("{\"documento\":\"52998224725\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(200, auth.handleRequest(event, null).getStatusCode());
        event.setBody("%%%");
        assertEquals(400, auth.handleRequest(event, null).getStatusCode());
        assertEquals(400, auth.handleRequest(null, null).getStatusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET /.well-known/openid-configuration", "GET /.well-known/jwks.json"})
    void publicaMetadadosSemConsultarBanco(String route) throws Exception {
        var tokens = TestTokens.criar(Clock.systemUTC());
        var handler = new AuthHandler(new AuthService(d -> {
            throw new AssertionError("Metadados nao consultam o banco");
        }, tokens), tokens);
        var event = new APIGatewayV2HTTPEvent();
        event.setRouteKey(route);
        var response = handler.handleRequest(event, null);
        assertEquals(200, response.getStatusCode());
        var body = MAPPER.readTree(response.getBody());
        if (route.endsWith("openid-configuration")) {
            assertEquals(TestTokens.ISSUER, body.path("issuer").asText());
            assertEquals(TestTokens.ISSUER + "/.well-known/jwks.json", body.path("jwks_uri").asText());
        } else {
            assertEquals("RSA", body.path("keys").get(0).path("kty").asText());
            assertFalse(body.path("keys").get(0).has("d"));
        }
        assertFalse(response.getBody().contains(TestTokens.PRIVATE_KEY));
    }

    @Test
    void rejeitaRotaDesconhecida() {
        var event = new APIGatewayV2HTTPEvent();
        event.setRouteKey("GET /auth");
        assertEquals(404, handlerCom(d -> { throw new AssertionError(); }).handleRequest(event, null).getStatusCode());
    }
}
