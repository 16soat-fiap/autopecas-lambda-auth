package br.com.autopecas.infrastructure.persistence;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class JdbcConfigurationTest {
    @Test
    void rejeitaConfiguracaoAusente() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcClienteRepository(null, "user", "password"));
        assertThrows(IllegalArgumentException.class, () -> new JdbcClienteRepository("jdbc:postgresql://localhost/db", "", "password"));
        assertThrows(IllegalArgumentException.class, () -> new JdbcClienteRepository("jdbc:postgresql://localhost/db", "user", ""));
    }
}
