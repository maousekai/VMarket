package com.vmarket.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.vmarket.shop.entity.Shop;

/**
 * Chạy Flyway THẬT (V1, V2) trên H2 (MODE=PostgreSQL) và để
 * Hibernate {@code ddl-auto: validate} đối chiếu entity với schema do migration sinh ra
 * — cùng khuôn với {@code FlywayMigrationTest} của auth-service / user-service.
 *
 * <p>Context khởi động được nghĩa là: (1) script chạy không lỗi, (2) mapping entity khớp
 * cột/kiểu trong migration. Các test còn lại chạy trên schema Hibernate tự sinh nên không
 * bắt được lỗi trong file migration.
 *
 * <p>DB riêng ({@code shop_flyway_it}) để không đụng schema của test khác.
 */
@SpringBootTest
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:shop_flyway_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.flyway.enabled=true",
		"spring.jpa.hibernate.ddl-auto=validate",
})
class FlywayMigrationTest {

	@Autowired Flyway flyway;
	@Autowired DataSource dataSource;
	@Autowired JdbcTemplate jdbcTemplate;

	@Test
	void migrations_applied_upToLatestVersion() {
		var current = flyway.info().current();
		assertThat(current).isNotNull();
		assertThat(current.getVersion().getVersion()).isEqualTo("2");
		assertThat(flyway.info().applied()).hasSize(2);
	}

	@Test
	void columns_present_and_entitiesValidateAgainstMigratedSchema() throws Exception {
		try (Connection c = dataSource.getConnection()) {
			for (String column : new String[] { "owner_id", "name", "name_key", "description", "logo_url",
					"cover_url", "policies", "contact_email", "contact_phone", "province", "district", "ward",
					"street_address", "status", "status_reason", "approved_at", "version", "created_at",
					"updated_at" }) {
				assertThat(columnExists(c, "shops", column)).as("shops.%s", column).isTrue();
			}
			for (String column : new String[] { "shop_id", "from_status", "to_status", "reason", "changed_by",
					"created_at" }) {
				assertThat(columnExists(c, "shop_status_history", column))
						.as("shop_status_history.%s", column).isTrue();
			}
			// V2 - nhat ky sua noi dung ho so (ra soat PR #24, muc A)
			for (String column : new String[] { "shop_id", "field_name", "old_value", "new_value",
					"status_at_change", "changed_by", "created_at" }) {
				assertThat(columnExists(c, "shop_profile_changes", column))
						.as("shop_profile_changes.%s", column).isTrue();
			}
		}
		// Context đã khởi động với ddl-auto=validate -> entity đã khớp schema migration.
	}

	/**
	 * Tên ràng buộc trong migration phải trùng hằng số ở {@link Shop}: đó là thứ
	 * {@code GlobalExceptionHandler} dò để trả đúng mã lỗi khi hai request đăng ký song
	 * song cùng lọt qua bước kiểm tra trước. Lệch tên thì trên PostgreSQL thật client
	 * nhận {@code DATA_CONFLICT} chung chung thay vì {@code SHOP_ALREADY_EXISTS}.
	 */
	@Test
	void uniqueConstraints_coTenKhopEntity() {
		insertShop("01JBQ9YDX7K3M8N5P2R4T6V801", "01JBQ9YDX7K3M8N5P2R4T6V8A1", "gom hoi an");

		assertThatThrownBy(() -> insertShop("01JBQ9YDX7K3M8N5P2R4T6V802", "01JBQ9YDX7K3M8N5P2R4T6V8A1", "khac"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining(Shop.UQ_OWNER_ID.toUpperCase());
		assertThatThrownBy(() -> insertShop("01JBQ9YDX7K3M8N5P2R4T6V803", "01JBQ9YDX7K3M8N5P2R4T6V8A2", "gom hoi an"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining(Shop.UQ_NAME_KEY.toUpperCase());

		jdbcTemplate.update("DELETE FROM shops");
	}

	private void insertShop(String id, String ownerId, String nameKey) {
		jdbcTemplate.update("""
				INSERT INTO shops (id, owner_id, name, name_key, contact_email, contact_phone, province, district,
				                   ward, street_address, status)
				VALUES (?, ?, ?, ?, 'a@b.vn', '0912345678', 'Quảng Nam', 'Hội An', 'Thanh Hà', '12 Phạm Phán', 'PENDING')
				""", id, ownerId, nameKey, nameKey);
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
}
