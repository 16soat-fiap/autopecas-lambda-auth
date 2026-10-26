package br.com.autopecas.migration;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.sql.DriverManager;
import java.util.Map;
import org.flywaydb.core.Flyway;

/** Administrative artifact; never included in the public authentication Lambda. */
public final class MigrationHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            String url = required("DB_URL");
            String user = required("MIGRATION_DB_USER");
            String password = required("MIGRATION_DB_PASSWORD");
            var flyway = Flyway.configure().dataSource(url, user, password)
                    .locations("classpath:db/migration").cleanDisabled(true)
                    .baselineOnMigrate(false).validateOnMigrate(true).load();
            var result = flyway.migrate();
            try (var connection = DriverManager.getConnection(url, user, password);
                    var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("GRANT USAGE ON SCHEMA public TO lambda_auth");
                statement.execute("GRANT SELECT (id, ativo) ON public.clientes TO lambda_auth");
                statement.execute("GRANT SELECT (id, cpf) ON public.clientes_pf TO lambda_auth");
                statement.execute("GRANT SELECT (id, cnpj) ON public.clientes_pj TO lambda_auth");
                statement.execute("GRANT SELECT (id, cpf, ativo) ON public.funcionarios TO lambda_auth");
                statement.execute("GRANT SELECT (funcionario_id, role) ON public.funcionario_roles TO lambda_auth");
                // SET ROLE checks the effective runtime permissions without disclosing credentials.
                statement.execute("SET LOCAL ROLE lambda_auth");
                statement.executeQuery("SELECT funcionario_id, role FROM public.funcionario_roles LIMIT 0").close();
                statement.executeQuery("SELECT id, cpf, ativo FROM public.funcionarios LIMIT 0").close();
                try (var privileges = statement.executeQuery(
                        "SELECT bool_or(has_table_privilege('lambda_auth', c.oid,"
                                + " 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))"
                                + " FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                                + " WHERE n.nspname='public' AND c.relkind IN ('r','p')")) {
                    if (privileges.next() && privileges.getBoolean(1))
                        throw new IllegalStateException("Runtime role has write privileges");
                }
                statement.execute("SAVEPOINT denied_write");
                boolean denied = false;
                try {
                    statement.executeUpdate("DELETE FROM public.funcionario_roles WHERE false");
                } catch (java.sql.SQLException exception) {
                    if (!"42501".equals(exception.getSQLState())) throw exception;
                    denied = true;
                    statement.execute("ROLLBACK TO SAVEPOINT denied_write");
                }
                if (!denied) throw new IllegalStateException("Runtime role has write privileges");
                statement.execute("RESET ROLE");
                connection.commit();
            }
            return Map.of("ok", true, "migrationsExecuted", result.migrationsExecuted,
                    "version", flyway.info().current().getVersion().toString(), "readOnlyVerified", true);
        } catch (Exception exception) {
            // JDBC and Flyway exceptions can contain sensitive connection information.
            return Map.of("ok", false, "errorType", exception.getClass().getSimpleName());
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }

    public static void main(String[] args) {
        var result = new MigrationHandler().handleRequest(Map.of(), null);
        System.out.println(result);
        if (!Boolean.TRUE.equals(result.get("ok"))) System.exit(1);
    }
}
