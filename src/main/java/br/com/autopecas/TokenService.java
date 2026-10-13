package br.com.autopecas;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Objects;
import com.auth0.jwt.JWTVerifier;

/** Gera e valida os JWTs (HS256). O mesmo segredo e issuer valem para a Lambda e o authorizer. */
public class TokenService {

    public static final String ISSUER = "autopecas-auth";
    private static final int TAMANHO_MINIMO_SEGREDO = 32;

    private final Algorithm algoritmo;
    private final Duration validade;
    private final Clock relogio;
    private final com.auth0.jwt.interfaces.JWTVerifier verificador;

    public TokenService(String segredo, Duration validade, Clock relogio) {
        if (segredo == null || segredo.isBlank() || segredo.length() < TAMANHO_MINIMO_SEGREDO) {
            throw new IllegalArgumentException(
                "JWT_SECRET deve ter ao menos " + TAMANHO_MINIMO_SEGREDO + " caracteres");
        }
        if (validade == null || validade.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Validade do JWT deve ser de pelo menos um segundo");
        }
        this.algoritmo = Algorithm.HMAC256(segredo);
        this.validade = validade;
        this.relogio = Objects.requireNonNull(relogio, "Relógio obrigatório");
        this.verificador = ((JWTVerifier.BaseVerification) JWT.require(algoritmo)
            .withIssuer(ISSUER)
            .withClaimPresence("sub")
            .withClaimPresence("iat")
            .withClaimPresence("exp")
            .withClaim("tipo", (claim, jwt) -> "PF".equals(claim.asString()) || "PJ".equals(claim.asString())))
            .build(relogio);
    }

    /** O subject ? o id do cliente; o documento (dado pessoal) nunca vai dentro do token. */
    public String gerar(Cliente cliente, Documento.Tipo tipoDocumento) {
        if (cliente == null || cliente.id() == null || cliente.id().isBlank() || !cliente.ativo()) {
            throw new IllegalArgumentException("Cliente ativo com id obrigatório");
        }
        Objects.requireNonNull(tipoDocumento, "Tipo de documento obrigatório");
        Instant agora = relogio.instant();
        return JWT.create()
            .withIssuer(ISSUER)
            .withSubject(cliente.id())
            .withClaim("tipo", tipoDocumento == Documento.Tipo.CPF ? "PF" : "PJ")
            .withIssuedAt(Date.from(agora))
            .withExpiresAt(Date.from(agora.plus(validade)))
            .sign(algoritmo);
    }

    /** Confere assinatura, issuer e expiração. Vazio se o token for inválido. */
    public Optional<DecodedJWT> validar(String token) {
        if (token == null) return Optional.empty();
        try {
            DecodedJWT jwt = verificador.verify(token);
            if (jwt.getSubject().isBlank() || !jwt.getExpiresAtAsInstant().isAfter(jwt.getIssuedAtAsInstant())) {
                return Optional.empty();
            }
            return Optional.of(jwt);
        } catch (JWTVerificationException e) {
            return Optional.empty();
        }
    }
}
