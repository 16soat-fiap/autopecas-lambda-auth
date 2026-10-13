package br.com.autopecas.handler;

import br.com.autopecas.infrastructure.security.TokenService;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2CustomAuthorizerEvent;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/** HTTP API payload 2.0, com enableSimpleResponses=true e cache desativado. */
public class AuthorizerHandler implements RequestHandler<APIGatewayV2CustomAuthorizerEvent, Map<String, Object>> {
    private static final Pattern BEARER = Pattern.compile("(?i)^Bearer +([^\\s,]+)$");
    private final TokenService tokens;

    public AuthorizerHandler() {
        this(new TokenService(System.getenv("JWT_SECRET"), Duration.ofMinutes(30), Clock.systemUTC()));
    }

    AuthorizerHandler(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public Map<String, Object> handleRequest(APIGatewayV2CustomAuthorizerEvent event, Context context) {
        if (event == null || !"2.0".equals(event.getVersion()) || event.getHeaders() == null) return negar();
        var headers = event.getHeaders().entrySet().stream()
            .filter(e -> "authorization".equalsIgnoreCase(e.getKey())).toList();
        if (headers.size() != 1 || headers.getFirst().getValue() == null) return negar();
        var matcher = BEARER.matcher(headers.getFirst().getValue());
        if (!matcher.matches()) return negar();
        return tokens.validar(matcher.group(1))
            .<Map<String, Object>>map(jwt -> Map.of(
                "isAuthorized", true,
                "context", Map.of("clienteId", jwt.getSubject(), "tipo", jwt.getClaim("tipo").asString())))
            .orElseGet(AuthorizerHandler::negar);
    }

    private static Map<String, Object> negar() {
        return Map.of("isAuthorized", false, "context", Map.of());
    }
}
