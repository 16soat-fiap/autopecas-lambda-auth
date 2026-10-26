package br.com.autopecas.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;


import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.*;

@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class JdbcFuncionarioRepositoryTest {
    private static final String ID = "00000000-0000-0000-0000-000000000010";

    @BeforeEach
    void seed() throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                System.getenv("DB_URL"),
                                System.getenv("DB_USER"),
                                System.getenv("DB_PASSWORD"));
                var s = c.createStatement()) {
            s.execute(
                    "INSERT INTO funcionarios VALUES ('"
                            + ID
                            + "','529.982.247-25',true) ON CONFLICT(id) DO UPDATE SET ativo=true");
            s.execute(
                    "INSERT INTO funcionario_roles VALUES ('"
                            + ID
                            + "','MECANICO') ON CONFLICT DO NOTHING");
        }
    }

    @Test
    void encontraCredencialComRoles() {
        var credential =
                JdbcFuncionarioRepository.fromEnv()
                        .buscarPorCpf("52998224725")
                        .orElseThrow();
        assertEquals(ID, credential.id());
        assertTrue(credential.ativo());
        assertEquals(Set.of("MECANICO"), credential.roles());
    }

    @Test
    void naoExisteESqlInjectionNaoEncontramUsuario() {
        var r = JdbcFuncionarioRepository.fromEnv();
        assertTrue(r.buscarPorCpf("11144477735").isEmpty());
        assertTrue(r.buscarPorCpf("' OR 1=1 --").isEmpty());
    }

    @Test
    void funcionarioInativoImpedeAcesso() throws Exception {
        for (String table : List.of("funcionarios")) {
            String key = table.equals("funcionarios") ? "id" : "funcionario_id";
            try (var c =
                            DriverManager.getConnection(
                                    System.getenv("DB_URL"),
                                    System.getenv("DB_USER"),
                                    System.getenv("DB_PASSWORD"));
                    var s = c.createStatement()) {
                s.execute("UPDATE " + table + " SET ativo=false WHERE " + key + "='" + ID + "'");
                assertFalse(
                        JdbcFuncionarioRepository.fromEnv()
                                .buscarPorCpf("52998224725")
                                .orElseThrow()
                                .ativo());
                s.execute("UPDATE " + table + " SET ativo=true WHERE " + key + "='" + ID + "'");
            }
        }
    }
    @Test
    void cpfDuplicadoComMascarasDiferentesNaoAutentica() throws Exception {
        try (var c = DriverManager.getConnection(System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD")); var s = c.createStatement()) {
            try {
                s.execute("INSERT INTO funcionarios VALUES ('00000000-0000-0000-0000-000000000099', '52998224725', true)");
                assertTrue(JdbcFuncionarioRepository.fromEnv().buscarPorCpf("52998224725").isEmpty());
            } finally {
                s.execute("DELETE FROM funcionarios WHERE id='00000000-0000-0000-0000-000000000099'");
            }
        }
    }
}
