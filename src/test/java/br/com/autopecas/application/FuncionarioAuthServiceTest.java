package br.com.autopecas.application;

import static org.junit.jupiter.api.Assertions.*;
import br.com.autopecas.domain.*;
import br.com.autopecas.infrastructure.security.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;

class FuncionarioAuthServiceTest {
    private static final String ID = "00000000-0000-0000-0000-000000000010";
    private final TokenService tokens = TestTokens.criar(Clock.systemUTC());

    @Test
    void autenticaPorCpfNormalizadoESemEscalarPrivilegios() {
        var service = new FuncionarioAuthService(cpf -> {
            assertEquals("52998224725", cpf);
            return Optional.of(new FuncionarioAcesso(ID, true, Set.of("MECANICO")));
        }, tokens);
        for (String cpf : List.of("52998224725", "529.982.247-25")) {
            var jwt = tokens.validar(service.autenticar(cpf).orElseThrow()).orElseThrow();
            assertEquals(ID, jwt.getSubject());
            assertEquals("FUNCIONARIO", jwt.getClaim("perfil").asString());
            assertEquals(List.of("MECANICO"), jwt.getClaim("roles").asList(String.class));
            assertTrue(jwt.getClaim("tipo").isMissing());
            assertTrue(jwt.getClaim("cpf").isMissing());
        }
    }

    @Test
    void rejeitaAusenteInativoSemRolesERoleDesconhecida() {
        assertTrue(new FuncionarioAuthService(e -> Optional.empty(), tokens).autenticar("52998224725").isEmpty());
        for (var identity : List.of(
                new FuncionarioAcesso(ID, false, Set.of("ADMIN")),
                new FuncionarioAcesso(ID, true, Set.of()),
                new FuncionarioAcesso(ID, true, Set.of("ROOT")))) {
            assertTrue(new FuncionarioAuthService(e -> Optional.of(identity), tokens).autenticar("52998224725").isEmpty());
        }
    }

    @Test
    void entradaInvalidaNaoConsultaRepositorio() {
        var service = new FuncionarioAuthService(e -> {throw new AssertionError();}, tokens);
        for (String cpf : Arrays.asList(null, "", "11111111111", "52998224724", "11222333000181", "a@b", "52998224725 OR 1=1")) {
            assertTrue(service.autenticar(cpf).isEmpty());
        }
    }
}
