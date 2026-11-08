package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.security.RefreshTokenCookieService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Option B (PBL6-72): client native nhận/gửi refresh token qua body + header
 * {@code X-Refresh-Token} khi có {@code X-Client-Type: mobile} và KHÔNG có Origin/Referer.
 * Web (có Origin hoặc thiếu X-Client-Type) giữ nguyên hành vi cookie-only của PBL6-46.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MobileRefreshTokenApiTest {

	@Autowired MockMvc mockMvc;
	@Autowired RoleRepository roleRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserRoleRepository userRoleRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired ObjectMapper objectMapper;

	private static final String EMAIL = "mobile.user@example.com";
	private static final String PASSWORD = "Abcd1234@";
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";
	private static final String MOBILE = "mobile";

	@BeforeEach
	void seedUser() {
		Role buyer = roleRepository.findByName(RoleName.BUYER).orElseGet(() -> {
			Role r = new Role();
			r.setName(RoleName.BUYER);
			return roleRepository.save(r);
		});
		User user = new User();
		user.setEmail(EMAIL);
		user.setUsername("mobile.user");
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

	private ResultActions login(String clientType, String origin) throws Exception {
		MockHttpServletRequestBuilder req = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}");
		if (clientType != null) {
			req.header("X-Client-Type", clientType);
		}
		if (origin != null) {
			req.header("Origin", origin);
		}
		return mockMvc.perform(req);
	}

	private String mobileLoginRefreshToken() throws Exception {
		return bodyOf(login(MOBILE, null).andExpect(status().isOk())).get("refreshToken").asString();
	}

	private JsonNode bodyOf(ResultActions actions) throws Exception {
		MockHttpServletResponse res = actions.andReturn().getResponse();
		return objectMapper.readTree(res.getContentAsString());
	}

	@Test
	void login_mobileWithoutOrigin_returnsRefreshTokenInBody_andStillSetsCookie() throws Exception {
		ResultActions result = login(MOBILE, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshExpiresIn").value(15 * 24 * 3600))
				.andExpect(cookie().exists(RefreshTokenCookieService.COOKIE_NAME));
		String cookieValue = result.andReturn().getResponse().getCookie(RefreshTokenCookieService.COOKIE_NAME)
				.getValue();
		assertThat(bodyOf(result).get("refreshToken").asString()).isEqualTo(cookieValue);
	}

	@Test
	void login_withOrigin_orWithoutClientType_neverReturnsRefreshTokenInBody() throws Exception {
		login(MOBILE, ALLOWED_ORIGIN).andExpect(status().isOk())
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andExpect(jsonPath("$.refreshExpiresIn").doesNotExist())
				.andExpect(cookie().exists(RefreshTokenCookieService.COOKIE_NAME));
		login(null, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andExpect(jsonPath("$.refreshExpiresIn").doesNotExist());
		login("web", null).andExpect(status().isOk())
				.andExpect(jsonPath("$.refreshToken").doesNotExist());
		// Referer cũng đủ để coi là trình duyệt.
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.header("X-Client-Type", MOBILE).header("Referer", ALLOWED_ORIGIN + "/login")
				.content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.refreshToken").doesNotExist());
	}

	@Test
	void refresh_viaHeader_rotates_returnsNewRefreshToken() throws Exception {
		String rt1 = mobileLoginRefreshToken();

		ResultActions result = mockMvc.perform(post("/api/auth/refresh")
				.header("X-Client-Type", MOBILE).header("X-Refresh-Token", rt1))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshExpiresIn").isNumber());
		String rt2 = bodyOf(result).get("refreshToken").asString();
		assertThat(rt2).isNotEqualTo(rt1);

		// Token mới dùng được tiếp.
		mockMvc.perform(post("/api/auth/refresh").header("X-Client-Type", MOBILE).header("X-Refresh-Token", rt2))
				.andExpect(status().isOk());
	}

	@Test
	void refresh_headerInvalid_sameErrorCodeAsCookie() throws Exception {
		mockMvc.perform(post("/api/auth/refresh").header("X-Client-Type", MOBILE)
				.header("X-Refresh-Token", "not-a-real-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void refresh_headerWithoutClientType_isIgnored_missing401() throws Exception {
		String rt = mobileLoginRefreshToken();
		mockMvc.perform(post("/api/auth/refresh").header("X-Refresh-Token", rt))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_MISSING"));
	}

	@Test
	void refresh_headerWithAllowedOrigin_isIgnored_missing401() throws Exception {
		String rt = mobileLoginRefreshToken();
		mockMvc.perform(post("/api/auth/refresh").header("X-Client-Type", MOBILE)
				.header("Origin", ALLOWED_ORIGIN).header("X-Refresh-Token", rt))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_MISSING"));
	}

	@Test
	void logout_viaHeader_revokesSession() throws Exception {
		String rt = mobileLoginRefreshToken();
		assertThat(refreshTokenRepository.findAll().get(0).getRevokedAt()).isNull();

		mockMvc.perform(post("/api/auth/logout").header("X-Client-Type", MOBILE).header("X-Refresh-Token", rt))
				.andExpect(status().isOk());

		assertThat(refreshTokenRepository.findAll().get(0).getRevokedAt()).isNotNull();
	}

	@Test
	void logout_headerWithAllowedOrigin_doesNotRevoke() throws Exception {
		String rt = mobileLoginRefreshToken();
		mockMvc.perform(post("/api/auth/logout").header("X-Client-Type", MOBILE)
				.header("Origin", ALLOWED_ORIGIN).header("X-Refresh-Token", rt))
				.andExpect(status().isOk());
		assertThat(refreshTokenRepository.findAll().get(0).getRevokedAt()).isNull();
	}

	@Test
	void disallowedOrigin_stays403_evenWithMobileHeaders() throws Exception {
		String rt = mobileLoginRefreshToken();
		mockMvc.perform(post("/api/auth/logout").header("X-Client-Type", MOBILE)
				.header("Origin", "http://evil.example").header("X-Refresh-Token", rt))
				.andExpect(status().isForbidden());
		assertThat(refreshTokenRepository.findAll().get(0).getRevokedAt()).isNull();
	}

	@Test
	void sessions_listAndRevoke_viaHeader() throws Exception {
		String rt = mobileLoginRefreshToken();

		ResultActions list = mockMvc.perform(get("/api/auth/sessions")
				.header("X-Client-Type", MOBILE).header("X-Refresh-Token", rt))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].current").value(true));
		String id = bodyOf(list).get(0).get("id").asString();

		mockMvc.perform(post("/api/auth/sessions/revoke-others")
				.header("X-Client-Type", MOBILE).header("X-Refresh-Token", rt))
				.andExpect(status().isOk());

		mockMvc.perform(delete("/api/auth/sessions/" + id)
				.header("X-Client-Type", MOBILE).header("X-Refresh-Token", rt))
				.andExpect(status().isOk());
		assertThat(refreshTokenRepository.findAll().get(0).getRevokedAt()).isNotNull();
	}

	@Test
	void sessions_headerWithoutMobileRule_missing401() throws Exception {
		String rt = mobileLoginRefreshToken();
		mockMvc.perform(get("/api/auth/sessions").header("X-Refresh-Token", rt))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_MISSING"));
	}
}
