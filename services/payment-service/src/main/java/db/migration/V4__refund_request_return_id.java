package db.migration;

import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V4__refund_request_return_id extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();

        // 1. Add return_id column
        try (var ddl = connection.createStatement()) {
            ddl.execute("ALTER TABLE refund_requests ADD COLUMN return_id VARCHAR(36)");
        }

        // 2. Find FK constraints on payment_id and drop them first (needed for H2 & Postgres)
        List<String> fkNames = new ArrayList<>();
        try (var rs = connection.getMetaData().getImportedKeys(null, null, "REFUND_REQUESTS")) {
            while (rs.next()) {
                if ("PAYMENT_ID".equalsIgnoreCase(rs.getString("FKCOLUMN_NAME"))) {
                    String fk = rs.getString("FK_NAME");
                    if (fk != null) fkNames.add(fk);
                }
            }
        }
        if (fkNames.isEmpty()) {
            try (var rs = connection.getMetaData().getImportedKeys(null, null, "refund_requests")) {
                while (rs.next()) {
                    if ("PAYMENT_ID".equalsIgnoreCase(rs.getString("FKCOLUMN_NAME"))) {
                        String fk = rs.getString("FK_NAME");
                        if (fk != null) fkNames.add(fk);
                    }
                }
            }
        }

        try (var ddl = connection.createStatement()) {
            for (String fk : fkNames) {
                ddl.execute("ALTER TABLE refund_requests DROP CONSTRAINT \"" + fk.replace("\"", "\"\"") + "\"");
            }
        }

        // 3. Find unique constraints on payment_id
        List<String> uniqueConstraintNames = new ArrayList<>();
        try (var select = connection.createStatement();
                var rows =
                        select.executeQuery(
                                """
            select tc.constraint_name from information_schema.table_constraints tc
            join information_schema.key_column_usage k on tc.constraint_name=k.constraint_name
                and tc.constraint_schema=k.constraint_schema and tc.table_name=k.table_name
            where lower(tc.table_name)='refund_requests' and tc.table_schema=current_schema() and tc.constraint_type='UNIQUE'
            group by tc.constraint_name having count(*)=1 and max(lower(k.column_name))='payment_id'
            """)) {
            while (rows.next()) {
                uniqueConstraintNames.add(rows.getString(1));
            }
        }

        try (var ddl = connection.createStatement()) {
            for (String name : uniqueConstraintNames) {
                try {
                    ddl.execute("ALTER TABLE refund_requests DROP CONSTRAINT \"" + name.replace("\"", "\"\"") + "\"");
                } catch (Exception ignored) {
                }
            }
        }

        // 4. Find and drop any lingering unique index on payment_id (in H2 index may persist after constraint drop)
        List<String> uniqueIndexNames = new ArrayList<>();
        try (var rs = connection.getMetaData().getIndexInfo(null, null, "REFUND_REQUESTS", true, false)) {
            while (rs.next()) {
                if ("PAYMENT_ID".equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    String idx = rs.getString("INDEX_NAME");
                    if (idx != null) uniqueIndexNames.add(idx);
                }
            }
        }
        if (uniqueIndexNames.isEmpty()) {
            try (var rs = connection.getMetaData().getIndexInfo(null, null, "refund_requests", true, false)) {
                while (rs.next()) {
                    if ("PAYMENT_ID".equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                        String idx = rs.getString("INDEX_NAME");
                        if (idx != null) uniqueIndexNames.add(idx);
                    }
                }
            }
        }

        try (var ddl = connection.createStatement()) {
            for (String idx : uniqueIndexNames) {
                try {
                    ddl.execute("DROP INDEX IF EXISTS \"" + idx.replace("\"", "\"\"") + "\"");
                } catch (Exception ignored) {
                }
            }

            // 5. Re-add non-unique FK constraint
            ddl.execute(
                    "ALTER TABLE refund_requests ADD CONSTRAINT fk_refund_requests_payment FOREIGN KEY (payment_id) REFERENCES payment_transactions(id)");

            // 6. Create standard indexes compatible with both H2 and PostgreSQL
            ddl.execute("CREATE UNIQUE INDEX idx_refund_return_id ON refund_requests(return_id)");
            ddl.execute("CREATE INDEX idx_refund_payment_id ON refund_requests(payment_id)");
        }
    }
}
