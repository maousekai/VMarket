package com.vmarket.auth;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.vmarket.auth.config.AuthOtpProperties;
import com.vmarket.auth.dto.RegisterRequest;
import com.vmarket.auth.email.EmailSender;
import com.vmarket.auth.entity.EmailOtp;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.EmailOtpLockRepository;
import com.vmarket.auth.repository.EmailOtpRepository;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.security.DeviceMeta;
import com.vmarket.auth.service.OtpService;
import com.vmarket.auth.service.RegistrationService;

/**
 * Ba race của luồng OTP (review PR PBL6-47), dựng lại bằng 2 luồng thật: luồng A
 * bị tạm dừng tại một điểm chọn trước (spy), luồng B chạy chen vào giữa, rồi nhả A.
 * Cùng một bộ kịch bản chạy trên H2 ({@link OtpConcurrencyTest}) và PostgreSQL
 * thật ({@link OtpConcurrencyPostgresTest}).
 */
abstract class OtpRaceScenarios {

	static final String EMAIL = "race@example.com";
	static final String CODE = "123456";
	static final DeviceMeta DEVICE = new DeviceMeta("JUnit", "127.0.0.1");

	@MockitoSpyBean PasswordEncoder passwordEncoder;
	@MockitoSpyBean RoleRepository roleRepository;
	@MockitoBean EmailSender emailSender;

	@Autowired OtpService otpService;
	@Autowired RegistrationService registrationService;
	@Autowired EmailOtpRepository otpRepository;
	@Autowired EmailOtpLockRepository lockRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserRoleRepository userRoleRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;
	@Autowired AuthOtpProperties props;
	@Autowired JdbcTemplate jdbc;

	private final CountDownLatch paused = new CountDownLatch(1);
	private final CountDownLatch release = new CountDownLatch(1);
	private ExecutorService pool;

	@BeforeEach
	void setUp() {
		pool = Executors.newFixedThreadPool(2);
		doNothing().when(emailSender).send(any());
		if (roleRepository.findByName(RoleName.BUYER).isEmpty()) {
			Role r = new Role();
			r.setName(RoleName.BUYER);
			roleRepository.save(r);
		}
	}

	@AfterEach
	void cleanup() {
		release.countDown();
		pool.shutdownNow();
		refreshTokenRepository.deleteAll();
		otpRepository.deleteAll();
		lockRepository.deleteAll();
		userRoleRepository.deleteAll();
		userRepository.deleteAll();
	}

	/**
	 * P1: request mã ĐÚNG đọc {@code attempts = max-1}; trong lúc nó so BCrypt, request
	 * mã SAI khác dùng nốt lượt cuối và commit. Request mã đúng không được phát token.
	 */
	@Test
	void correctCode_afterConcurrentLastWrongAttempt_isRejected() throws Exception {
		EmailOtp otp = seedOtp(CODE);
		jdbc.update("update email_otp set attempts = ? where id = ?", props.getMaxAttempts() - 1, otp.getId());
		doAnswer(pauseOnFirstCall()).when(passwordEncoder).matches(any(), any());

		Future<ApiException> correct = pool.submit(() -> tryVerify(CODE));
		assertThat(paused.await(10, SECONDS)).as("luồng mã đúng phải tới được điểm dừng").isTrue();

		ApiException wrongResult = pool.submit(() -> tryVerify("000000")).get(10, SECONDS);
		assertThat(wrongResult.getCode()).isEqualTo("OTP_TOO_MANY_ATTEMPTS");

		release.countDown();
		ApiException correctResult = correct.get(10, SECONDS);
		assertThat(correctResult).as("không được phát token sau khi đã hết lượt").isNotNull();
		assertThat(correctResult.getCode()).isEqualTo("OTP_TOO_MANY_ATTEMPTS");
		assertThat(userRepository.findByEmail(EMAIL)).isEmpty();
		assertThat(otpRepository.findById(otp.getId()).orElseThrow().getConsumedAt()).isNull();
	}

