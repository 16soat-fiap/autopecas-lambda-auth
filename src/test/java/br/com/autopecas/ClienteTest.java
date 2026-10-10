package br.com.autopecas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClienteTest {

    @Test
    void ativoQuandoStatusEhAtivo() {
        assertTrue(new Cliente("1", "ATIVO").isAtivo());
        assertTrue(new Cliente("1", "ativo").isAtivo());
    }

    @Test
    void naoAtivoNosDemaisStatus() {
        assertFalse(new Cliente("1", "BLOQUEADO").isAtivo());
        assertFalse(new Cliente("1", null).isAtivo());
    }
}
