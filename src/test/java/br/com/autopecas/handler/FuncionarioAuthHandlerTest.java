package br.com.autopecas.handler;

import static org.junit.jupiter.api.Assertions.*;

import br.com.autopecas.application.*;
import br.com.autopecas.domain.*;
import br.com.autopecas.infrastructure.security.*;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.*;

class FuncionarioAuthHandlerTest {
    private final TokenService tokens = TestTokens.criar(Clock.systemUTC());

    private AuthHandler handler(FuncionarioRepository repository) {
        return new AuthHandler(
                new AuthService(
                        d -> {
                            throw new AssertionError();
                        },
                        tokens),
                tokens,
                new FuncionarioAuthService(repository, tokens));
    }

    private APIGatewayV2HTTPEvent event(String body) {
        var event = new APIGatewayV2HTTPEvent();
        event.setRouteKey("POST /auth/funcionarios");
        event.setBody(body);
        return event;
    }

    private String body(Map<String, Object> values) {
        try {
            return new ObjectMapper().writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rolesDoCorpoNaoConcedemAcessoAdmin() throws Exception {
        var response =
                handler(
                                e ->
                                        Optional.of(
                                                new FuncionarioAcesso(
                                                        "00000000-0000-0000-0000-000000000010",
                                                        true,
                                                        Set.of("MECANICO"))))
                        .handleRequest(
                                event(
                                        body(
                                                Map.of(
                                                        "cpf",
                                                        "529.982.247-25",
                                                        "roles",
                                                        List.of("ADMIN")))),
                                null);
        assertEquals(200, response.getStatusCode());
        String token = new ObjectMapper().readTree(response.getBody()).get("token").asText();
        assertEquals(
                List.of("MECANICO"),
                tokens.validar(token).orElseThrow().getClaim("roles").asList(String.class));
    }

    @Test
    void falhasDeCredencialTemRespostaGenerica() {
        var h = handler(e -> Optional.empty());
        for (String input :
                Arrays.asList(
                        null,
                        "null",
                        "[]",
                        "{}",
                        "invalid",
                        body(Map.of("cpf", "invalido")))) {
            var response = h.handleRequest(event(input), null);
            assertEquals(400, response.getStatusCode());
            assertFalse(response.getBody().contains("hash"));
        }
    }

    @Test
    void desconhecidoOuSemAcessoRetorna401() {
        var response = handler(cpf -> Optional.empty()).handleRequest(event(body(Map.of("cpf", "52998224725"))), null);
        assertEquals(401, response.getStatusCode());
    }

    @Test
    void erroDeBancoNaoVazaDetalhes() {
        var response =
                handler(
                                e -> {
                                    throw new IllegalStateException("segredo");
                                })
                        .handleRequest(event(body(Map.of("cpf", "52998224725"))), null);
        assertEquals(500, response.getStatusCode());
        assertFalse(response.getBody().contains("segredo"));
    }
}
