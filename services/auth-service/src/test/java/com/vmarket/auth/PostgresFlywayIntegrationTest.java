package com.vmarket.auth;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.vmarket.auth.dto.RegisterRequest;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.service.RegistrationService;

/**
 * Chạy Flyway V1→V9 và đăng ký tài khoản trên PostgreSQL THẬT (Testcontainers),
 * để dành từ PBL6-41 tới nay — {@link FlywayMigrationTest} chỉ chạy trên H2
 * (giả lập cú pháp Postgres qua {@code MODE=PostgreSQL}), không tự xác nhận
 * được tên constraint UNIQUE thật do Postgres tự sinh.
 *
 * <p>Đây chính là lỗ hổng mà {@code RegistrationServiceRaceTest} phải né bằng
 * mock: {@code RegistrationService.duplicateConflict} suy ra trường bị trùng
 * bằng cách so khớp chuỗi con "email"/"username" trong tên constraint — giả
 * định đó chưa từng được kiểm trên engine thật cho tới ticket này.
 *
 * <p><b>Vì sao phải dùng race thật (không phải 2 lệnh gọi tuần tự):</b>
 * {@code RegistrationService.register} kiểm {@code existsByEmail}/{@code
 * existsByUsername} TRƯỚC khi ghi — gọi {@code register} hai lần liên tiếp thì
 * lần hai bị chặn ngay ở bước kiểm tra đó, không bao giờ chạm tới nhánh bắt
 * {@code DataIntegrityViolationException} (nơi tên constraint thật được đọc) —
 * phát hiện khi review PBL6-47. Test dưới đây tạm dừng luồng A ngay TRƯỚC khi
 * ghi (tại {@code PasswordEncoder.encode}, giống {@code
 * AccountSuspensionRaceTest}) để luồng B kịp qua bước kiểm tra và commit trước,
 * buộc luồng A khi được nhả ra phải thật sự chạm ràng buộc UNIQUE của Postgres.
 *
 * <p>{@code disabledWithoutDocker = true} (giống {@code ProductInfrastructureIntegrationTest}):
 * máy dev không có Docker sẽ tự bỏ qua thay vì làm `mvn test` fail cứng. CI
 * (GitHub Actions {@code ubuntu-latest}) có sẵn Docker, không cần cấu hình gì
 * thêm — đã xác nhận qua việc product-service chạy Testcontainers không có
 * bước setup Docker riêng trong workflow.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PostgresFlywayIntegrationTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
					.withDatabaseName("auth_it")
					.withUsername("auth_it")
					.withPassword("auth_it");

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		// src/test/resources/application.yml khoa cung driver-class-name=org.h2.Driver
		// cho toan bo test classpath (xem comment trong file do) -> phai ghi de rieng
		// o day, neu khong driver H2 se tu choi jdbcUrl postgresql:// (that bai luc
		// khoi dong context, khong phai luc chay test).
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.flyway.enabled", () -> "true");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
	}

	@MockitoSpyBean PasswordEncoder passwordEncoder;

	@Autowired Flyway flyway;
	@Autowired RegistrationService registrationService;
	@Autowired UserRepository userRepository;

	private final CountDownLatch paused = new CountDownLatch(1);
	private final CountDownLatch release = new CountDownLatch(1);
	private ExecutorService pool;

	@BeforeEach
	void setUp() {
		pool = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void cleanup() {
		release.countDown();
		pool.shutdownNow();
		userRepository.deleteAll();
	}

	@Test
	void migrations_applied_upToLatestVersion() {
		var current = flyway.info().current();
		assertThat(current).isNotNull();
		assertThat(current.getVersion().getVersion()).isEqualTo("9");
		assertThat(flyway.info().applied()).hasSize(9);
		// Context da khoi dong voi ddl-auto=validate -> entity da khop schema
		// migration tren Postgres THAT (khong phai gia lap MODE=PostgreSQL cua H2).
	}

	@Test
	void duplicateEmail_onRealPostgres_mapsToEmailAlreadyExists() throws Exception {
		doAnswer(pauseOnFirstCall()).when(passwordEncoder).encode(any());

		Future<ApiException> loser = pool.submit(
				() -> tryRegister("trung-email@example.com", "user-mot"));
		assertThat(paused.await(10, SECONDS)).as("luồng A phải tới được điểm dừng").isTrue();

		Future<ApiException> winner = pool.submit(
				() -> tryRegister("trung-email@example.com", "user-hai"));
		assertThat(winner.get(10, SECONDS)).as("luồng B đăng ký trước, không có gì để trùng").isNull();

		release.countDown();
		ApiException loserResult = loser.get(10, SECONDS);
		assertThat(loserResult).as("luồng A phải va chạm UNIQUE constraint thật của Postgres").isNotNull();
		assertThat(loserResult.getCode()).isEqualTo("EMAIL_ALREADY_EXISTS");
	}

	@Test
	void duplicateUsername_onRealPostgres_mapsToUsernameAlreadyExists() throws Exception {
		doAnswer(pauseOnFirstCall()).when(passwordEncoder).encode(any());

		Future<ApiException> loser = pool.submit(
				() -> tryRegister("user-ba@example.com", "trung-username"));
		assertThat(paused.await(10, SECONDS)).as("luồng A phải tới được điểm dừng").isTrue();

		Future<ApiException> winner = pool.submit(
				() -> tryRegister("user-bon@example.com", "trung-username"));
		assertThat(winner.get(10, SECONDS)).as("luồng B đăng ký trước, không có gì để trùng").isNull();

		release.countDown();
		ApiException loserResult = loser.get(10, SECONDS);
		assertThat(loserResult).as("luồng A phải va chạm UNIQUE constraint thật của Postgres").isNotNull();
		assertThat(loserResult.getCode()).isEqualTo("USERNAME_ALREADY_EXISTS");
	}

	/** {@code null} nếu đăng ký thành công, ngược lại {@link ApiException} ném ra. */
	private ApiException tryRegister(String email, String username) {
		try {
			registrationService.register(new RegisterRequest(email, username, "Abcd1234@"));
			return null;
		} catch (ApiException ex) {
			return ex;
		}
	}

	/** Lần gọi đầu tiên dừng lại (báo {@code paused}) tới khi {@code release}; các lần sau chạy thường. */
	private <T> Answer<T> pauseOnFirstCall() {
		AtomicBoolean first = new AtomicBoolean(true);
		return invocation -> {
			if (first.compareAndSet(true, false)) {
				paused.countDown();
				if (!release.await(10, SECONDS)) {
					throw new IllegalStateException("test không nhả luồng A");
				}
			}
			@SuppressWarnings("unchecked")
			T result = (T) invocation.callRealMethod();
			return result;
		};
	}
}
