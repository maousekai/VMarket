package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.vmarket.auth.email.EmailSender;
import com.vmarket.auth.entity.EmailOtp;
import com.vmarket.auth.repository.EmailOtpRepository;
import com.vmarket.auth.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class OtpRequestApiTest {

	@Autowired MockMvc mockMvc;
	@Autowired EmailOtpRepository otpRepository;
	@Autowired UserRepository userRepository;

	@MockitoBean EmailSender emailSender;

	private static final String EMAIL = "an.nguyen@example.com";

	@BeforeEach
	void stubMailer() {
		doNothing().when(emailSender).send(org.mockito.ArgumentMatchers.any());
	}

	@AfterEach
	void cleanup() {
		otpRepository.deleteAll();
		userRepository.deleteAll();
	}

	private ResultActions request(String email) throws Exception {
		return mockMvc.perform(post("/api/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\"}"));
	}

	@Test
	void request_returns200_andPersistsHashedOtp() throws Exception {
		request(EMAIL)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.expiresInSeconds").value(300));

		EmailOtp otp = otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL).orElseThrow();
		assertThat(otp.getCodeHash()).startsWith("$2"); // BCrypt, không phải plaintext
		assertThat(otp.getAttempts()).isZero();
		assertThat(otp.getConsumedAt()).isNull();
		org.mockito.Mockito.verify(emailSender).send(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void request_again_within60s_returns429_resendTooSoon() throws Exception {
		request(EMAIL).andExpect(status().isOk());
		request(EMAIL)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error.code").value("OTP_RESEND_TOO_SOON"));
	}

	@Test
	void request_moreThanHourlyLimit_returns429_rateLimited() throws Exception {
		// Seed 5 OTP đã 'consumed' (created_at ~ now) -> resend-60s không chặn
		// (điều kiện là consumedAt == null), nhưng đếm 1 giờ = 5 >= hourlyLimit.
		for (int i = 0; i < 5; i++) {
			EmailOtp old = new EmailOtp();
			old.setEmail(EMAIL);
			old.setCodeHash("$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
			old.setExpiresAt(Instant.now().plus(5, ChronoUnit.MINUTES));
			old.setConsumedAt(Instant.now());
			otpRepository.save(old);
		}
		request(EMAIL)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error.code").value("OTP_RATE_LIMITED"));
	}

	@Test
	void request_invalidEmail_returns400() throws Exception {
		request("not-an-email")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}
}
