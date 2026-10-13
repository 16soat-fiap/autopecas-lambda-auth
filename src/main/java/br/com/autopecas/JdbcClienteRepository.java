package br.com.autopecas;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;

public class JdbcClienteRepository implements ClienteRepository {

    private static final String SQL_PF = """
        SELECT c.id, c.ativo
          FROM clientes c
          JOIN clientes_pf pf ON pf.id = c.id
         WHERE pf.cpf = ?
        """;

    private static final String SQL_PJ = """
        SELECT c.id, c.ativo
          FROM clientes c
          JOIN clientes_pj pj ON pj.id = c.id
         WHERE pj.cnpj = ?
        """;

    private final String url;
    private final String user;
    private final String password;

    public JdbcClienteRepository(String url, String user, String password) {
        if (url == null || !url.startsWith("jdbc:postgresql://") || user == null || user.isBlank()
                || password == null || password.isBlank()) {
            throw new IllegalArgumentException("DB_URL PostgreSQL, DB_USER e DB_PASSWORD são obrigatórios");
        }
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
    public Optional<Cliente> buscarPorDocumento(Documento documento) {
        String sql = switch (documento.tipo()) {
            case CPF -> SQL_PF;
            case CNPJ -> SQL_PJ;
        };

        Properties propriedades = new Properties();
        propriedades.setProperty("user", user);
        propriedades.setProperty("password", password);
        propriedades.setProperty("connectTimeout", "5");
        propriedades.setProperty("socketTimeout", "10");
        try (Connection con = DriverManager.getConnection(url, propriedades);
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setQueryTimeout(5);
            ps.setString(1, documento.valor());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Cliente(rs.getString("id"), rs.getBoolean("ativo")));
            }
        } catch (SQLException e) {
            // Não inclua o documento na mensagem: ? dado pessoal e iria para o log.
            throw new IllegalStateException("Falha ao consultar cliente no banco", e);
        }
    }
}
