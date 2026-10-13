package br.com.autopecas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class JdbcClienteRepositoryTest {

    private final ClienteRepository repo = JdbcClienteRepository.fromEnv();

    private Optional<Cliente> buscar(String documento) {
        return repo.buscarPorDocumento(Documento.parse(documento).orElseThrow());
    }

    @Test
    void encontraPfAtivoPorCpf() {
        var cliente = buscar("52998224725").orElseThrow();
        assertEquals("11111111-1111-1111-1111-111111111111", cliente.id());
        assertTrue(cliente.ativo());
    }

    @Test
    void encontraPfInativoPorCpf() {
        var cliente = buscar("11144477735").orElseThrow();
        assertFalse(cliente.ativo());
    }

    @Test
    void encontraPjAtivaPorCnpj() {
        var cliente = buscar("11222333000181").orElseThrow();
        assertEquals("33333333-3333-3333-3333-333333333333", cliente.id());
        assertTrue(cliente.ativo());
    }

    @Test
    void encontraPjInativaPorCnpj() {
        assertFalse(buscar("11444777000161").orElseThrow().ativo());
    }

    @Test
    void encontraPjPorCnpjAlfanumerico() {
        var cliente = buscar("12.ABC.345/01DE-35").orElseThrow();
        assertEquals("55555555-5555-5555-5555-555555555555", cliente.id());
    }

    @Test
    void retornaVazioQuandoNaoExiste() {
        assertTrue(buscar("39053344705").isEmpty());       // CPF válido, não cadastrado
        assertTrue(buscar("11111111000191").isEmpty());    // CNPJ válido, não cadastrado
    }
}
