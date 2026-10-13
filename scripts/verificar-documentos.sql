-- Somente leitura. Execute no banco da aplicacao principal.
-- Exibe contagens, sem expor os documentos completos.
SELECT count(*) AS total,
       count(*) FILTER (WHERE cpf ~ '^[0-9]{11}$') AS normalizados
FROM clientes_pf;

SELECT count(*) AS total,
       count(*) FILTER (WHERE cnpj ~ '^[0-9A-Z]{12}[0-9]{2}$') AS normalizados
FROM clientes_pj;
