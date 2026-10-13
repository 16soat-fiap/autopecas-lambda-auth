package br.com.autopecas.infrastructure.security;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/** Chaves efemeras, geradas somente em testes. */
public final class TestTokens {
    public static final String ISSUER = "https://auth.example.com/homologacao";
    public static final String AUDIENCE = "autopecas-api";
    public static final String PRIVATE_KEY = gerarChave(2048);
    private TestTokens() {}

    public static String gerarChave(int bits) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static TokenService criar(Clock clock) {
        return new TokenService(PRIVATE_KEY, ISSUER, AUDIENCE, Duration.ofMinutes(30), clock);
    }
}
