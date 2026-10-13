-- Fixture exclusiva de testes, baseada nas entidades JOINED do historico.
-- Nao substitui as migrations da aplicacao principal.
BEGIN;

CREATE TABLE clientes (
    id UUID PRIMARY KEY,
    tipo_cliente VARCHAR(2) NOT NULL,
    nome VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE,
    telefone VARCHAR(255),
    aceita_notificacoes BOOLEAN NOT NULL,
    ativo BOOLEAN NOT NULL,
    endereco_cep VARCHAR(255),
    endereco_logradouro VARCHAR(255),
    endereco_numero VARCHAR(255),
    endereco_complemento VARCHAR(255),
    endereco_bairro VARCHAR(255),
    endereco_cidade VARCHAR(255),
    endereco_uf VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    update_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE clientes_pf (
    id UUID PRIMARY KEY REFERENCES clientes(id),
    cpf VARCHAR(14) NOT NULL UNIQUE,
    data_nascimento DATE,
    rg VARCHAR(255),
    genero VARCHAR(255),
    profissao VARCHAR(255)
);

CREATE TABLE clientes_pj (
    id UUID PRIMARY KEY REFERENCES clientes(id),
    cnpj VARCHAR(18) NOT NULL UNIQUE,
    razao_social VARCHAR(255) NOT NULL,
    inscricao_estadual VARCHAR(255),
    inscricao_municipal VARCHAR(255),
    contato_responsavel VARCHAR(255)
);

INSERT INTO clientes (id, tipo_cliente, nome, aceita_notificacoes, ativo) VALUES
('11111111-1111-1111-1111-111111111111', 'PF', 'PF Ativo', true, true),
('22222222-2222-2222-2222-222222222222', 'PF', 'PF Inativo', true, false),
('33333333-3333-3333-3333-333333333333', 'PJ', 'PJ Ativa', true, true),
('44444444-4444-4444-4444-444444444444', 'PJ', 'PJ Inativa', true, false),
('55555555-5555-5555-5555-555555555555', 'PJ', 'PJ Alfanumerica', true, true);

INSERT INTO clientes_pf (id, cpf) VALUES
('11111111-1111-1111-1111-111111111111', '52998224725'),
('22222222-2222-2222-2222-222222222222', '11144477735');

INSERT INTO clientes_pj (id, cnpj, razao_social) VALUES
('33333333-3333-3333-3333-333333333333', '11222333000181', 'PJ Ativa Ltda'),
('44444444-4444-4444-4444-444444444444', '11444777000161', 'PJ Inativa Ltda'),
('55555555-5555-5555-5555-555555555555', '12ABC34501DE35', 'PJ Alfanumerica Ltda');

COMMIT;
