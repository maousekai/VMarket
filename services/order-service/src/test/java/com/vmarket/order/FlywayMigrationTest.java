package com.vmarket.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Chạy Flyway THẬT (V1 → V3) trên H2 (MODE=PostgreSQL) và để Hibernate
 * {@code ddl-auto: validate} đối chiếu entity với schema do migration sinh ra —
 * cùng khuôn với {@code FlywayMigrationTest} của auth-service / user-service /
 * shop-service.
 *
 * <p>Context khởi động được nghĩa là: (1) script chạy không lỗi, (2) mapping entity
 * khớp cột/kiểu trong migration. Các test còn lại chạy trên schema Hibernate tự
 * sinh nên không bắt được lỗi trong file migration.
 *
 * <p>DB riêng ({@code order_flyway_it}) để không đụng schema của test khác.
 */
@SpringBootTest
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:order_flyway_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.flyway.enabled=true",
		"spring.flyway.locations=classpath:db/migration",
		"spring.jpa.hibernate.ddl-auto=validate",
})
class FlywayMigrationTest {

	@Autowired Flyway flyway;
	@Autowired DataSource dataSource;

	@Test
	void migrations_applied_upToLatestVersion() {
		var current = flyway.info().current();
		assertThat(current).isNotNull();
		assertThat(current.getVersion().getVersion()).isEqualTo("3");
		assertThat(flyway.info().applied()).hasSize(3);
	}

	@Test
	void columns_present_districtIsOptional_andEntitiesValidateAgainstMigratedSchema() throws Exception {
		try (Connection c = dataSource.getConnection()) {
			for (String column : new String[] { "user_id", "status", "recipient_name", "phone",
					"province", "district", "ward", "street_address", "note", "total_amount",
					"created_at", "updated_at", "idempotency_key" }) {
				assertThat(columnExists(c, "orders", column)).as("orders.%s", column).isTrue();
			}
			for (String column : new String[] { "order_id", "product_id", "variant_id", "shop_id",
					"quantity", "unit_price", "line_total" }) {
				assertThat(columnExists(c, "order_items", column))
						.as("order_items.%s", column).isTrue();
			}
			// V3 - địa chỉ hai cấp: district được phép NULL.
			assertThat(columnIsNullable(c, "orders", "district")).isTrue();
		}
		// Context đã khởi động với ddl-auto=validate -> entity đã khớp schema migration.
	}

	private static boolean columnExists(Connection c, String table, String column) throws Exception {
		DatabaseMetaData meta = c.getMetaData();
		for (String t : new String[] { table, table.toUpperCase() }) {
			for (String col : new String[] { column, column.toUpperCase() }) {
				try (ResultSet rs = meta.getColumns(null, null, t, col)) {
					if (rs.next()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean columnIsNullable(Connection c, String table, String column) throws Exception {
		DatabaseMetaData meta = c.getMetaData();
		for (String t : new String[] { table, table.toUpperCase() }) {
			for (String col : new String[] { column, column.toUpperCase() }) {
				try (ResultSet rs = meta.getColumns(null, null, t, col)) {
					if (rs.next()) {
						return rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
					}
				}
			}
		}
		return false;
	}
}
