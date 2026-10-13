package br.com.autopecas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CnpjValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "11222333000181", "11.222.333/0001-81",
        "11444777000161",
        "12ABC34501DE35", "12.ABC.345/01DE-35",
        "12abc34501de35"                        // minúsculas são aceitas
    })
    void aceitaCnpjValido(String cnpj) {
        assertTrue(CnpjValidator.isValid(cnpj));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "1122233300018",       // curto demais
        "112223330001811",     // longo demais
        "11222333000182",      // dígito verificador errado
        "00000000000000",      // todos iguais
        "11111111111111",
        "12ABC34501DE36",      // dígito verificador errado (alfanumérico)
        "12ABC34501DEAB",      // dígitos verificadores precisam ser números
        "12ABC34501D!35"       // caractere inválido
    })
    void rejeitaCnpjInvalido(String cnpj) {
        assertFalse(CnpjValidator.isValid(cnpj));
    }

    @Test
    void normalizaRemovendoPontuacaoEPadronizandoMaiusculas() {
        assertEquals("12ABC34501DE35", CnpjValidator.normalize("12.abc.345/01de-35"));
        assertEquals("11222333000181", CnpjValidator.normalize("11.222.333/0001-81"));
        assertEquals("", CnpjValidator.normalize(null));
    }
}
