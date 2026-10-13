package br.com.autopecas;

import java.util.Locale;

/** Valida CNPJ numérico e alfanumérico (módulo 11, valor = código ASCII - 48). */
public final class CnpjValidator {

    private static final String FORMATO =
        "[0-9A-Z]{2}\\.?[0-9A-Z]{3}\\.?[0-9A-Z]{3}/?[0-9A-Z]{4}-?\\d{2}";

    private static final int[] PESOS_DV1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] PESOS_DV2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private CnpjValidator() {}

    /** Remove pontuação e padroniza em maiúsculas: "12.abc.345/01de-35" -> "12ABC34501DE35". */
    public static String normalize(String cnpj) {
        return cnpj == null ? "" : cnpj.trim().toUpperCase(Locale.ROOT).replaceAll("[./-]", "");
    }

    public static boolean isValid(String cnpj) {
        if (cnpj == null) return false;

        String entrada = cnpj.trim().toUpperCase(Locale.ROOT);
        if (!entrada.matches(FORMATO)) return false;

        String d = normalize(entrada);
        if (d.chars().distinct().count() == 1) return false; // 00000000000000 etc.

        return digito(d, PESOS_DV1) == d.charAt(12) - '0'
            && digito(d, PESOS_DV2) == d.charAt(13) - '0';
    }

    private static int digito(String d, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < pesos.length; i++) {
            soma += (d.charAt(i) - 48) * pesos[i];
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }
}
