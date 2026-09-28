package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
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
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.vmarket.auth.entity.RefreshToken;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.security.OpaqueTokenCodec;
import com.vmarket.auth.security.RefreshTokenCookieService;

@SpringBootTest
@AutoConfigureMockMvc
class RefreshApiTest {

	@Autowired MockMvc mockMvc;
	@Autowired RoleRepository roleRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserRoleRepository userRoleRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired OpaqueTokenCodec tokenCodec;

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

	private String loginAndGetRefreshCookie() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return result.getResponse().getCookie(RefreshTokenCookieService.COOKIE_NAME).getValue();
	}

	private ResultActions refresh(String rawRefreshToken) throws Exception {
		return mockMvc.perform(post("/api/auth/refresh")
				.cookie(new MockCookie(RefreshTokenCookieService.COOKIE_NAME, rawRefreshToken)));
	}

	private String newCookieFrom(ResultActions actions) throws Exception {
		return actions.andReturn().getResponse().getCookie(RefreshTokenCookieService.COOKIE_NAME).getValue();
	}

	@Test
	void refresh_rotates_newPairIssued_oldTokenRevoked() throws Exception {
		String rt1 = loginAndGetRefreshCookie();

		ResultActions result = refresh(rt1).andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andExpect(cookie().exists(RefreshTokenCookieService.COOKIE_NAME));
		String rt2 = newCookieFrom(result);
		assertThat(rt2).isNotEqualTo(rt1);

		RefreshToken old = refreshTokenRepository.findByTokenHash(tokenCodec.hash(rt1)).orElseThrow();
		assertThat(old.getRevokedAt()).isNotNull();
		assertThat(old.getReplacedBy()).isNotNull();
	}

	@Test
	void refresh_reuseAfterGrace_revokesEntireChain_persisted() throws Exception {
		String rt1 = loginAndGetRefreshCookie();
		String rt2 = newCookieFrom(refresh(rt1).andExpect(status().isOk()));

		// Ép token cũ "bị thu hồi từ lâu" để vượt khoảng ân hạn.
		RefreshToken old = refreshTokenRepository.findByTokenHash(tokenCodec.hash(rt1)).orElseThrow();
		old.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS));
		refreshTokenRepository.save(old);

		refresh(rt1).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_REUSED"))
				.andExpect(cookie().maxAge(RefreshTokenCookieService.COOKIE_NAME, 0));

		// rt2 cũng bị thu hồi theo (toàn bộ phiên) — kiểm tra đã persist
		RefreshToken chained = refreshTokenRepository.findByTokenHash(tokenCodec.hash(rt2)).orElseThrow();
		assertThat(chained.getRevokedAt()).isNotNull();
		refresh(rt2).andExpect(status().isUnauthorized());
	}

	@Test
	void refresh_retryWithinGrace_isRejected_withoutRevokingChain() throws Exception {
		String rt1 = loginAndGetRefreshCookie();
		String rt2 = newCookieFrom(refresh(rt1).andExpect(status().isOk()));

		// Dùng lại rt1 ngay (trong ân hạn) -> chỉ từ chối, KHÔNG thu hồi rt2, và
		// KHÔNG xoá cookie (rt2 có thể đang là cookie hợp lệ hiện tại của trình
		// duyệt do request thắng race ghi — xoá nhầm sẽ đăng xuất dù phiên còn sống).
		refresh(rt1).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_ROTATION_CONFLICT"))
				.andExpect(cookie().doesNotExist(RefreshTokenCookieService.COOKIE_NAME));

		refresh(rt2).andExpect(status().isOk());
	}

	@Test
	void refresh_whenAccountLocked_revokesAllTokens_and423() throws Exception {
		String rt1 = loginAndGetRefreshCookie();

		// Mô phỏng Admin khoá tài khoản thủ công (không qua luồng login sai mật khẩu).
		user.setLockedUntil(Instant.now().plus(15, ChronoUnit.MINUTES));
		userRepository.save(user);

		refresh(rt1).andExpect(status().isLocked())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"))
				.andExpect(cookie().maxAge(RefreshTokenCookieService.COOKIE_NAME, 0));

		RefreshToken revoked = refreshTokenRepository.findByTokenHash(tokenCodec.hash(rt1)).orElseThrow();
		assertThat(revoked.getRevokedAt()).isNotNull();
	}

	@Test
	void refresh_missingCookie_401() throws Exception {
		mockMvc.perform(post("/api/auth/refresh"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_MISSING"));
	}

	@Test
	void refresh_unknownToken_401() throws Exception {
		refresh("khong-ton-tai")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void refresh_expiredToken_401() throws Exception {
		String raw = tokenCodec.generate();
		RefreshToken expired = new RefreshToken();
		expired.setUserId(user.getId());
		expired.setTokenHash(tokenCodec.hash(raw));
		expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
		expired.setLastUsedAt(Instant.now());
		refreshTokenRepository.save(expired);

		refresh(raw).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_EXPIRED"));
	}
}
