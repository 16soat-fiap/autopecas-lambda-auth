package br.com.autopecas.domain;

/** Visão mínima do cliente para autenticação: só o que a Lambda precisa. */
public record Cliente(String id, boolean ativo) {}
