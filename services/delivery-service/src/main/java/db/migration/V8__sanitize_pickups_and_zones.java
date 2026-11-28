package db.migration;

import com.vmarket.delivery.domain.AreaNames;
import com.vmarket.delivery.domain.DeliveryRules;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * Review PR 68 lan 2:
 *  - pickup_snapshot chi giu ten shop, dia chi, SDT: bo thong tin ngan hang, email, ownerId da luu.
 *  - Dien ward va khu giao hang (district) cho chuyen cu tu snapshot don.
 */
public class V8__sanitize_pickups_and_zones extends BaseJavaMigration {
    public static final Set<String> PICKUP_FIELDS =
            Set.of(
                    "id",
                    "name",
                    "phone",
                    "province",
                    "ward",
                    "district",
                    "streetAddress",
                    "latitude",
                    "longitude");

    /** Chi giu cac truong lay hang can; dung chung voi DeliveryService luc ban giao. */
    public static ObjectNode sanitizePickup(ObjectMapper json, JsonNode node) {
        ObjectNode clean = json.createObjectNode();
        if (node != null)
            node.properties()
                    .forEach(
                            entry -> {
                                if (PICKUP_FIELDS.contains(entry.getKey()))
                                    clean.set(entry.getKey(), entry.getValue());
                            });
        return clean;
    }

    @Override
    public void migrate(Context context) throws Exception {
        ObjectMapper json = new ObjectMapper();
        var connection = context.getConnection();
        try (var select = connection.createStatement();
                var rows =
                        select.executeQuery(
                                "select assignment_id, shop_id, pickup_snapshot from"
                                        + " delivery_handoffs");
                var update =
                        connection.prepareStatement(
                                "update delivery_handoffs set pickup_snapshot=? where"
                                        + " assignment_id=? and shop_id=?")) {
            while (rows.next()) {
                String raw = rows.getString("pickup_snapshot");
                JsonNode node = json.readTree(raw == null || raw.isBlank() ? "{}" : raw);
                update.setString(1, json.writeValueAsString(sanitizePickup(json, node)));
                update.setString(2, rows.getString("assignment_id"));
                update.setString(3, rows.getString("shop_id"));
                update.addBatch();
            }
            update.executeBatch();
        }
        DeliveryRules rules = DeliveryRules.get();
        try (var select = connection.createStatement();
                var rows =
                        select.executeQuery(
                                "select id, district, order_snapshot from delivery_assignments");
                var update =
                        connection.prepareStatement(
                                "update delivery_assignments set ward=?, district=? where id=?")) {
            while (rows.next()) {
                JsonNode order = json.readTree(rows.getString("order_snapshot"));
                String ward = order.path("ward").asString("");
                String district = rows.getString("district");
                if (district == null || district.isBlank())
                    district = rules.zoneOfWard(ward).orElse(null);
                update.setString(1, ward.isBlank() ? null : ward);
                update.setString(2, district == null ? null : AreaNames.normalize(district));
                update.setString(3, rows.getString("id"));
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
