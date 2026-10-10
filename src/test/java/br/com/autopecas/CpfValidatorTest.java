package br.com.autopecas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CpfValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "52998224725", "529.982.247-25",
        "11144477735", "39053344705"
    })
    void aceitaCpfValido(String cpf) {
        assertTrue(CpfValidator.isValid(cpf));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "123",                 // curto demais
        "529982247250",        // longo demais
        "52998224724",         // dígito verificador errado
        "11111111111",         // todos iguais
        "00000000000",
        "5299822472a5",        // letra no meio
        "529 982 247 25"       // formato não aceito
    })
    void rejeitaCpfInvalido(String cpf) {
        assertFalse(CpfValidator.isValid(cpf));
    }

    @Test
    void normalizaRemovendoPontuacao() {
        assertEquals("52998224725", CpfValidator.normalize("529.982.247-25"));
        assertEquals("", CpfValidator.normalize(null));
    }
}
