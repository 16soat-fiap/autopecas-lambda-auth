package br.com.autopecas.migration;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.sql.*;
import java.util.*;

/** Temporary synthetic fixtures, invoked only through IAM inside the private VPC. */
public final class ValidationFixtureHandler implements RequestHandler<Map<String,Object>, Map<String,Object>> {
    public Map<String,Object> handleRequest(Map<String,Object> event, Context context) {
        try (var connection = DriverManager.getConnection(System.getenv("DB_URL"),
                System.getenv("MIGRATION_DB_USER"), System.getenv("MIGRATION_DB_PASSWORD"))) {
            String run = UUID.fromString((String)event.get("run")).toString();
            String marker = "validacao-aws:" + run;
            connection.setAutoCommit(false);
            if ("cleanup".equals(event.get("action"))) {
                try (var delete = connection.prepareStatement("DELETE FROM clientes_pf WHERE id IN (SELECT id FROM clientes WHERE nome=?)")) {
                    delete.setString(1, marker); delete.executeUpdate();
                }
                int removed = 0;
                for (String table : List.of("clientes", "funcionarios")) {
                    try (var delete = connection.prepareStatement("DELETE FROM " + table + " WHERE nome=?")) {
                        delete.setString(1, marker); removed += delete.executeUpdate();
                    }
                }
                connection.commit();
                return Map.of("ok", true, "removed", removed);
            }
            if (!"seed".equals(event.get("action"))) throw new IllegalArgumentException();
            var identities = new LinkedHashMap<String,Object>();
            var random = new java.security.SecureRandom();
            for (String kind : List.of("staff_admin", "staff_atendente", "staff_mecanico", "staff_inactive", "staff_no_roles", "client_active", "client_inactive")) {
                String cpf;
                while (true) {
                    String digits = String.format(Locale.ROOT, "%09d", random.nextInt(1000000000));
                    cpf = digits + check(digits);
                    cpf += check(cpf);
                    if (cpf.chars().distinct().count() == 1) continue;
                    try (var query = connection.prepareStatement("SELECT 1 FROM funcionarios WHERE translate(cpf,'.- ','')=? UNION ALL SELECT 1 FROM clientes_pf WHERE translate(cpf,'.- ','')=?")) {
                        query.setString(1,cpf); query.setString(2,cpf);
                        try (var rows = query.executeQuery()) { if (!rows.next()) break; }
                    }
                }
                UUID id = UUID.randomUUID();
                boolean active = !kind.endsWith("inactive");
                List<String> roles = switch (kind) {
                    case "staff_admin", "staff_inactive" -> List.of("ADMIN");
                    case "staff_atendente" -> List.of("ATENDENTE");
                    case "staff_mecanico" -> List.of("MECANICO");
                    default -> List.of();
                };
                if (kind.startsWith("staff")) {
                    try (var insert = connection.prepareStatement("INSERT INTO funcionarios(id,tipo_funcionario,matricula,cpf,nome,ativo,created_at,updated_at) VALUES (?,?,?,?,?,?,now(),now())")) {
                        insert.setObject(1,id); insert.setString(2,kind.equals("staff_mecanico") ? "MECANICO" : "ATENDENTE");
                        insert.setString(3,id.toString().replace("-","").substring(0,20));
                        insert.setString(4,cpf); insert.setString(5,marker); insert.setBoolean(6,active); insert.executeUpdate();
                    }
                    for (String role : roles) {
                        try (var insert = connection.prepareStatement("INSERT INTO funcionario_roles(funcionario_id,role) VALUES (?,?)")) {
                            insert.setObject(1,id); insert.setString(2,role); insert.executeUpdate();
                        }
                    }
                } else {
                    try (var insert = connection.prepareStatement("INSERT INTO clientes(id,tipo_cliente,nome,ativo,created_at,update_at) VALUES (?,'PF',?,?,now(),now())")) {
                        insert.setObject(1,id); insert.setString(2,marker); insert.setBoolean(3,active); insert.executeUpdate();
                    }
                    try (var insert = connection.prepareStatement("INSERT INTO clientes_pf(id,cpf) VALUES (?,?)")) {
                        insert.setObject(1,id); insert.setString(2,cpf); insert.executeUpdate();
                    }
                }
                identities.put(kind, Map.of("id",id.toString(),"cpf",cpf,"roles",roles));
            }
            connection.commit();
            return Map.of("ok",true,"identities",identities);
        } catch (Exception exception) {
            return Map.of("ok",false,"errorType",exception.getClass().getSimpleName());
        }
    }
    private static int check(String digits) {
        int sum=0;
        for (int i=0;i<digits.length();i++) sum+=(digits.charAt(i)-'0')*(digits.length()+1-i);
        int remainder=(sum*10)%11;
        return remainder==10 ? 0 : remainder;
    }
}
