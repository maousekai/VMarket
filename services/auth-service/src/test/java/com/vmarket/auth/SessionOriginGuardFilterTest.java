package com.vmarket.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
 * PBL6-46 (PR #17 review, finding #3, NFR-SEC-05) — {@code SessionOriginGuardFilter}
 * chỉ áp cho 3 endpoint đổi trạng thái phiên; giá trị hợp lệ lấy từ
 * {@code app.cors.allowed-origins} (test profile: chỉ {@code http://localhost:5173}).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionOriginGuardFilterTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:5173";
	private static final String DISALLOWED_ORIGIN = "http://evil.example.com";

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

	private String login() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return result.getResponse().getCookie(RefreshTokenCookieService.COOKIE_NAME).getValue();
	}

	@Test
	void logout_allowedOrigin_passes() throws Exception {
		String rt = login();
		mockMvc.perform(post("/api/auth/logout")
				.header("Origin", ALLOWED_ORIGIN)
				.cookie(new MockCookie(RefreshTokenCookieService.COOKIE_NAME, rt)))
				.andExpect(status().isOk());
	}

	@Test
	void logout_disallowedOrigin_blockedByCorsBeforeReachingThisFilter() throws Exception {
		// CorsFilter (chạy trước SessionOriginGuardFilter trong chain) đã tự chặn Origin
		// lạ cho MỌI path -> 403 đến từ đó, không phải từ filter này (thân bài lỗi khác
		// hình dạng chuẩn của dự án, không assert jsonPath ở đây). Xem javadoc filter.
		String rt = login();
		mockMvc.perform(post("/api/auth/logout")
				.header("Origin", DISALLOWED_ORIGIN)
				.cookie(new MockCookie(RefreshTokenCookieService.COOKIE_NAME, rt)))
				.andExpect(status().isForbidden());
	}

	@Test
	void logout_missingOrigin_allowedReferer_passes() throws Exception {
		String rt = login();
		mockMvc.perform(post("/api/auth/logout")
				.header("Referer", ALLOWED_ORIGIN + "/account/sessions")
				.cookie(new MockCookie(RefreshTokenCookieService.COOKIE_NAME, rt)))
				.andExpect(status().isOk());
	}

	@Test
	void logout_missingOrigin_disallowedReferer_403() throws Exception {
		String rt = login();
		mockMvc.perform(post("/api/auth/logout")
				.header("Referer", DISALLOWED_ORIGIN + "/x")
				.cookie(new MockCookie(RefreshTokenCookieService.COOKIE_NAME, rt)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("ORIGIN_NOT_ALLOWED"));
	}

	@Test
	void logout_noOriginNoReferer_passesThrough() throws Exception {
		// Không đủ thông tin để từ chối an toàn -> cho qua (xem javadoc filter).
		String rt = login();
		mockMvc.perform(post("/api/auth/logout")
				.cookie(new MockCookie(RefreshTokenCookieService.COOKIE_NAME, rt)))
				.andExpect(status().isOk());
	}

	@Test
	void revokeOne_missingOrigin_disallowedReferer_403() throws Exception {
		String rt = login();
		String sessionId = refreshTokenRepository.findAll().get(0).getId();
		mockMvc.perform(delete("/api/auth/sessions/" + sessionId)
				.header("Referer", DISALLOWED_ORIGIN + "/x")
				.cookie(new MockCookie(RefreshTokenCookieService.COOKIE_NAME, rt)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("ORIGIN_NOT_ALLOWED"));
	}

	@Test
	void revokeOthers_missingOrigin_disallowedReferer_403() throws Exception {
		String rt = login();
		mockMvc.perform(post("/api/auth/sessions/revoke-others")
				.header("Referer", DISALLOWED_ORIGIN + "/x")
				.cookie(new MockCookie(RefreshTokenCookieService.COOKIE_NAME, rt)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("ORIGIN_NOT_ALLOWED"));
	}

	@Test
	void listSessions_readOnly_notGuarded_disallowedRefererStillPasses() throws Exception {
		// GET /sessions chỉ đọc -> không nằm trong phạm vi filter. Dùng Referer (không
		// phải Origin) để phép thử này thật sự đi qua SessionOriginGuardFilter thay vì
		// bị CorsFilter (chặn theo Origin cho MỌI path, xem 2 test trên) chặn trước.
		String rt = login();
		mockMvc.perform(get("/api/auth/sessions")
				.header("Referer", DISALLOWED_ORIGIN + "/x")
				.cookie(new MockCookie(RefreshTokenCookieService.COOKIE_NAME, rt)))
				.andExpect(status().isOk());
	}
}
