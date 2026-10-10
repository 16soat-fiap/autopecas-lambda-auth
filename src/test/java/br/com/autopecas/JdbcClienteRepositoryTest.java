package br.com.autopecas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class JdbcClienteRepositoryTest {

    private final ClienteRepository repo = JdbcClienteRepository.fromEnv();

    @Test
    void encontraClienteAtivo() {
        var cliente = repo.buscarPorCpf("52998224725");
        assertTrue(cliente.isPresent());
        assertTrue(cliente.get().isAtivo());
    }

    @Test
    void encontraClienteBloqueado() {
        var cliente = repo.buscarPorCpf("11144477735");
        assertTrue(cliente.isPresent());
        assertFalse(cliente.get().isAtivo());
    }

    @Test
    void retornaVazioQuandoNaoExiste() {
        assertTrue(repo.buscarPorCpf("39053344705").isEmpty());
    }
}
