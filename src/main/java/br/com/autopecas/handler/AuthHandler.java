package br.com.autopecas.handler;

import br.com.autopecas.application.AuthService;
import br.com.autopecas.application.FuncionarioAuthService;
import br.com.autopecas.application.ResultadoAuth;
import br.com.autopecas.infrastructure.persistence.JdbcClienteRepository;
import br.com.autopecas.infrastructure.persistence.JdbcFuncionarioRepository;
import br.com.autopecas.infrastructure.security.TokenService;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** POST /auth com corpo {"documento": "<CPF ou CNPJ>"}. */
public class AuthHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuthService service;
    private final TokenService tokens;
    private final FuncionarioAuthService funcionarios;

    public AuthHandler() {
        this(TokenService.fromEnv());
    }

    private AuthHandler(TokenService tokens) {
        this(
                new AuthService(JdbcClienteRepository.fromEnv(), tokens),
                tokens,
                new FuncionarioAuthService(JdbcFuncionarioRepository.fromEnv(), tokens));
    }

    AuthHandler(AuthService service, TokenService tokens) {
        this(service, tokens, null);
    }

    AuthHandler(AuthService service, TokenService tokens, FuncionarioAuthService funcionarios) {
        this.service = service;
        this.tokens = tokens;
        this.funcionarios = funcionarios;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        if (event != null) {
            if ("GET /.well-known/openid-configuration".equals(event.getRouteKey())) {
                return resposta(200, tokens.discovery());
            }
            if ("GET /.well-known/jwks.json".equals(event.getRouteKey())) {
                return resposta(200, tokens.jwks());
            }
            if (event.getRouteKey() != null
                    && !"POST /auth".equals(event.getRouteKey())
                    && !"POST /auth/funcionarios".equals(event.getRouteKey())) {
                return resposta(404, Map.of("erro", "Rota inexistente"));
            }
        }
        String requestId = context == null ? null : context.getAwsRequestId();
        String corpo = event == null ? null : event.getBody();
        if (event != null && Boolean.TRUE.equals(event.getIsBase64Encoded()) && corpo != null) {
            try {
                corpo = new String(Base64.getDecoder().decode(corpo), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return responder(
                        400, Map.of("erro", "Documento inválido"), requestId, "DOCUMENTO_INVALIDO");
            }
        }
        if (corpo != null && corpo.length() > 16384)
            return resposta(400, Map.of("erro", "Corpo invalido"));
        if (event != null && "POST /auth/funcionarios".equals(event.getRouteKey())) {
            try {
                JsonNode input = corpo == null ? null : MAPPER.readTree(corpo);
                String cpf = input != null && input.path("cpf").isTextual() ? input.path("cpf").asText() : null;
                if (!br.com.autopecas.domain.validation.CpfValidator.isValid(cpf))
                    return responder(400, Map.of("erro", "CPF invalido"), requestId, "CPF_INVALIDO");
                var token = funcionarios.autenticar(cpf);
                return token.isPresent()
                        ? responder(
                                200, Map.of("token", token.get()), requestId, "FUNCIONARIO_SUCESSO")
                        : responder(
                                401,
                                Map.of("erro", "Funcionario sem acesso"),
                                requestId,
                                "FUNCIONARIO_SEM_ACESSO");
            } catch (JsonProcessingException e) {
                return responder(400, Map.of("erro", "CPF invalido"), requestId, "CPF_INVALIDO");
            } catch (Exception e) {
                registrar(requestId, "ERRO_INTERNO", e.getClass().getSimpleName());
                return resposta(500, Map.of("erro", "Erro interno"));
            }
        }
        return processar(corpo, requestId);
    }

    APIGatewayV2HTTPResponse processar(String corpo, String requestId) {
        try {
            ResultadoAuth resultado = service.autenticar(extrairDocumento(corpo));
            return switch (resultado) {
                case ResultadoAuth.Sucesso s ->
                        responder(200, Map.of("token", s.token()), requestId, "SUCESSO");
                case ResultadoAuth.DocumentoInvalido d ->
                        responder(
                                400,
                                Map.of("erro", "Documento inválido"),
                                requestId,
                                "DOCUMENTO_INVALIDO");
                case ResultadoAuth.ClienteNaoEncontrado n ->
                        responder(
                                401,
                                Map.of("erro", "Cliente não encontrado"),
                                requestId,
                                "CLIENTE_NAO_ENCONTRADO");
                case ResultadoAuth.ClienteInativo i ->
                        responder(
                                403,
                                Map.of("erro", "Cliente sem acesso"),
                                requestId,
                                "CLIENTE_INATIVO");
            };
        } catch (Exception e) {
            registrar(requestId, "ERRO_INTERNO", e.getClass().getSimpleName());
            return resposta(500, Map.of("erro", "Erro interno"));
        }
    }

    private static String extrairDocumento(String corpo) {
        if (corpo == null || corpo.isBlank()) return null;
        try {
            JsonNode raiz = MAPPER.readTree(corpo);
            if (raiz == null || !raiz.isObject()) return null;
            JsonNode documento = raiz.path("documento");
            return documento.isTextual() ? documento.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static APIGatewayV2HTTPResponse responder(
            int status, Object corpo, String requestId, String resultado) {
        registrar(requestId, resultado, null);
        return resposta(status, corpo);
    }

    private static APIGatewayV2HTTPResponse resposta(int status, Object corpo) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(status)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(MAPPER.writeValueAsString(corpo))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Log estruturado em JSON. Nunca registra o documento nem o token. */
    private static void registrar(String requestId, String resultado, String detalhe) {
        Map<String, Object> linha = new LinkedHashMap<>();
        linha.put("evento", "autenticacao");
        linha.put("requestId", requestId);
        linha.put("resultado", resultado);
        if (detalhe != null) linha.put("detalhe", detalhe);
        try {
            System.out.println(MAPPER.writeValueAsString(linha));
        } catch (JsonProcessingException ignorada) {
            // log ? best-effort: não derruba a requisição
        }
    }
}
