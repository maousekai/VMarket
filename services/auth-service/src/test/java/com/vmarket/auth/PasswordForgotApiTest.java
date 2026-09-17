package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.vmarket.auth.email.EmailSender;
import com.vmarket.auth.entity.PasswordResetToken;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.repository.PasswordResetTokenRepository;
import com.vmarket.auth.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class PasswordForgotApiTest {

	@Autowired MockMvc mockMvc;
	@Autowired PasswordResetTokenRepository tokenRepository;
	@Autowired UserRepository userRepository;
	@Autowired PasswordEncoder passwordEncoder;

	@MockitoBean EmailSender emailSender;

	private static final String EMAIL = "an.nguyen@example.com";

	@BeforeEach
	void stubMailer() {
		doNothing().when(emailSender).send(org.mockito.ArgumentMatchers.any());
	}

	@AfterEach
	void cleanup() {
		tokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	private User seedUser(String email) {
		User user = new User();
		user.setEmail(email);
		user.setUsername("an.nguyen");
		user.setPasswordHash(passwordEncoder.encode("Abcd1234@"));
		user.setEmailVerified(true);
		return userRepository.save(user);
	}

	private ResultActions forgot(String email) throws Exception {
		return mockMvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\"}"));
	}

	@Test
	void forgot_existingUser_returns200_persistsHashedToken_sendsEmail() throws Exception {
		User user = seedUser(EMAIL);

		forgot(EMAIL)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.expiresInSeconds").value(300));

		PasswordResetToken token = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
		assertThat(token.getCodeHash()).startsWith("$2"); // BCrypt, không phải plaintext
		assertThat(token.getAttempts()).isZero();
		assertThat(token.getConsumedAt()).isNull();
		org.mockito.Mockito.verify(emailSender).send(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void forgot_unknownEmail_returns200_sameShape_noTokenPersisted_noEmailSent() throws Exception {
		forgot("never-registered@example.com")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.expiresInSeconds").value(300));

		assertThat(tokenRepository.findAll()).isEmpty();
		org.mockito.Mockito.verifyNoInteractions(emailSender);
	}

	@Test
	void forgot_again_within60s_returns200_butNoNewTokenOrEmail() throws Exception {
		User user = seedUser(EMAIL);

		forgot(EMAIL).andExpect(status().isOk());
		forgot(EMAIL)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.expiresInSeconds").value(300));

		// Cooldown vẫn chặn ở tầng dưới — chỉ không còn lộ ra qua status code
		// (M-1): chỉ 1 token được tạo, chỉ 1 email được gửi.
		assertThat(tokenRepository.countByUserIdAndCreatedAtAfter(user.getId(), Instant.now().minusSeconds(3600)))
				.isEqualTo(1);
		org.mockito.Mockito.verify(emailSender, org.mockito.Mockito.times(1))
				.send(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void forgot_moreThanHourlyLimit_returns200_butNoNewToken() throws Exception {
		User user = seedUser(EMAIL);
		// Seed 5 token đã 'consumed' (created_at ~ now) -> resend-60s không chặn
		// (điều kiện là consumedAt == null), nhưng đếm 1 giờ = 5 >= hourlyLimit.
		for (int i = 0; i < 5; i++) {
			PasswordResetToken old = new PasswordResetToken();
			old.setUserId(user.getId());
			old.setCodeHash("$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
			old.setExpiresAt(Instant.now().plus(5, ChronoUnit.MINUTES));
			old.setConsumedAt(Instant.now());
			tokenRepository.save(old);
		}
		forgot(EMAIL)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.expiresInSeconds").value(300));

		// Hourly-limit vẫn chặn ở tầng dưới (M-1): vẫn 5 token, không email mới.
		assertThat(tokenRepository.findAll()).hasSize(5);
		org.mockito.Mockito.verifyNoInteractions(emailSender);
	}

	@Test
	void forgot_invalidEmail_returns400() throws Exception {
		forgot("not-an-email")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void forgot_concurrentRequests_onlyOneTokenIssued() throws Exception {
		User user = seedUser(EMAIL);

		int concurrentRequests = 6;
		ExecutorService pool = Executors.newFixedThreadPool(concurrentRequests);
		try {
			List<Callable<Void>> tasks = new ArrayList<>();
			for (int i = 0; i < concurrentRequests; i++) {
				tasks.add(() -> {
					forgot(EMAIL);
					return null;
				});
			}
			List<Future<Void>> futures = pool.invokeAll(tasks);
			for (Future<Void> future : futures) {
				future.get(10, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdown();
		}

		// M-2: check-cooldown + check-hourly-limit + insert không còn là 3 thao tác
		// tách rời không khoá -> đúng 1 trong 6 request song song tạo được token
		// (những request còn lại bị chặn bởi cooldown 60s của request thắng cuộc).
		assertThat(tokenRepository.countByUserIdAndCreatedAtAfter(user.getId(), Instant.now().minusSeconds(3600)))
				.isEqualTo(1);
	}
}
