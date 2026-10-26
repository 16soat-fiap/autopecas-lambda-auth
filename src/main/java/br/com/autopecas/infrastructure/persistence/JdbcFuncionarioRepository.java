package br.com.autopecas.infrastructure.persistence;

import br.com.autopecas.domain.*;

import java.sql.*;
import java.util.*;

public class JdbcFuncionarioRepository implements FuncionarioRepository {
    private final String url, user, password;

    public JdbcFuncionarioRepository(String url, String user, String password) {
        if (url == null
                || !url.startsWith("jdbc:postgresql://")
                || user == null
                || user.isBlank()
                || password == null
                || password.isBlank())
            throw new IllegalArgumentException("Configuracao JDBC obrigatoria");
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public static JdbcFuncionarioRepository fromEnv() {
        return new JdbcFuncionarioRepository(
                System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"));
    }

    @Override
    public Optional<FuncionarioAcesso> buscarPorCpf(String cpf) {
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("connectTimeout", "5");
        props.setProperty("socketTimeout", "10");
        String sql =
                "SELECT f.id, f.ativo, r.role FROM funcionarios f"
                        + " LEFT JOIN funcionario_roles r ON r.funcionario_id=f.id"
                        + " WHERE translate(f.cpf, '.- ', '')=?";
        try (var con = DriverManager.getConnection(url, props);
                var ps = con.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            ps.setString(1, cpf);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String id = rs.getString("id");
                boolean ativo = rs.getBoolean("ativo");
                Set<String> roles = new HashSet<>();
                do {
                    if (!id.equals(rs.getString("id"))) return Optional.empty();
                    String role = rs.getString("role");
                    if (role != null) roles.add(role);
                } while (rs.next());
                return Optional.of(new FuncionarioAcesso(id, ativo, roles));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar acesso de funcionario");
        }
    }
}
