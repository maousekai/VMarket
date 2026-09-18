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
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.security.RefreshTokenCookieService;

/**
 * FR-AUTH-06 — logout / liệt kê / thu hồi phiên. Định danh phiên gọi qua cookie
 * {@code refresh_token} (không dùng access token) — xem {@code SessionService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionApiTest {

	@Autowired MockMvc mockMvc;
	@Autowired RoleRepository roleRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserRoleRepository userRoleRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;
	@Autowired PasswordEncoder passwordEncoder;

	private static final String EMAIL = "an.nguyen@example.com";
	private static final String PASSWORD = "Abcd1234@";

	@BeforeEach
	void seedUser() {
		Role buyer = roleRepository.findByName(RoleName.BUYER).orElseGet(() -> {
			Role r = new Role();
			r.setName(RoleName.BUYER);
			return roleRepository.save(r);
		});
		User user = new User();
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

	private String loginAndGetRefreshCookie(String userAgent) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.header("User-Agent", userAgent)
				.content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return result.getResponse().getCookie(RefreshTokenCookieService.COOKIE_NAME).getValue();
	}

	private MockCookie refreshCookie(String rawToken) {
		return new MockCookie(RefreshTokenCookieService.COOKIE_NAME, rawToken);
	}

	@Test
	void logout_withCookie_revokesToken_clearsCookie() throws Exception {
		String rt = loginAndGetRefreshCookie("device-A");
		assertThat(refreshTokenRepository.count()).isEqualTo(1);

		mockMvc.perform(post("/api/auth/logout").cookie(refreshCookie(rt)))
				.andExpect(status().isOk())
				.andExpect(cookie().maxAge(RefreshTokenCookieService.COOKIE_NAME, 0));

		assertThat(refreshTokenRepository.findAll().get(0).getRevokedAt()).isNotNull();
	}

	@Test
	void logout_withoutCookie_isIdempotent_200() throws Exception {
		mockMvc.perform(post("/api/auth/logout")).andExpect(status().isOk());
	}

	@Test
	void logout_alreadyRevokedToken_stillReturns200() throws Exception {
		String rt = loginAndGetRefreshCookie("device-A");
		mockMvc.perform(post("/api/auth/logout").cookie(refreshCookie(rt))).andExpect(status().isOk());

		mockMvc.perform(post("/api/auth/logout").cookie(refreshCookie(rt))).andExpect(status().isOk());
	}

	@Test
	void listSessions_missingCookie_401() throws Exception {
		mockMvc.perform(get("/api/auth/sessions"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_MISSING"));
	}

	@Test
	void listSessions_multipleDevices_marksCurrent() throws Exception {
		String rtA = loginAndGetRefreshCookie("device-A");
		loginAndGetRefreshCookie("device-B");

		// Mới dùng gần đây trước -> device-B (login sau) đứng trước device-A.
		mockMvc.perform(get("/api/auth/sessions").cookie(refreshCookie(rtA)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].userAgent").value("device-B"))
				.andExpect(jsonPath("$[0].current").value(false))
				.andExpect(jsonPath("$[1].userAgent").value("device-A"))
				.andExpect(jsonPath("$[1].current").value(true));
	}

	@Test
	void revokeOne_ownSession_succeeds_andClearsCookieWhenCurrent() throws Exception {
		String rt = loginAndGetRefreshCookie("device-A");
		String sessionId = refreshTokenRepository.findAll().get(0).getId();

		mockMvc.perform(delete("/api/auth/sessions/" + sessionId).cookie(refreshCookie(rt)))
				.andExpect(status().isOk())
				.andExpect(cookie().maxAge(RefreshTokenCookieService.COOKIE_NAME, 0));

		assertThat(refreshTokenRepository.findById(sessionId).orElseThrow().getRevokedAt()).isNotNull();
	}

	@Test
	void revokeOne_otherDeviceSession_doesNotClearCallerCookie() throws Exception {
		String rtA = loginAndGetRefreshCookie("device-A");
		loginAndGetRefreshCookie("device-B");
		String sessionIdB = refreshTokenRepository.findAll().stream()
				.filter(t -> "device-B".equals(t.getUserAgent())).findFirst().orElseThrow().getId();

		mockMvc.perform(delete("/api/auth/sessions/" + sessionIdB).cookie(refreshCookie(rtA)))
				.andExpect(status().isOk())
				.andExpect(cookie().doesNotExist(RefreshTokenCookieService.COOKIE_NAME));
	}

	@Test
	void revokeOne_unknownId_404() throws Exception {
		String rt = loginAndGetRefreshCookie("device-A");

		mockMvc.perform(delete("/api/auth/sessions/unknown-id").cookie(refreshCookie(rt)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("TARGET_SESSION_NOT_FOUND"));
	}

	@Test
	void revokeOne_anotherUsersSession_404_notLeaked() throws Exception {
		Role buyer = roleRepository.findByName(RoleName.BUYER).orElseThrow();
		User other = new User();
		other.setEmail("khac@example.com");
		other.setUsername("khac");
		other.setPasswordHash(passwordEncoder.encode(PASSWORD));
		other.setEmailVerified(true);
		userRepository.save(other);
		userRoleRepository.save(new UserRole(other.getId(), buyer.getId()));

		MvcResult otherLogin = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"khac@example.com\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk()).andReturn();
		String otherRt = otherLogin.getResponse().getCookie(RefreshTokenCookieService.COOKIE_NAME).getValue();
		String otherSessionId = JsonPath.read(
				mockMvc.perform(get("/api/auth/sessions").cookie(refreshCookie(otherRt)))
						.andReturn().getResponse().getContentAsString(),
				"$[0].id");

		String rt = loginAndGetRefreshCookie("device-A");
		mockMvc.perform(delete("/api/auth/sessions/" + otherSessionId).cookie(refreshCookie(rt)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("TARGET_SESSION_NOT_FOUND"));

		userRepository.delete(other);
	}

	@Test
	void revokeOthers_keepsCurrentSession_revokesRest() throws Exception {
		String rtA = loginAndGetRefreshCookie("device-A");
		loginAndGetRefreshCookie("device-B");
		loginAndGetRefreshCookie("device-C");

		ResultActions result = mockMvc.perform(post("/api/auth/sessions/revoke-others").cookie(refreshCookie(rtA)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Đã thu hồi 2 phiên khác"));
		result.andReturn();

		mockMvc.perform(get("/api/auth/sessions").cookie(refreshCookie(rtA)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].userAgent").value("device-A"));
	}
}
