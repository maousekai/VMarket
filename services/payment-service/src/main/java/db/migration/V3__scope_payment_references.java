package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;

public class V3__scope_payment_references extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        var names = new ArrayList<String>();
        try (var select = connection.createStatement();
                var rows =
                        select.executeQuery(
                                """
            select tc.constraint_name from information_schema.table_constraints tc
            join information_schema.key_column_usage k on tc.constraint_name=k.constraint_name
                and tc.constraint_schema=k.constraint_schema and tc.table_name=k.table_name
            where lower(tc.table_name)='payment_transactions' and tc.table_schema=current_schema() and tc.constraint_type='UNIQUE'
            group by tc.constraint_name having count(*)=1 and max(lower(k.column_name))='reference'
            """)) {
            while (rows.next()) names.add(rows.getString(1));
        }
        try (var ddl = connection.createStatement()) {
            for (String name : names)
                ddl.execute(
                        "alter table payment_transactions drop constraint \""
                                + name.replace("\"", "\"\"")
                                + "\"");
            ddl.execute(
                    "alter table payment_transactions add constraint uk_payment_method_reference"
                            + " unique(method, reference)");
        }
        var mapper = new ObjectMapper();
        try (var select = connection.createStatement();
                var rows = select.executeQuery("select id,payload from event_outbox");
                var update =
                        connection.prepareStatement(
                                "update event_outbox set aggregate_id=? where id=?")) {
            while (rows.next()) {
                String order;
                try {
                    order = mapper.readTree(rows.getString("payload")).path("orderId").asString();
                } catch (RuntimeException malformed) {
                    continue;
                }
                if (!order.isBlank() && order.length() <= 26) {
                    update.setString(1, order);
                    update.setString(2, rows.getString("id"));
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
    }
}