	/**
	 * P2: đã có {@code hourlyLimit-1} mã trong giờ; hai request song song không được
	 * cùng qua kiểm tra để tạo tổng cộng {@code hourlyLimit+1} mã.
	 */
	@Test
	void concurrentRequests_atHourlyLimitMinusOne_issueOnlyOne() throws Exception {
		for (int i = 0; i < props.getHourlyLimit() - 1; i++) {
			seedOtp(CODE);
		}
		// Lùi created_at để không vướng cooldown 60s, vẫn nằm trong cửa sổ 1 giờ.
		jdbc.update("update email_otp set created_at = ?",
				Timestamp.from(Instant.now().minus(10, ChronoUnit.MINUTES)));
		doAnswer(pauseOnFirstCall()).when(passwordEncoder).encode(any());

		Future<ApiException> first = pool.submit(this::tryRequest);
		assertThat(paused.await(10, SECONDS)).as("luồng A phải tới được điểm dừng").isTrue();

		Future<ApiException> second = pool.submit(this::tryRequest);
		// Có khoá theo email thì B phải chờ A. Nếu B xong trong lúc A còn dừng thì B
		// đã kiểm giới hạn trên dữ liệu cũ — đúng lỗi cần chặn (assert ở dưới sẽ fail).
		try {
			second.get(500, MILLISECONDS);
		} catch (TimeoutException expected) {
			// B đang chờ khoá
		}

		release.countDown();
		ApiException a = first.get(10, SECONDS);
		ApiException b = second.get(10, SECONDS);

		assertThat(a).as("A giữ khoá trước, phải phát mã thành công").isNull();
		// B chờ khoá xong mới kiểm, nên thấy mã A vừa tạo: cooldown chặn trước cả trần
		// theo giờ (cả hai đều là 429). Không có khoá thì B qua cả hai kiểm tra -> null.
		assertThat(b).as("B phải thấy mã của A và bị chặn").isNotNull();
		assertThat(b.getCode()).isIn("OTP_RESEND_TOO_SOON", "OTP_RATE_LIMITED");
		assertThat(otpRepository.countByEmailAndCreatedAtAfter(EMAIL, Instant.now().minus(1, ChronoUnit.HOURS)))
				.isEqualTo(props.getHourlyLimit());
	}

	/**
	 * P2: verify OTP cho email chưa có user; đăng ký bằng mật khẩu chen vào tạo user
	 * cùng email trước khi verify kịp INSERT. Phải là 409 có kiểm soát (không 500),
	 * OTP chưa bị tiêu thụ, và gửi lại đúng mã thì thành công.
	 */
	@Test
	void passwordRegistrationWinsRace_otpVerifyReturnsConflict_thenRetrySucceeds() throws Exception {
		seedOtp(CODE);
		// Dừng sau khi verify đã thấy "chưa có user", ngay trước khi tạo user mới.
		// Spy của repository Spring Data (interface) không gọi được callRealMethod ->
		// trả về Role đã đọc sẵn sau khi nhả.
		Optional<Role> buyer = roleRepository.findByName(RoleName.BUYER);
		doAnswer(pauseOnFirstCall(() -> buyer)).when(roleRepository).findByName(eq(RoleName.BUYER));

		Future<ApiException> otpVerify = pool.submit(() -> tryVerify(CODE));
		assertThat(paused.await(10, SECONDS)).as("luồng verify phải tới được điểm dừng").isTrue();

		pool.submit(() -> registrationService.register(new RegisterRequest(EMAIL, "race.user", "Abcd1234@")))
				.get(10, SECONDS);

		release.countDown();
		ApiException conflict = otpVerify.get(10, SECONDS);
		assertThat(conflict).as("phải trả lỗi có kiểm soát, không ném DataIntegrityViolationException").isNotNull();
		assertThat(conflict.getCode()).isEqualTo("REGISTRATION_CONFLICT");
		assertThat(otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL).orElseThrow().getConsumedAt())
				.as("rollback phải khôi phục OTP để client gửi lại").isNull();

		assertThat(tryVerify(CODE)).as("gửi lại đúng mã phải thành công").isNull();
		assertThat(userRepository.findByEmail(EMAIL).orElseThrow().isEmailVerified()).isTrue();
	}

	// --- helpers -----------------------------------------------------------

	private EmailOtp seedOtp(String code) {
		EmailOtp otp = new EmailOtp();
		otp.setEmail(EMAIL);
		otp.setCodeHash(passwordEncoder.encode(code));
		otp.setExpiresAt(Instant.now().plus(props.getTtl()));
		return otpRepository.save(otp);
	}

	/** {@code null} nếu verify thành công, ngược lại {@link ApiException} ném ra. */
	private ApiException tryVerify(String code) {
		try {
			otpService.verifyOtp(EMAIL, code, DEVICE);
			return null;
		} catch (ApiException ex) {
			return ex;
		}
	}

	private ApiException tryRequest() {
		try {
			otpService.requestOtp(EMAIL);
			return null;
		} catch (ApiException ex) {
			return ex;
		}
	}

	/** Lần gọi đầu tiên dừng lại (báo {@code paused}) tới khi {@code release}; các lần sau chạy thường. */
	private <T> Answer<T> pauseOnFirstCall() {
		return pauseOnFirstCall(null);
	}

	/** Như trên, nhưng trả {@code result} thay vì gọi method thật (khi spy không gọi được). */
	private <T> Answer<T> pauseOnFirstCall(Supplier<T> result) {
		AtomicBoolean first = new AtomicBoolean(true);
		return invocation -> {
			if (first.compareAndSet(true, false)) {
				paused.countDown();
				if (!release.await(10, SECONDS)) {
					throw new IllegalStateException("test không nhả luồng A");
				}
			}
			if (result != null) {
				return result.get();
			}
			@SuppressWarnings("unchecked")
			T real = (T) invocation.callRealMethod();
			return real;
		};
	}
}
