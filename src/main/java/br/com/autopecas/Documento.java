package br.com.autopecas;

import java.util.Optional;

/** Documento de identificação do cliente, já validado e normalizado. */
public record Documento(Tipo tipo, String valor) {

    public enum Tipo { CPF, CNPJ }

    /** Devolve vazio se o texto não for um CPF nem um CNPJ válido. */
    public static Optional<Documento> parse(String bruto) {
        if (CpfValidator.isValid(bruto)) {
            return Optional.of(new Documento(Tipo.CPF, CpfValidator.normalize(bruto)));
        }
        if (CnpjValidator.isValid(bruto)) {
            return Optional.of(new Documento(Tipo.CNPJ, CnpjValidator.normalize(bruto)));
        }
        return Optional.empty();
    }
}
