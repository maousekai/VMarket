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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.auth.email.EmailMessage;
import com.vmarket.auth.email.EmailSender;
import com.vmarket.auth.entity.EmailOtp;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.repository.EmailOtpRepository;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;

@SpringBootTest
@AutoConfigureMockMvc
class OtpVerifyApiTest {

	@Autowired MockMvc mockMvc;
	@Autowired EmailOtpRepository otpRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserRoleRepository userRoleRepository;
	@Autowired RoleRepository roleRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;
	@Autowired PasswordEncoder passwordEncoder;

	@MockitoBean EmailSender emailSender;

	private static final String EMAIL = "an.nguyen@example.com";
	private static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

	@BeforeEach
	void seedRole() {
		doNothing().when(emailSender).send(org.mockito.ArgumentMatchers.any());
		if (roleRepository.findByName(RoleName.BUYER).isEmpty()) {
			Role r = new Role();
			r.setName(RoleName.BUYER);
			roleRepository.save(r);
		}
	}

	@AfterEach
	void cleanup() {
		refreshTokenRepository.deleteAll();
		otpRepository.deleteAll();
		userRoleRepository.deleteAll();
		userRepository.deleteAll();
	}

	private String requestOtpAndCaptureCode(String email) throws Exception {
		mockMvc.perform(post("/api/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\"}"))
				.andExpect(status().isOk());
		ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
		org.mockito.Mockito.verify(emailSender, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
		Matcher m = SIX_DIGITS.matcher(captor.getValue().textBody());
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private org.springframework.test.web.servlet.ResultActions verify(String email, String otp) throws Exception {
		return mockMvc.perform(post("/api/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"otp\":\"" + otp + "\"}"));
	}

	@Test
	void verify_correctCode_createsVerifiedUser_andReturnsTokens() throws Exception {
		String code = requestOtpAndCaptureCode(EMAIL);

		verify(EMAIL, code)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.roles[0]").value("BUYER"));

		User user = userRepository.findByEmail(EMAIL).orElseThrow();
		assertThat(user.isEmailVerified()).isTrue();
		assertThat(user.getPasswordHash()).isNull();
		assertThat(userRoleRepository.findByUserId(user.getId())).hasSize(1);
		assertThat(otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL).orElseThrow().getConsumedAt()).isNotNull();
	}

	@Test
	void verify_existingPasswordUser_flipsVerified_keepsPassword() throws Exception {
		Role buyer = roleRepository.findByName(RoleName.BUYER).orElseThrow();
		User existing = new User();
		existing.setEmail(EMAIL);
		existing.setUsername("an.nguyen");
		existing.setPasswordHash(passwordEncoder.encode("Abcd1234@"));
		existing.setEmailVerified(false);
		userRepository.save(existing);
		userRoleRepository.save(new com.vmarket.auth.entity.UserRole(existing.getId(), buyer.getId()));
		String hashBefore = existing.getPasswordHash();

		String code = requestOtpAndCaptureCode(EMAIL);
		verify(EMAIL, code).andExpect(status().isOk());

		User after = userRepository.findByEmail(EMAIL).orElseThrow();
		assertThat(after.isEmailVerified()).isTrue();
		assertThat(after.getPasswordHash()).isEqualTo(hashBefore);
	}

	@Test
	void verify_wrongCode_400_incrementsAttempts() throws Exception {
		requestOtpAndCaptureCode(EMAIL);

		verify(EMAIL, "000000")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("OTP_INVALID"));

		assertThat(otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL).orElseThrow().getAttempts()).isEqualTo(1);
	}

	@Test
	void verify_fiveWrong_invalidatesCode() throws Exception {
		String code = requestOtpAndCaptureCode(EMAIL);
		String wrong = code.equals("000000") ? "111111" : "000000";

		for (int i = 1; i <= 4; i++) {
			verify(EMAIL, wrong).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("OTP_INVALID"));
		}
		verify(EMAIL, wrong).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("OTP_TOO_MANY_ATTEMPTS"));

		// mã đã bị vô hiệu — kể cả nhập đúng cũng không dùng được
		verify(EMAIL, code).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("OTP_TOO_MANY_ATTEMPTS"));
	}

	@Test
	void verify_expiredCode_400() throws Exception {
		String code = requestOtpAndCaptureCode(EMAIL);
		EmailOtp otp = otpRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL).orElseThrow();
		otp.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
		otpRepository.save(otp);

		verify(EMAIL, code).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("OTP_EXPIRED"));
	}

	@Test
	void verify_consumedCode_400_alreadyUsed() throws Exception {
		String code = requestOtpAndCaptureCode(EMAIL);
		verify(EMAIL, code).andExpect(status().isOk());

		verify(EMAIL, code).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("OTP_ALREADY_USED"));
	}

	@Test
	void verify_noRequest_400_notFound() throws Exception {
		verify("never@requested.com", "123456")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("OTP_NOT_FOUND"));
	}
}
