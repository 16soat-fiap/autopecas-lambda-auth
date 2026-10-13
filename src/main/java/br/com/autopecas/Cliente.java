package br.com.autopecas;

/** Visão mínima do cliente para autenticação: só o que a Lambda precisa. */
public record Cliente(String id, boolean ativo) {}
