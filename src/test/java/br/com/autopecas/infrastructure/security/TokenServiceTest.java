package br.com.autopecas.infrastructure.security;

import static org.junit.jupiter.api.Assertions.*;

import br.com.autopecas.domain.Cliente;
import br.com.autopecas.domain.Documento;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.*;
import java.util.*;

class TokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TokenService tokens = TestTokens.criar(CLOCK);
    private final Cliente cliente = new Cliente("id-1", true);

    @SuppressWarnings("unchecked")
    private Map<String, String> jwk() {
        return ((List<Map<String, String>>) tokens.jwks().get("keys")).getFirst();
    }

    private RSAPublicKey publicKey() throws Exception {
        return (RSAPublicKey)
                KeyFactory.getInstance("RSA")
                        .generatePublic(
                                new RSAPublicKeySpec(
                                        new BigInteger(
                                                1, Base64.getUrlDecoder().decode(jwk().get("n"))),
                                        new BigInteger(
                                                1, Base64.getUrlDecoder().decode(jwk().get("e")))));
    }

    @Test
    void verificaAssinaturaComChavePublicadaNoJwks() throws Exception {
        String token = tokens.gerar(cliente, Documento.Tipo.CPF);
        // Verificador independente usa apenas material publico, como o gateway.
        var jwt =
                JWT.require(Algorithm.RSA256(publicKey(), null))
                        .withIssuer(TestTokens.ISSUER)
                        .withAudience(TestTokens.AUDIENCE)
                        .build()
                        .verify(tokensAtual().gerar(cliente, Documento.Tipo.CPF));
        assertEquals("RS256", jwt.getAlgorithm());
        assertEquals(jwk().get("kid"), jwt.getKeyId());
        assertEquals("id-1", jwt.getSubject());
        assertEquals("PF", jwt.getClaim("tipo").asString());
        assertEquals("CLIENTE", jwt.getClaim("perfil").asString());
        assertEquals(List.of("CLIENTE"), jwt.getClaim("roles").asList(String.class));
        assertEquals("api:access", jwt.getClaim("scope").asString());
        var fixed = tokens.validar(token).orElseThrow();
        assertEquals(NOW, fixed.getIssuedAtAsInstant());
        assertEquals(NOW.plusSeconds(1800), fixed.getExpiresAtAsInstant());
        assertFalse(fixed.getClaims().containsKey("documento"));
    }

    private TokenService tokensAtual() {
        return TestTokens.criar(Clock.systemUTC());
    }

    @Test
    void publicaDiscoveryEJwksSemMaterialPrivado() {
        assertEquals(TestTokens.ISSUER, tokens.discovery().get("issuer"));
        assertEquals(
                TestTokens.ISSUER + "/.well-known/jwks.json", tokens.discovery().get("jwks_uri"));
        assertEquals(Set.of("kty", "use", "alg", "kid", "n", "e"), jwk().keySet());
        assertEquals("RSA", jwk().get("kty"));
        assertEquals("sig", jwk().get("use"));
        assertEquals("RS256", jwk().get("alg"));
        assertFalse(tokens.jwks().toString().contains(TestTokens.PRIVATE_KEY));
    }

    @Test
    void emiteTipoPj() {
        assertEquals(
                "PJ",
                tokens.validar(tokens.gerar(cliente, Documento.Tipo.CNPJ))
                        .orElseThrow()
                        .getClaim("tipo")
                        .asString());
    }

    @Test
    void rejeitaExpiracaoAssinaturaErradaETokenMalformado() {
        String token = tokens.gerar(cliente, Documento.Tipo.CPF);
        assertTrue(
                TestTokens.criar(Clock.fixed(NOW.plusSeconds(1800), ZoneOffset.UTC))
                        .validar(token)
                        .isEmpty());
        var other =
                new TokenService(
                        TestTokens.gerarChave(2048),
                        TestTokens.ISSUER,
                        TestTokens.AUDIENCE,
                        Duration.ofMinutes(30),
                        CLOCK);
        assertTrue(other.validar(token).isEmpty());
        assertTrue(tokens.validar("invalido").isEmpty());
        assertTrue(tokens.validar(null).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "issuer",
                "audience",
                "kid",
                "sub",
                "iat",
                "exp",
                "scope",
                "tipo",
                "blank",
                "future"
            })
    void rejeitaClaimsIncorretas(String caso) throws Exception {
        var builder =
                JWT.create()
                        .withClaim("perfil", "CLIENTE")
                        .withArrayClaim("roles", new String[] {"CLIENTE"})
                        .withKeyId(caso.equals("kid") ? "outro" : jwk().get("kid"))
                        .withIssuer(
                                caso.equals("issuer")
                                        ? "https://outro.example.com"
                                        : TestTokens.ISSUER)
                        .withAudience(caso.equals("audience") ? "outra-api" : TestTokens.AUDIENCE);
        if (!caso.equals("sub")) builder.withSubject(caso.equals("blank") ? " " : "id");
        if (!caso.equals("iat"))
            builder.withIssuedAt(NOW.plusSeconds(caso.equals("future") ? 60 : 0));
        if (!caso.equals("exp")) builder.withExpiresAt(NOW.plusSeconds(1800));
        if (!caso.equals("scope")) builder.withClaim("scope", TokenService.ACCESS_SCOPE);
        if (!caso.equals("tipo")) builder.withClaim("tipo", "PF");
        var key =
                (RSAPrivateKey)
                        KeyFactory.getInstance("RSA")
                                .generatePrivate(
                                        new PKCS8EncodedKeySpec(
                                                Base64.getDecoder()
                                                        .decode(TestTokens.PRIVATE_KEY)));
        assertTrue(tokens.validar(builder.sign(Algorithm.RSA256(publicKey(), key))).isEmpty());
    }

    @Test
    void rejeitaConfiguracaoEClienteInvalidos() {
        for (String invalid : List.of("", "invalid", TestTokens.gerarChave(1024))) {
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new TokenService(
                                            invalid,
                                            TestTokens.ISSUER,
                                            TestTokens.AUDIENCE,
                                            Duration.ofMinutes(30),
                                            CLOCK));
            assertFalse(error.getMessage().contains(invalid) && !invalid.isEmpty());
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TokenService(
                                null,
                                TestTokens.ISSUER,
                                TestTokens.AUDIENCE,
                                Duration.ofMinutes(30),
                                CLOCK));
        for (String issuer :
                List.of("http://auth.test", "https://auth.test/", "https://auth.test?x=1")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new TokenService(
                                    TestTokens.PRIVATE_KEY,
                                    issuer,
                                    TestTokens.AUDIENCE,
                                    Duration.ofMinutes(30),
                                    CLOCK));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TokenService(
                                TestTokens.PRIVATE_KEY,
                                TestTokens.ISSUER,
                                "",
                                Duration.ofMinutes(30),
                                CLOCK));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TokenService(
                                TestTokens.PRIVATE_KEY,
                                TestTokens.ISSUER,
                                TestTokens.AUDIENCE,
                                Duration.ZERO,
                                CLOCK));
        assertThrows(
                IllegalArgumentException.class,
                () -> tokens.gerar(new Cliente(" ", true), Documento.Tipo.CPF));
        assertThrows(
                IllegalArgumentException.class,
                () -> tokens.gerar(new Cliente("id", false), Documento.Tipo.CPF));
    }
}
