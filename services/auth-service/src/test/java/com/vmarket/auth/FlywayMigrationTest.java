package com.vmarket.auth;

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
 * Chạy Flyway THẬT (V1 → V2 → V3) trên H2 (MODE=PostgreSQL) và để Hibernate
 * {@code ddl-auto: validate} đối chiếu entity với schema do migration sinh ra.
 *
 * <p>Nếu context khởi động được nghĩa là: (1) các script migration chạy không lỗi,
 * (2) mapping entity khớp với cột/kiểu trong migration. Đây là điểm mà bộ test
 * còn lại (Flyway tắt + {@code create-drop}) không kiểm được.
 *
 * <p>DB riêng ({@code jdbc:h2:mem:flyway_it}) để không đụng schema của các test khác.
 * Test end-to-end trên PostgreSQL thật (Testcontainers) để dành PBL6-47.
 */
@SpringBootTest
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:flyway_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
	void v3_columns_present_and_entitiesValidateAgainstMigratedSchema() throws Exception {
		try (Connection c = dataSource.getConnection()) {
			assertThat(columnExists(c, "users", "failed_login_attempts")).isTrue();
			assertThat(columnExists(c, "users", "locked_until")).isTrue();
			assertThat(columnExists(c, "refresh_tokens", "revoked_at")).isTrue();
			assertThat(columnExists(c, "refresh_tokens", "replaced_by")).isTrue();
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
}
