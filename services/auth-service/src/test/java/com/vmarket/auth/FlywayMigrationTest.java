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
 * Chạy Flyway THẬT (V1 → V5, V7, V8) trên H2 (MODE=PostgreSQL) và để Hibernate
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
		// V6 do PBL6-46 giữ (chưa merge) -> nhánh này có V1..V5 + V7 + V8 = 7 migration.
		assertThat(current.getVersion().getVersion()).isEqualTo("8");
		assertThat(flyway.info().applied()).hasSize(7);
	}

	@Test
	void columns_present_and_entitiesValidateAgainstMigratedSchema() throws Exception {
		try (Connection c = dataSource.getConnection()) {
			// V3
			assertThat(columnExists(c, "users", "failed_login_attempts")).isTrue();
			assertThat(columnExists(c, "users", "locked_until")).isTrue();
			assertThat(columnExists(c, "refresh_tokens", "revoked_at")).isTrue();
			assertThat(columnExists(c, "refresh_tokens", "replaced_by")).isTrue();
			// V4
			assertThat(columnExists(c, "email_otp", "code_hash")).isTrue();
			assertThat(columnExists(c, "email_otp", "expires_at")).isTrue();
			assertThat(columnExists(c, "email_otp", "attempts")).isTrue();
			assertThat(columnExists(c, "email_otp", "consumed_at")).isTrue();
			assertThat(passwordHashIsNullable(c)).isTrue();
			// V5
			assertThat(columnExists(c, "password_reset_token", "user_id")).isTrue();
			assertThat(columnExists(c, "password_reset_token", "code_hash")).isTrue();
			assertThat(columnExists(c, "password_reset_token", "expires_at")).isTrue();
			assertThat(columnExists(c, "password_reset_token", "attempts")).isTrue();
			assertThat(columnExists(c, "password_reset_token", "consumed_at")).isTrue();
			// V7 (PBL6-13)
			assertThat(columnExists(c, "users", "suspended_at")).isTrue();
			assertThat(columnExists(c, "users", "suspended_reason")).isTrue();
			assertThat(columnExists(c, "users", "suspended_by")).isTrue();
			// V8 (PBL6-13) - lịch sử hoạt động
			assertThat(columnExists(c, "account_activities", "user_id")).isTrue();
			assertThat(columnExists(c, "account_activities", "action")).isTrue();
			assertThat(columnExists(c, "account_activities", "actor_id")).isTrue();
			assertThat(columnExists(c, "account_activities", "reason")).isTrue();
			assertThat(columnExists(c, "account_activities", "created_at")).isTrue();
		}
		// Context đã khởi động với ddl-auto=validate -> entity đã khớp schema migration.
	}

	private static boolean passwordHashIsNullable(Connection c) throws Exception {
		DatabaseMetaData meta = c.getMetaData();
		for (String t : new String[] { "users", "USERS" }) {
			for (String col : new String[] { "password_hash", "PASSWORD_HASH" }) {
				try (ResultSet rs = meta.getColumns(null, null, t, col)) {
					if (rs.next()) {
						return rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
					}
				}
			}
		}
		return false;
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
