package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.vmarket.auth.config.DevAdminProperties;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.service.DevAdminSeeder;

/**
 * Tài khoản ADMIN mẫu cho dev/e2e được tạo bằng code ({@link DevAdminSeeder}), không
 * còn migration {@code R__seed_dev_admin.sql}.
 *
 * <p>Bốn điều cần giữ: (1) seeder chỉ chạy ở profile dev, (2) mật khẩu lấy từ
 * {@code DEV_ADMIN_PASSWORD} hoặc sinh ngẫu nhiên chứ không hard-code trong repo,
 * (3) chạy lại / tài khoản đã có thì không ghi đè hay nâng quyền, (4) DB từng chạy
 * seed migration cũ vẫn migrate được bằng profile khác.
 */
class DevAdminSeederTest {

	private static final String EMAIL = "admin@vmarket.local";
	private static final String USERNAME = "admin.vmarket";
	private static final String USER_ID = "01JBQ9YDX7K3M8N5P2R4T6V8AA";
	private static final String ADMIN_ROLE_ID = "03QPZFXY4216QVTKBF3WJFYEKV";

	@TempDir
	Path tempDir;

	@Test
	void createsVerifiedAdminWithConfiguredPassword() {
		UserRepository users = mock(UserRepository.class);
		RoleRepository roles = mock(RoleRepository.class);
		UserRoleRepository userRoles = mock(UserRoleRepository.class);
		when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
		when(users.findByUsername(USERNAME)).thenReturn(Optional.empty());
		when(roles.findByName(RoleName.ADMIN)).thenReturn(Optional.of(adminRole()));
		when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			user.setId(USER_ID);
			return user;
		});

		PasswordEncoder encoder = new BCryptPasswordEncoder();
		new DevAdminSeeder(users, roles, userRoles, encoder, properties("Dev@12345")).run(null);

		ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
		verify(users).saveAndFlush(userCaptor.capture());
		User saved = userCaptor.getValue();
		assertThat(saved.getEmail()).isEqualTo(EMAIL);
		assertThat(saved.getUsername()).isEqualTo(USERNAME);
		assertThat(saved.isEmailVerified()).isTrue();
		assertThat(encoder.matches("Dev@12345", saved.getPasswordHash())).isTrue();

		ArgumentCaptor<UserRole> roleCaptor = ArgumentCaptor.forClass(UserRole.class);
		verify(userRoles).saveAndFlush(roleCaptor.capture());
		assertThat(roleCaptor.getValue().getUserId()).isEqualTo(USER_ID);
		assertThat(roleCaptor.getValue().getRoleId()).isEqualTo(ADMIN_ROLE_ID);
	}

	@Test
	void generatesAndLogsAPasswordWhenNoneIsConfigured() {
		UserRepository users = mock(UserRepository.class);
		RoleRepository roles = mock(RoleRepository.class);
		UserRoleRepository userRoles = mock(UserRoleRepository.class);
		PasswordEncoder encoder = mock(PasswordEncoder.class);
		when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
		when(users.findByUsername(USERNAME)).thenReturn(Optional.empty());
		when(roles.findByName(RoleName.ADMIN)).thenReturn(Optional.of(adminRole()));
		when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(encoder.encode(anyString())).thenReturn("encoded");

		new DevAdminSeeder(users, roles, userRoles, encoder, properties("  ")).run(null);

		ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
		verify(encoder).encode(password.capture());
		assertThat(password.getValue()).isNotBlank().hasSizeGreaterThanOrEqualTo(20);
	}

	@Test
	void keepsAnExistingAccountWithoutElevatingIt() {
		User existing = new User();
		existing.setId("01HMANUALADMIN0000000000AA");
		existing.setEmail(EMAIL);
		existing.setUsername(USERNAME);

		UserRepository users = mock(UserRepository.class);
		RoleRepository roles = mock(RoleRepository.class);
		UserRoleRepository userRoles = mock(UserRoleRepository.class);
		when(users.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

		new DevAdminSeeder(users, roles, userRoles, mock(PasswordEncoder.class), properties("Dev@12345"))
				.run(null);

		verify(users, never()).saveAndFlush(any());
		verify(userRoles, never()).saveAndFlush(any());
	}

	@Test
	void keepsAnAccountWhoseUsernameIsAlreadyTaken() {
		User existing = new User();
		existing.setId("01HMANUALADMIN0000000000AB");
		existing.setEmail("someone-else@example.com");
		existing.setUsername(USERNAME);

		UserRepository users = mock(UserRepository.class);
		RoleRepository roles = mock(RoleRepository.class);
		UserRoleRepository userRoles = mock(UserRoleRepository.class);
		when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
		when(users.findByUsername(USERNAME)).thenReturn(Optional.of(existing));

		new DevAdminSeeder(users, roles, userRoles, mock(PasswordEncoder.class), properties("Dev@12345"))
				.run(null);

		verify(users, never()).saveAndFlush(any());
		verify(userRoles, never()).saveAndFlush(any());
	}

	@Test
	void seederIsDevOnly_andTheOldFlywaySeedIsGone() throws Exception {
		assertThat(DevAdminSeeder.class.getAnnotation(Profile.class).value())
				.containsExactly("dev");

		Path resources = Path.of("src/main/resources");
		String main = Files.readString(resources.resolve("application.yml"));
		String dev = Files.readString(resources.resolve("application-dev.yml"));
		String prod = Files.readString(resources.resolve("application-prod.yml"));

		assertThat(main).contains("repeatable:missing").doesNotContain("db/dev");
		assertThat(dev).contains("DEV_ADMIN_PASSWORD").doesNotContain("db/dev");
		assertThat(prod).doesNotContain("db/dev").doesNotContain("dev-admin");
		assertThat(Files.exists(resources.resolve("db/dev/R__seed_dev_admin.sql"))).isFalse();
	}

	/**
	 * Mô phỏng DB từng chạy profile dev: {@code R__seed_dev_admin.sql} đã áp dụng nhưng file
	 * đã bị xoá. Không có cấu hình ignore thì Flyway dừng (đây là lỗi review #37 mục 3);
	 * với {@code repeatable:missing} thì migrate tiếp bình thường, còn V* thiếu vẫn lỗi.
	 */
	@Test
	void missingDevSeedRepeatableMigration_isIgnored() throws Exception {
		String url = "jdbc:h2:mem:dev_seed_legacy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
		Path seed = tempDir.resolve("R__seed_dev_admin.sql");
		Files.writeString(seed, "-- seed cũ, đã bị xoá khỏi repo\nSELECT 1;\n",
				StandardCharsets.UTF_8);
		String filesystem = "filesystem:" + tempDir.toAbsolutePath();

		Flyway withLegacySeed = Flyway.configure().dataSource(url, "sa", "")
				.locations("classpath:db/migration", filesystem)
				.load();
		withLegacySeed.migrate();
		assertThat(withLegacySeed.info().applied()).hasSize(11);

		Files.delete(seed);

		assertThatThrownBy(() -> Flyway.configure().dataSource(url, "sa", "")
				.locations("classpath:db/migration").load().migrate())
				.isInstanceOf(FlywayException.class)
				.hasMessageContaining("not resolved locally");

		Flyway tolerant = Flyway.configure().dataSource(url, "sa", "")
				.locations("classpath:db/migration")
				.ignoreMigrationPatterns("repeatable:missing")
				.load();
		assertThatCode(tolerant::migrate).doesNotThrowAnyException();
	}

	private static DevAdminProperties properties(String password) {
		DevAdminProperties properties = new DevAdminProperties();
		properties.setPassword(password);
		return properties;
	}

	private static Role adminRole() {
		Role role = new Role();
		role.setId(ADMIN_ROLE_ID);
		role.setName(RoleName.ADMIN);
		return role;
	}
}
