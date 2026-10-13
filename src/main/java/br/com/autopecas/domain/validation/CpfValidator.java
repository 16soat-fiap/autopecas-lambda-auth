package br.com.autopecas.domain.validation;

public final class CpfValidator {

    private static final String FORMATO = "\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}";

    private CpfValidator() {}

    /** Remove pontos e traço: "529.982.247-25" -> "52998224725". */
    public static String normalize(String cpf) {
        return cpf == null ? "" : cpf.replaceAll("\\D", "");
    }

    public static boolean isValid(String cpf) {
        if (cpf == null || !cpf.matches(FORMATO)) return false;

        String d = normalize(cpf);
        if (d.chars().distinct().count() == 1) return false; // 111.111.111-11 etc.

        return digito(d, 9) == d.charAt(9) - '0'
            && digito(d, 10) == d.charAt(10) - '0';
    }

    private static int digito(String d, int tamanho) {
        int soma = 0;
        for (int i = 0; i < tamanho; i++) {
            soma += (d.charAt(i) - '0') * (tamanho + 1 - i);
        }
        int resto = (soma * 10) % 11;
        return resto == 10 ? 0 : resto;
    }
}
