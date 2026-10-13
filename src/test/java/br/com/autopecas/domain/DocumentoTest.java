package br.com.autopecas.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocumentoTest {

    @Test
    void reconheceCpfComEPontuacao() {
        var doc = Documento.parse("529.982.247-25").orElseThrow();
        assertEquals(Documento.Tipo.CPF, doc.tipo());
        assertEquals("52998224725", doc.valor());
    }

    @Test
    void reconheceCnpjNumerico() {
        var doc = Documento.parse("11.222.333/0001-81").orElseThrow();
        assertEquals(Documento.Tipo.CNPJ, doc.tipo());
        assertEquals("11222333000181", doc.valor());
    }

    @Test
    void reconheceCnpjAlfanumericoNormalizandoMaiusculas() {
        var doc = Documento.parse("12.abc.345/01de-35").orElseThrow();
        assertEquals(Documento.Tipo.CNPJ, doc.tipo());
        assertEquals("12ABC34501DE35", doc.valor());
    }

    @Test
    void rejeitaDocumentoInvalido() {
        assertTrue(Documento.parse("123").isEmpty());
        assertTrue(Documento.parse("52998224724").isEmpty());     // CPF com DV errado
        assertTrue(Documento.parse("11222333000182").isEmpty());  // CNPJ com DV errado
        assertTrue(Documento.parse(null).isEmpty());
    }
}
