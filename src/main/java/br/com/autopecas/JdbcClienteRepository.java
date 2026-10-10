package br.com.autopecas;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class JdbcClienteRepository implements ClienteRepository {

    private static final String SQL = "SELECT id, status FROM clientes WHERE cpf = ?";

    private final String url;
    private final String user;
    private final String password;

    public JdbcClienteRepository(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** Lê DB_URL, DB_USER e DB_PASSWORD das variáveis de ambiente (como na Lambda). */
    public static JdbcClienteRepository fromEnv() {
        return new JdbcClienteRepository(
            System.getenv("DB_URL"),
            System.getenv("DB_USER"),
            System.getenv("DB_PASSWORD"));
    }

    @Override
    public Optional<Cliente> buscarPorCpf(String cpf) {
        try (Connection con = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = con.prepareStatement(SQL)) {

            ps.setString(1, cpf);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Cliente(rs.getString("id"), rs.getString("status")));
            }
        } catch (SQLException e) {
            // Não inclua o CPF na mensagem: é dado pessoal e iria para o log.
            throw new IllegalStateException("Falha ao consultar cliente no banco", e);
        }
    }
}
