package com.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** V13: backfill orders.shop_id chỉ cho đơn đúng 1 shop; idempotency unique mở rộng theo shop. */
class SplitOrdersMigrationTest {

    private static final String SHOP_A = "01JBQ9SHOP00000000000000A1";
    private static final String SHOP_B = "01JBQ9SHOP00000000000000B1";

    @Test
    void v13BackfillsShopIdOnlyForSingleShopOrders() {
        String url = "jdbc:h2:mem:split-orders-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("12").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));

        insertOrder(jdbc, "order-single", "key-1");
        insertItem(jdbc, "item-1", "order-single", SHOP_A);
        insertItem(jdbc, "item-2", "order-single", SHOP_A);
        insertOrder(jdbc, "order-multi", "key-2");
        insertItem(jdbc, "item-3", "order-multi", SHOP_A);
        insertItem(jdbc, "item-4", "order-multi", SHOP_B);
        insertOrder(jdbc, "order-empty", null);

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        assertThat(jdbc.queryForObject("select shop_id from orders where id='order-single'", String.class))
                .isEqualTo(SHOP_A);
        assertThat(jdbc.queryForObject("select shop_id from orders where id='order-multi'", String.class))
                .isNull();
        assertThat(jdbc.queryForObject("select shop_id from orders where id='order-empty'", String.class))
                .isNull();
        assertThat(jdbc.queryForObject("select count(*) from orders where checkout_id is not null", Integer.class))
                .isZero();

        // Unique mới: cùng (user, key) nhưng khác shop được phép; trùng cả shop thì bị chặn.
        jdbc.update("update orders set idempotency_key='same', user_id='u' where id='order-single'");
        insertOrderWithShop(jdbc, "order-b", "same", SHOP_B);
        try {
            insertOrderWithShop(jdbc, "order-dup", "same", SHOP_B);
            org.junit.jupiter.api.Assertions.fail("expected unique violation");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            // ok
        }
    }

    private static void insertOrder(JdbcTemplate jdbc, String id, String key) {
        jdbc.update(
                "insert into orders (id,user_id,status,recipient_name,phone,province,ward,street_address,"
                        + "total_amount,idempotency_key) values (?,?,?,?,?,?,?,?,?,?)",
                id, "u", "PENDING", "n", "p", "pr", "w", "s", 1, key);
    }

    private static void insertOrderWithShop(JdbcTemplate jdbc, String id, String key, String shop) {
        jdbc.update(
                "insert into orders (id,user_id,status,recipient_name,phone,province,ward,street_address,"
                        + "total_amount,idempotency_key,shop_id) values (?,?,?,?,?,?,?,?,?,?,?)",
                id, "u", "PENDING", "n", "p", "pr", "w", "s", 1, key, shop);
    }

    private static void insertItem(JdbcTemplate jdbc, String id, String orderId, String shop) {
        jdbc.update(
                "insert into order_items (id,order_id,product_id,shop_id,quantity,unit_price,line_total)"
                        + " values (?,?,?,?,?,?,?)",
                id, orderId, "prod", shop, 1, 1, 1);
    }
}
