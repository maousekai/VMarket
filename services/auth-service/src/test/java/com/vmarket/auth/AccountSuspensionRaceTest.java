package com.vmarket.auth;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.vmarket.auth.dto.LoginRequest;
import com.vmarket.auth.dto.RefreshRequest;
import com.vmarket.auth.entity.EmailOtp;
import com.vmarket.auth.entity.PasswordResetToken;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.AccountActivityRepository;
import com.vmarket.auth.repository.EmailOtpRepository;
import com.vmarket.auth.repository.PasswordResetTokenRepository;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.security.OpaqueTokenCodec;
import com.vmarket.auth.service.AccountAdminService;
import com.vmarket.auth.service.AuthenticationService;
import com.vmarket.auth.service.OtpService;
import com.vmarket.auth.service.PasswordChangeService;
import com.vmarket.auth.service.PasswordResetService;

/**
 * Review PR #22, M1 — Admin khoá tài khoản chạy XEN giữa một luồng khác trên cùng user,
 * bằng hai transaction JPA thật trên H2.
 *
 * <p>Cách dựng: luồng A (đăng nhập, OTP, refresh, đổi / đặt lại mật khẩu) bị dừng đúng
 * chỗ nó đã đọc "chưa bị khoá" nhưng chưa ghi xong — tại {@code OpaqueTokenCodec.generate}
 * (sắp lưu refresh token) hoặc {@code PasswordEncoder.encode} (sắp ghi mật khẩu mới).
 * Trong lúc A dừng, luồng B gọi {@code suspend}. Rồi nhả A.
 *
 * <p>Trước khi sửa, B commit ngay trong lúc A dừng, và A sau đó:
 * <ul>
 *   <li>đổi / đặt lại mật khẩu: {@code save(user)} bằng entity cũ → xoá mất lần khoá;</li>
 *   <li>đăng nhập / OTP: lưu một refresh token còn hiệu lực SAU khi khoá đã thu hồi phiên.</li>
 * </ul>
 * Sau khi sửa, A giữ khoá dòng user nên B phải chờ A commit — lần khoá còn nguyên và thu
 * hồi luôn token A vừa phát.
 */
@SpringBootTest
class AccountSuspensionRaceTest {

	private static final String PASSWORD = "Abcd1234@";
	private static final String NEW_PASSWORD = "Newpass1@";
	private static final String REASON = "Vi phạm";

	@MockitoSpyBean OpaqueTokenCodec tokenCodec;
	@MockitoSpyBean PasswordEncoder passwordEncoder;

	@Autowired AuthenticationService authenticationService;
	@Autowired OtpService otpService;
	@Autowired PasswordChangeService passwordChangeService;
	@Autowired PasswordResetService passwordResetService;
	@Autowired AccountAdminService accountAdminService;
	@Autowired UserRepository userRepository;
	@Autowired RoleRepository roleRepository;
	@Autowired UserRoleRepository userRoleRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;
	@Autowired EmailOtpRepository emailOtpRepository;
	@Autowired PasswordResetTokenRepository resetTokenRepository;
	@Autowired AccountActivityRepository activityRepository;
	@Autowired JdbcTemplate jdbcTemplate;

	private final CountDownLatch paused = new CountDownLatch(1);
	private final CountDownLatch release = new CountDownLatch(1);
	private ExecutorService pool;

	private User admin;
	private User an;

	@BeforeEach
	void seed() {
		pool = Executors.newFixedThreadPool(2);
		admin = seedUser("admin@example.com", "admin.vmarket", RoleName.ADMIN);
		an = seedUser("an.nguyen@example.com", "an.nguyen", RoleName.BUYER);
	}

	@AfterEach
	void cleanup() {
		release.countDown();
		pool.shutdownNow();
		activityRepository.deleteAll();
		emailOtpRepository.deleteAll();
		resetTokenRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRoleRepository.deleteAll();
		userRepository.deleteAll();
	}

	// --- phát token ----------------------------------------------------------

	@Test
	void dangNhap_biKhoaXenGiua_tokenVuaPhatVanBiThuHoi() throws Exception {
		doAnswer(pauseOnFirstCall()).when(tokenCodec).generate();

		boolean suspendWaited = raceWithSuspend(
				() -> authenticationService.login(new LoginRequest(an.getEmail(), PASSWORD)));

		assertSuspendedWithNoActiveSession();
		assertThat(suspendWaited).as("Admin khoá phải chờ đăng nhập đang giữ dòng user").isTrue();
	}

	@Test
	void otp_biKhoaXenGiua_tokenVuaPhatVanBiThuHoi() throws Exception {
		EmailOtp otp = new EmailOtp();
		otp.setEmail(an.getEmail());
		otp.setCodeHash(passwordEncoder.encode("123456"));
		otp.setExpiresAt(Instant.now().plus(5, ChronoUnit.MINUTES));
		emailOtpRepository.save(otp);
		doAnswer(pauseOnFirstCall()).when(tokenCodec).generate();

		boolean suspendWaited = raceWithSuspend(() -> otpService.verifyOtp(an.getEmail(), "123456"));

		assertSuspendedWithNoActiveSession();
		assertThat(suspendWaited).as("Admin khoá phải chờ xác thực OTP đang giữ dòng user").isTrue();
	}

