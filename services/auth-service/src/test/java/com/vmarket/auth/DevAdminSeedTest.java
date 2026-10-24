package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Tài khoản ADMIN mẫu cho dev/e2e ({@code db/dev/R__seed_dev_admin.sql}).
 *
 * <p>Ba điều cần giữ: (1) mật khẩu ghi trong tài liệu khớp hash trong script, (2) script
 * <b>chỉ</b> được nạp bởi profile dev để production không bao giờ có tài khoản này,
 * (3) script chạy được trên DB sạch, chạy lại không nhân đôi, và không ghi đè tài khoản
 * admin đã tạo tay.
 */
class DevAdminSeedTest {

	private static final String SEED = "db/dev/R__seed_dev_admin.sql";
	private static final String EMAIL = "admin@vmarket.local";

	@Test
	void seedHash_matchesTheDocumentedPassword() throws Exception {
		String sql = new String(new ClassPathResource(SEED).getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
		Matcher hash = Pattern.compile("'(\\$2[aby]\\$\\d\\d\\$[./A-Za-z0-9]{53})'").matcher(sql);

		assertThat(hash.find()).as("BCrypt hash trong script seed").isTrue();
		var encoder = new BCryptPasswordEncoder();
		assertThat(encoder.matches("Admin@12345", hash.group(1))).isTrue();
		assertThat(encoder.matches("Admin@12346", hash.group(1))).isFalse();
	}

	@Test
	void seed_isLoadedOnlyByTheDevProfile() throws Exception {
		Path resources = Path.of("src/main/resources");
		String main = Files.readString(resources.resolve("application.yml"));
		String prod = Files.readString(resources.resolve("application-prod.yml"));
		String dev = Files.readString(resources.resolve("application-dev.yml"));

		assertThat(main).doesNotContain("db/dev");
		assertThat(prod).doesNotContain("db/dev");
		assertThat(dev).contains("classpath:db/migration,classpath:db/dev");
	}

	@Test
	void seed_createsOneActiveAdmin_andRunningAgainAddsNothing() throws Exception {
		Flyway flyway = flyway("dev_seed_fresh", "classpath:db/migration", "classpath:db/dev");
		flyway.migrate();
		flyway.migrate();

		try (Connection c = DriverManager.getConnection(url("dev_seed_fresh"), "sa", "")) {
			assertThat(count(c, "SELECT COUNT(*) FROM users WHERE email = '" + EMAIL
					+ "' AND username = 'admin.vmarket' AND email_verified = TRUE"
					+ " AND suspended_at IS NULL")).isEqualTo(1L);
			assertThat(count(c, "SELECT COUNT(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id"
					+ " JOIN roles r ON r.id = ur.role_id"
					+ " WHERE u.email = '" + EMAIL + "' AND r.name = 'ADMIN'")).isEqualTo(1L);
			assertThat(count(c, "SELECT COUNT(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id"
					+ " WHERE u.email = '" + EMAIL + "'")).isEqualTo(1L);
		}
	}

	@Test
	void seed_keepsAnAdminThatWasCreatedByHand() throws Exception {
		flyway("dev_seed_manual", "classpath:db/migration").migrate();
		String manualId = "01HMANUALADMIN0000000000AA";
		try (Connection c = DriverManager.getConnection(url("dev_seed_manual"), "sa", "");
				Statement st = c.createStatement()) {
			st.executeUpdate("INSERT INTO users (id, email, username, password_hash, email_verified)"
					+ " VALUES ('" + manualId + "', '" + EMAIL + "', 'admin.vmarket', 'manual-hash', TRUE)");
			st.executeUpdate("INSERT INTO user_roles (user_id, role_id)"
					+ " SELECT '" + manualId + "', id FROM roles WHERE name = 'ADMIN'");
		}

		flyway("dev_seed_manual", "classpath:db/migration", "classpath:db/dev").migrate();

		try (Connection c = DriverManager.getConnection(url("dev_seed_manual"), "sa", "")) {
			assertThat(count(c, "SELECT COUNT(*) FROM users WHERE email = '" + EMAIL + "'"))
					.isEqualTo(1L);
			assertThat(count(c, "SELECT COUNT(*) FROM users WHERE id = '" + manualId
					+ "' AND password_hash = 'manual-hash'")).isEqualTo(1L);
			assertThat(count(c, "SELECT COUNT(*) FROM user_roles WHERE user_id = '" + manualId + "'"))
					.isEqualTo(1L);
		}
	}

	private static String url(String name) {
		return "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
	}

	private static Flyway flyway(String name, String... locations) {
		return Flyway.configure().dataSource(url(name), "sa", "").locations(locations).load();
	}

	private static long count(Connection c, String sql) throws Exception {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}
}
