package br.com.autopecas.infrastructure.security;

import br.com.autopecas.domain.Cliente;
import br.com.autopecas.domain.Documento;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.math.BigInteger;
import java.net.URI;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Emite JWT RS256 e publica somente a chave publica em JWKS. */
public class TokenService {
    public static final String ACCESS_SCOPE = "api:access";
    private final Algorithm algoritmo;
    private final Duration validade;
    private final Clock relogio;
    private final com.auth0.jwt.interfaces.JWTVerifier verificador;
    private final String issuer;
    private final String audience;
    private final String keyId;
    private final RSAPublicKey publicKey;

    /** privateKeyBase64: DER PKCS#8 codificado em Base64, sem cabecalho PEM. */
    public TokenService(String privateKeyBase64, String issuer, String audience, Duration validade, Clock relogio) {
        if (issuer == null || !issuer.startsWith("https://") || issuer.endsWith("/")) {
            throw new IllegalArgumentException("JWT_ISSUER deve ser HTTPS sem barra final");
        }
        URI uri = URI.create(issuer);
        if (uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("JWT_ISSUER invalido");
        }
        if (audience == null || audience.isBlank()) throw new IllegalArgumentException("JWT_AUDIENCE obrigatorio");
        if (validade == null || validade.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Validade do JWT deve ser de pelo menos um segundo");
        }
        try {
            var factory = KeyFactory.getInstance("RSA");
            var privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Objects.requireNonNull(privateKeyBase64))));
            if (privateKey.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("Chave RSA deve ter pelo menos 2048 bits");
            }
            publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            keyId = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
            algoritmo = Algorithm.RSA256(publicKey, privateKey);
        } catch (Exception e) {
            // Nao inclua a chave privada na mensagem ou no log.
            throw new IllegalArgumentException("JWT_PRIVATE_KEY deve conter chave RSA PKCS#8 em Base64");
        }
        this.issuer = issuer;
        this.audience = audience;
        this.validade = validade;
        this.relogio = Objects.requireNonNull(relogio, "Relogio obrigatorio");
        this.verificador = ((JWTVerifier.BaseVerification) JWT.require(Algorithm.RSA256(publicKey, null))
            .withIssuer(issuer).withAudience(audience)
            .withClaimPresence("sub").withClaimPresence("iat").withClaimPresence("exp")
            .withClaim("scope", ACCESS_SCOPE)
            .withClaim("tipo", (claim, jwt) -> "PF".equals(claim.asString()) || "PJ".equals(claim.asString())))
            .build(relogio);
    }

    public static TokenService fromEnv() {
        return new TokenService(System.getenv("JWT_PRIVATE_KEY"), System.getenv("JWT_ISSUER"),
            System.getenv("JWT_AUDIENCE"), Duration.ofMinutes(30), Clock.systemUTC());
    }

    public String gerar(Cliente cliente, Documento.Tipo tipoDocumento) {
        if (cliente == null || cliente.id() == null || cliente.id().isBlank() || !cliente.ativo()) {
            throw new IllegalArgumentException("Cliente ativo com id obrigatorio");
        }
        Objects.requireNonNull(tipoDocumento, "Tipo de documento obrigatorio");
        Instant agora = relogio.instant();
        return JWT.create().withKeyId(keyId)
            .withIssuer(issuer).withAudience(audience).withSubject(cliente.id())
            .withClaim("scope", ACCESS_SCOPE)
            .withClaim("tipo", tipoDocumento == Documento.Tipo.CPF ? "PF" : "PJ")
            .withIssuedAt(agora).withExpiresAt(agora.plus(validade)).sign(algoritmo);
    }

    /** Verificacao local usada nos testes; em producao o gateway valida o JWT. */
    public Optional<DecodedJWT> validar(String token) {
        if (token == null) return Optional.empty();
        try {
            var jwt = verificador.verify(token);
            if (!keyId.equals(jwt.getKeyId()) || jwt.getSubject().isBlank()
                    || !jwt.getExpiresAtAsInstant().isAfter(jwt.getIssuedAtAsInstant())) return Optional.empty();
            return Optional.of(jwt);
        } catch (JWTVerificationException e) {
            return Optional.empty();
        }
    }

    public Map<String, Object> discovery() {
        return Map.of("issuer", issuer, "jwks_uri", issuer + "/.well-known/jwks.json",
            "id_token_signing_alg_values_supported", List.of("RS256"),
            "response_types_supported", List.of("token"),
            "subject_types_supported", List.of("public"));
    }

    public Map<String, Object> jwks() {
        return Map.of("keys", List.of(Map.of("kty", "RSA", "use", "sig", "alg", "RS256",
            "kid", keyId, "n", unsigned(publicKey.getModulus()), "e", unsigned(publicKey.getPublicExponent()))));
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
