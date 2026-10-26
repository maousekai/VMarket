package db.migration;

import com.vmarket.delivery.domain.AreaNames;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Apply the same Unicode/address matching rules to profiles and assignments already stored. */
public class V4__normalize_delivery_areas extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        for (String table : new String[] {"shippers", "delivery_assignments"}) {
            try (var select = context.getConnection().createStatement();
                    var rows = select.executeQuery("select id, province, district from " + table);
                    var update =
                            context.getConnection()
                                    .prepareStatement(
                                            "update "
                                                    + table
                                                    + " set province=?, district=? where id=?")) {
                while (rows.next()) {
                    update.setString(1, AreaNames.normalize(rows.getString("province")));
                    update.setString(2, AreaNames.normalize(rows.getString("district")));
                    update.setString(3, rows.getString("id"));
                    update.addBatch();
                }
                update.executeBatch();
            }
        }
    }
}
