package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;

/**
 * KHÔNG dùng {@code @Transactional}: các nhánh "ghi rồi ném" (khoá tài khoản) phải
 * thực sự commit thì test mới có giá trị chống hồi quy. Dọn dữ liệu ở {@code @AfterEach}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginApiTest {

	@Autowired MockMvc mockMvc;
	@Autowired RoleRepository roleRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserRoleRepository userRoleRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;
	@Autowired PasswordEncoder passwordEncoder;

	private static final String EMAIL = "an.nguyen@example.com";
	private static final String PASSWORD = "Abcd1234@";

	private User user;

	@BeforeEach
	void seedUser() {
		Role buyer = roleRepository.findByName(RoleName.BUYER).orElseGet(() -> {
			Role r = new Role();
			r.setName(RoleName.BUYER);
			return roleRepository.save(r);
		});
		user = new User();
		user.setEmail(EMAIL);
		user.setUsername("an.nguyen");
		user.setPasswordHash(passwordEncoder.encode(PASSWORD));
		user.setEmailVerified(true);
		userRepository.save(user);
		userRoleRepository.save(new UserRole(user.getId(), buyer.getId()));
	}

	@AfterEach
	void cleanup() {
		refreshTokenRepository.deleteAll();
		userRoleRepository.deleteAll();
		userRepository.deleteAll();
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	@Test
	void login_success_returnsTokenPair() throws Exception {
		login(EMAIL, PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(900))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.roles[0]").value("BUYER"));

		assertThat(refreshTokenRepository.count()).isEqualTo(1);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getFailedLoginAttempts()).isZero();
	}

	@Test
	void login_wrongPassword_401_incrementsCounter_persisted() throws Exception {
		login(EMAIL, "Wrong123@")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

		assertThat(userRepository.findById(user.getId()).orElseThrow().getFailedLoginAttempts()).isEqualTo(1);
	}

	@Test
	void login_fiveWrong_locksAccount_persisted_thenCorrectPasswordStillLocked() throws Exception {
		for (int i = 1; i <= 4; i++) {
			login(EMAIL, "Wrong123@").andExpect(status().isUnauthorized());
		}
		login(EMAIL, "Wrong123@")
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));

		assertThat(userRepository.findById(user.getId()).orElseThrow().getLockedUntil()).isNotNull();

		login(EMAIL, PASSWORD)
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
	}

	@Test
	void login_unknownEmail_returns401_invalidCredentials() throws Exception {
		login("khong-ton-tai@example.com", PASSWORD)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void login_pendingUser_succeeds_withStatusPending() throws Exception {
		user.setEmailVerified(false);
		userRepository.save(user);

		login(EMAIL, PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test
	void login_missingPassword_returns400() throws Exception {
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + EMAIL + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}
}