	@Test
	void refresh_biKhoaXenGiua_tokenMoiVanBiThuHoi() throws Exception {
		String refreshToken = authenticationService.login(new LoginRequest(an.getEmail(), PASSWORD)).refreshToken();
		doAnswer(pauseOnFirstCall()).when(tokenCodec).generate();

		boolean suspendWaited = raceWithSuspend(() -> authenticationService.refresh(new RefreshRequest(refreshToken)));

		assertSuspendedWithNoActiveSession();
		assertThat(suspendWaited).as("Admin khoá phải chờ refresh đang giữ dòng user").isTrue();
	}

	// --- ghi mật khẩu --------------------------------------------------------

	@Test
	void doiMatKhau_biKhoaXenGiua_khongXoaMatLanKhoa() throws Exception {
		doAnswer(pauseOnFirstCall()).when(passwordEncoder).encode(any());

		boolean suspendWaited = raceWithSuspend(() -> {
			passwordChangeService.changePassword(an.getId(), PASSWORD, NEW_PASSWORD);
			return null;
		});

		assertSuspendedWithNoActiveSession();
		assertThat(passwordEncoder.matches(NEW_PASSWORD, reload(an).getPasswordHash()))
				.as("đổi mật khẩu commit trước nên vẫn có hiệu lực").isTrue();
		assertThat(suspendWaited).as("Admin khoá phải chờ đổi mật khẩu đang giữ dòng user").isTrue();
	}

	@Test
	void datLaiMatKhau_biKhoaXenGiua_khongXoaMatLanKhoa() throws Exception {
		PasswordResetToken token = new PasswordResetToken();
		token.setUserId(an.getId());
		token.setCodeHash(passwordEncoder.encode("654321"));
		token.setExpiresAt(Instant.now().plus(5, ChronoUnit.MINUTES));
		resetTokenRepository.save(token);
		doAnswer(pauseOnFirstCall()).when(passwordEncoder).encode(any());

		boolean suspendWaited = raceWithSuspend(
				() -> passwordResetService.resetPassword(an.getEmail(), "654321", NEW_PASSWORD));

		assertSuspendedWithNoActiveSession();
		assertThat(passwordEncoder.matches(NEW_PASSWORD, reload(an).getPasswordHash())).isTrue();
		assertThat(suspendWaited).as("Admin khoá phải chờ đặt lại mật khẩu đang giữ dòng user").isTrue();
	}

	// --- helpers -------------------------------------------------------------

	/**
	 * Chạy {@code flow} tới điểm dừng, cho Admin khoá {@code an} trong lúc đó, rồi nhả
	 * {@code flow}.
	 *
	 * @return {@code true} nếu tới lúc nhả {@code flow} mà thao tác khoá vẫn đang chờ khoá
	 *         dòng (tức là hai bên đã được tuần tự hoá)
	 */
	private boolean raceWithSuspend(Callable<?> flow) throws Exception {
		Future<?> flowResult = pool.submit(flow);
		assertThat(paused.await(10, SECONDS)).as("luồng A phải tới được điểm dừng").isTrue();

		Future<?> suspendResult = pool.submit(() -> accountAdminService.suspend(an.getId(), REASON, admin.getId()));
		awaitDoneOrBlocked(suspendResult);
		boolean suspendWaited = !suspendResult.isDone();

		release.countDown();
		suspendResult.get(10, SECONDS);
		try {
			flowResult.get(10, SECONDS);
		} catch (ExecutionException rejected) {
			// Luồng A bị từ chối cũng không sao — điều cần kiểm là trạng thái cuối cùng.
		}
		return suspendWaited;
	}

	/** Chờ tới khi thao tác khoá chạy xong, hoặc H2 báo có phiên đang chờ khoá dòng. */
	private void awaitDoneOrBlocked(Future<?> future) throws InterruptedException {
		long deadline = System.nanoTime() + SECONDS.toNanos(10);
		while (!future.isDone() && !someSessionWaitingForLock()) {
			assertThat(System.nanoTime()).as("khoá không xong mà cũng không chờ khoá dòng").isLessThan(deadline);
			Thread.sleep(10);
		}
	}

	private boolean someSessionWaitingForLock() {
		Integer waiting = jdbcTemplate.queryForObject(
				"select count(*) from information_schema.sessions where blocker_id is not null", Integer.class);
		return waiting != null && waiting > 0;
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

	private void assertSuspendedWithNoActiveSession() {
		User after = reload(an);
		assertThat(after.isSuspended()).as("lần khoá của Admin phải còn nguyên").isTrue();
		assertThat(after.getSuspendedReason()).isEqualTo(REASON);
		assertThat(after.getSuspendedBy()).isEqualTo(admin.getId());
		assertThat(refreshTokenRepository.findAll())
				.filteredOn(t -> t.getUserId().equals(an.getId()))
				.as("không còn refresh token nào của tài khoản bị khoá còn hiệu lực")
				.allSatisfy(t -> assertThat(t.getRevokedAt()).isNotNull());
	}

	private User reload(User u) {
		return userRepository.findById(u.getId()).orElseThrow();
	}

	private User seedUser(String email, String username, RoleName roleName) {
		Role role = roleRepository.findByName(roleName).orElseGet(() -> {
			Role r = new Role();
			r.setName(roleName);
			return roleRepository.save(r);
		});
		User u = new User();
		u.setEmail(email);
		u.setUsername(username);
		u.setPasswordHash(passwordEncoder.encode(PASSWORD));
		u.setEmailVerified(true);
		userRepository.save(u);
		userRoleRepository.save(new UserRole(u.getId(), role.getId()));
		return u;
	}
}
