package br.com.autopecas;

public record Cliente(String id, String status) {

    public boolean isAtivo() {
        return "ATIVO".equalsIgnoreCase(status);
    }
}
