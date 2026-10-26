package br.com.autopecas.tools;

import br.com.autopecas.infrastructure.security.TokenService;

import java.sql.*;
import java.util.*;

/** Comando administrativo local; nunca exposto pelo handler Lambda. */
public final class ProvisionarFuncionario {
    public static void main(String[] args) {
        try {
            if (args.length != 2)
                throw new IllegalArgumentException(
                        "Use: <UUID funcionario> <roles separadas"
                            + " por virgula>");
            UUID id = UUID.fromString(args[0]);
            Set<String> roles = Set.of(args[1].split(","));
            if (roles.isEmpty() || !TokenService.STAFF_ROLES.containsAll(roles))
                throw new IllegalArgumentException("Roles invalidos");
            try (var con =
                    DriverManager.getConnection(
                            required("DB_URL"),
                            required("ADMIN_DB_USER"),
                            required("ADMIN_DB_PASSWORD"))) {
                con.setAutoCommit(false);
                try {
                    try (var ps =
                            con.prepareStatement(
                                    "SELECT ativo FROM funcionarios WHERE id=? FOR UPDATE")) {
                        ps.setObject(1, id);
                        try (var rs = ps.executeQuery()) {
                            if (!rs.next() || !rs.getBoolean(1))
                                throw new IllegalArgumentException(
                                        "Funcionario ativo nao encontrado");
                        }
                    }
                    try (var ps =
                            con.prepareStatement(
                                    "DELETE FROM funcionario_roles WHERE funcionario_id=?")) {
                        ps.setObject(1, id);
                        ps.executeUpdate();
                    }
                    try (var ps =
                            con.prepareStatement(
                                    "INSERT INTO funcionario_roles(funcionario_id,role) VALUES"
                                        + " (?,?)")) {
                        for (String role : roles) {
                            ps.setObject(1, id);
                            ps.setString(2, role);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    con.commit();
                } catch (Exception e) {
                    con.rollback();
                    throw e;
                }
            }
            System.out.println("Permissoes gravadas para funcionario ativo.");
        } catch (Exception e) {
            System.err.println(
                    "Provisionamento nao concluido: "
                            + e.getClass().getSimpleName()
                            + ". Confira argumentos, banco e permissoes.");
            System.exit(1);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Configuracao ausente: " + name);
        return value;
    }
}
