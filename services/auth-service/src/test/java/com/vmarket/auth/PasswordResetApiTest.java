package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.auth.email.EmailMessage;
import com.vmarket.auth.email.EmailSender;
import com.vmarket.auth.entity.AccountActivity;
import com.vmarket.auth.entity.AccountActivityType;
import com.vmarket.auth.entity.PasswordResetToken;
import com.vmarket.auth.entity.RefreshToken;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.repository.AccountActivityRepository;
import com.vmarket.auth.repository.PasswordResetTokenRepository;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetApiTest {

	@Autowired MockMvc mockMvc;
	@Autowired PasswordResetTokenRepository tokenRepository;
	@Autowired UserRepository userRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired AccountActivityRepository activityRepository;

	@MockitoBean EmailSender emailSender;

	private static final String EMAIL = "an.nguyen@example.com";
	private static final String NEW_PASSWORD = "Newpass1@";
	private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

	@BeforeEach
	void stubMailer() {
		doNothing().when(emailSender).send(org.mockito.ArgumentMatchers.any());
	}

	@AfterEach
	void cleanup() {
		refreshTokenRepository.deleteAll();
		tokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	private User seedUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setUsername("an.nguyen");
		user.setPasswordHash(passwordEncoder.encode("Oldpass1@"));
		user.setEmailVerified(false);
		return userRepository.save(user);
	}

	private String forgotAndCaptureCode(String email) throws Exception {
		mockMvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\"}"))
				.andExpect(status().isOk());
		ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
		org.mockito.Mockito.verify(emailSender, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
		Matcher m = SIX_DIGITS.matcher(captor.getValue().textBody());
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private ResultActions reset(String email, String code, String newPassword) throws Exception {
		return mockMvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"newPassword\":\"" + newPassword + "\"}"));
	}

	@Test
	void reset_correctCode_updatesPasswordHash_clearsLock_revokesRefreshTokens_noTokenInResponse() throws Exception {
		User user = seedUser(EMAIL);
		user.setFailedLoginAttempts(3);
		user.setLockedUntil(Instant.now().plus(10, ChronoUnit.MINUTES));
		userRepository.save(user);

		RefreshToken active = new RefreshToken();
		active.setUserId(user.getId());
		active.setTokenHash("some-hash");
		active.setExpiresAt(Instant.now().plus(15, ChronoUnit.DAYS));
		refreshTokenRepository.save(active);

		String code = forgotAndCaptureCode(EMAIL);

		reset(EMAIL, code, NEW_PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.accessToken").doesNotExist())
				.andExpect(jsonPath("$.refreshToken").doesNotExist());

		User after = userRepository.findByEmail(EMAIL).orElseThrow();
		assertThat(passwordEncoder.matches(NEW_PASSWORD, after.getPasswordHash())).isTrue();
		assertThat(after.getFailedLoginAttempts()).isZero();
		assertThat(after.getLockedUntil()).isNull();

		RefreshToken afterToken = refreshTokenRepository.findById(active.getId()).orElseThrow();
		assertThat(afterToken.getRevokedAt()).isNotNull();

		// FR-USER-04: lịch sử hoạt động cơ bản mà Admin xem được.
		assertThat(activityRepository.findByUserId(user.getId(), Pageable.unpaged()))
				.extracting(AccountActivity::getAction)
				.containsExactly(AccountActivityType.PASSWORD_RESET);
	}

	@Test
	void reset_setsEmailVerifiedTrue_ifNotAlreadyVerified() throws Exception {
		seedUser(EMAIL); // emailVerified = false
		String code = forgotAndCaptureCode(EMAIL);

		reset(EMAIL, code, NEW_PASSWORD).andExpect(status().isOk());

		assertThat(userRepository.findByEmail(EMAIL).orElseThrow().isEmailVerified()).isTrue();
	}

	@Test
	void reset_wrongCode_400_incrementsAttempts() throws Exception {
		User user = seedUser(EMAIL);
		String code = forgotAndCaptureCode(EMAIL);
		// "000000" cố định có 1/1.000.000 khả năng trùng mã thật sinh ngẫu nhiên —
		// chọn mã khác mã thật, giống reset_fiveWrong_invalidatesToken_400_tooManyAttempts.
		String wrong = code.equals("000000") ? "111111" : "000000";

		reset(EMAIL, wrong, NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_INVALID"));

		assertThat(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow().getAttempts())
				.isEqualTo(1);
	}

	@Test
	void reset_correctCode_400_ifAttemptLimitAlreadyReachedConcurrently() throws Exception {
		// H-1: mô phỏng 5 request mã sai song song đã commit "attempts=5" vào DB
		// trước khi request mã đúng này chạy tới đoạn kiểm tra limit (giá trị đọc
		// được ở service có thể vẫn là bản cũ, nhỏ hơn). Mã đúng phải vẫn bị chặn —
		// giới hạn thật nằm ở điều kiện atomic trong markConsumed, không phải ở
		// biến attempts đọc trước đó.
		User user = seedUser(EMAIL);
		String code = forgotAndCaptureCode(EMAIL);

		PasswordResetToken token = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
		token.setAttempts(5); // maxAttempts mặc định = 5 — mô phỏng trạng thái DB đã hết lượt
		tokenRepository.save(token);

		reset(EMAIL, code, NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_TOO_MANY_ATTEMPTS"));

		User afterUser = userRepository.findByEmail(EMAIL).orElseThrow();
		assertThat(passwordEncoder.matches(NEW_PASSWORD, afterUser.getPasswordHash())).isFalse();
	}

	@Test
	@Transactional // custom @Modifying query (flushAutomatically) cần transaction đang mở của caller
	void markConsumed_atomicallyRejects_whenAttemptsAlreadyAtLimit_evenIfNotConsumedYet() {
		// H-1, biên an toàn thật: markConsumed phải tự kiểm tra lại attempts <
		// maxAttempts NGAY TRONG câu UPDATE, không dựa vào giá trị attempts mà
		// service đọc trước đó — giá trị đó có thể đã cũ nếu request mã sai khác
		// vừa commit trong lúc request mã đúng này đang xử lý (đây chính là kẽ hở
		// H-1 mô tả). Mô phỏng: DB đã ở trạng thái "hết lượt" nhưng consumed_at vẫn
		// null (chưa ai tiêu mã) -> markConsumed KHÔNG được phép tiêu mã.
		User user = seedUser(EMAIL);
		PasswordResetToken token = new PasswordResetToken();
		token.setUserId(user.getId());
		token.setCodeHash(passwordEncoder.encode("123456"));
		token.setExpiresAt(Instant.now().plus(5, ChronoUnit.MINUTES));
		token.setAttempts(5);
		token = tokenRepository.save(token);

		int updated = tokenRepository.markConsumed(token.getId(), Instant.now(), 5);

		assertThat(updated).isZero();
		assertThat(tokenRepository.findById(token.getId()).orElseThrow().getConsumedAt()).isNull();
	}

	@Test
	void reset_fiveWrong_invalidatesToken_400_tooManyAttempts() throws Exception {
		seedUser(EMAIL);
		String code = forgotAndCaptureCode(EMAIL);
		String wrong = code.equals("000000") ? "111111" : "000000";

		for (int i = 1; i <= 4; i++) {
			reset(EMAIL, wrong, NEW_PASSWORD).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_INVALID"));
		}
		reset(EMAIL, wrong, NEW_PASSWORD).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_TOO_MANY_ATTEMPTS"));

		// mã đã bị vô hiệu — kể cả nhập đúng cũng không dùng được
		reset(EMAIL, code, NEW_PASSWORD).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_TOO_MANY_ATTEMPTS"));
	}

	@Test
	void reset_expiredToken_400() throws Exception {
		User user = seedUser(EMAIL);
		String code = forgotAndCaptureCode(EMAIL);
		PasswordResetToken token = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
		token.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
		tokenRepository.save(token);

		reset(EMAIL, code, NEW_PASSWORD).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_EXPIRED"));
	}

	@Test
	void reset_consumedToken_400_alreadyUsed() throws Exception {
		seedUser(EMAIL);
		String code = forgotAndCaptureCode(EMAIL);
		reset(EMAIL, code, NEW_PASSWORD).andExpect(status().isOk());

		reset(EMAIL, code, "Another1@").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_ALREADY_USED"));
	}

	@Test
	void reset_noRequest_400_notFound() throws Exception {
		seedUser(EMAIL);

		reset(EMAIL, "123456", NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_NOT_FOUND"));
	}

	@Test
	void reset_unknownEmail_400_notFound() throws Exception {
		reset("never-registered@example.com", "123456", NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_NOT_FOUND"));
	}

	@Test
	void reset_newPasswordFailsPolicy_400_validationError() throws Exception {
		seedUser(EMAIL);
		String code = forgotAndCaptureCode(EMAIL);

		reset(EMAIL, code, "weak")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}
}
