package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import tools.jackson.databind.ObjectMapper;

public class V6__backfill_outbox_aggregates extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var mapper = new ObjectMapper();
        try (var select = context.getConnection().createStatement();
                var rows = select.executeQuery("select id, payload from event_outbox");
                var update =
                        context.getConnection()
                                .prepareStatement(
                                        "update event_outbox set aggregate_id=? where id=?")) {
            while (rows.next()) {
                String orderId;
                try {
                    orderId = mapper.readTree(rows.getString("payload")).path("orderId").asString();
                } catch (RuntimeException malformedPayload) {
                    // Keep malformed rows for the dispatcher's bounded retries/dead state.
                    continue;
                }
                if (!orderId.isBlank() && orderId.length() <= 26) {
                    update.setString(1, orderId);
                    update.setString(2, rows.getString("id"));
                    update.addBatch();
                }
            }
            update.executeBatch();
        }
    }
}
