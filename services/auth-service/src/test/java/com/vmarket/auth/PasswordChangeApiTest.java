package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

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

import com.jayway.jsonpath.JsonPath;
import com.vmarket.auth.config.InternalApiProperties;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.security.OpaqueTokenCodec;

/**
 * FR-USER-03 — API nội bộ đổi mật khẩu ({@code PUT /internal/users/{id}/password}).
 *
 * <p>Không {@code @Transactional}: nhánh "tăng bộ đếm sai rồi ném" phải thực sự commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordChangeApiTest {

	@Autowired MockMvc mockMvc;
	@Autowired RoleRepository roleRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserRoleRepository userRoleRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired OpaqueTokenCodec tokenCodec;
	@Autowired InternalApiProperties internalApiProperties;

	private static final String EMAIL = "an.nguyen@example.com";
	private static final String PASSWORD = "Abcd1234@";
	private static final String NEW_PASSWORD = "Xyz98765#";

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

	private ResultActions changePassword(String userId, String current, String next) throws Exception {
		return mockMvc.perform(put("/internal/users/" + userId + "/password")
				.header(InternalApiProperties.HEADER, internalApiProperties.getApiKey())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}"));
	}

	private ResultActions login(String password) throws Exception {
		return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}"));
	}

	@Test
	void doiThanhCong_204_matKhauMoiDangNhapDuoc_matKhauCuThiKhong() throws Exception {
		changePassword(user.getId(), PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

		login(NEW_PASSWORD).andExpect(status().isOk());
		login(PASSWORD).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void doiThanhCong_thuHoiMoiRefreshToken() throws Exception {
		String refreshToken = JsonPath.read(
				login(PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.refreshToken");

		changePassword(user.getId(), PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

		assertThat(refreshTokenRepository.findByTokenHash(tokenCodec.hash(refreshToken)).orElseThrow().getRevokedAt())
				.isNotNull();
	}

	@Test
	void saiMatKhauHienTai_400_vaTangBoDemDangNhapSai() throws Exception {
		changePassword(user.getId(), "Wrong123@", NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_CURRENT_PASSWORD"));

		assertThat(userRepository.findById(user.getId()).orElseThrow().getFailedLoginAttempts()).isEqualTo(1);
	}

	@Test
	void saiNamLan_khoaTam423_nhapDungCungBiChan() throws Exception {
		for (int i = 1; i <= 4; i++) {
			changePassword(user.getId(), "Wrong123@", NEW_PASSWORD).andExpect(status().isBadRequest());
		}
		changePassword(user.getId(), "Wrong123@", NEW_PASSWORD)
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));

		changePassword(user.getId(), PASSWORD, NEW_PASSWORD)
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
		// Chính sách khoá dùng chung với đăng nhập: đăng nhập cũng bị chặn.
		login(PASSWORD).andExpect(status().isLocked());
	}

	@Test
	void matKhauMoiTrungMatKhauCu_400() throws Exception {
		changePassword(user.getId(), PASSWORD, PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PASSWORD_UNCHANGED"));
	}

	@Test
	void matKhauMoiYeu_400_validationError() throws Exception {
		changePassword(user.getId(), PASSWORD, "abc")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.details[0].field").value("newPassword"));
	}

	@Test
	void taiKhoanTaoQuaOtp_chuaCoMatKhau_400() throws Exception {
		user.setPasswordHash(null);
		userRepository.save(user);

		changePassword(user.getId(), PASSWORD, NEW_PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PASSWORD_NOT_SET"));
	}

	@Test
	void taiKhoanBiAdminKhoa_423_suspended() throws Exception {
		user.setSuspendedAt(Instant.now());
		userRepository.save(user);

		changePassword(user.getId(), PASSWORD, NEW_PASSWORD)
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
	}

	@Test
	void userKhongTonTai_404() throws Exception {
		changePassword("01JBQ9YDX7K3M8N5P2R4T6V8ZZ", PASSWORD, NEW_PASSWORD)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void thieuHoacSaiInternalApiKey_401_khongDoiMatKhau() throws Exception {
		String body = "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}";
		mockMvc.perform(put("/internal/users/" + user.getId() + "/password")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		mockMvc.perform(put("/internal/users/" + user.getId() + "/password")
				.header(InternalApiProperties.HEADER, "sai-khoa-sai-khoa-sai-khoa-sai-khoa-0000")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());

		login(PASSWORD).andExpect(status().isOk());
	}
}
