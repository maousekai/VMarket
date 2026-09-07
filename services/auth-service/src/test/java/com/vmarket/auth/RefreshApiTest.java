package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
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

	private String loginAndGetRefreshToken() throws Exception {
		String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.refreshToken");
	}

	private ResultActions refresh(String token) throws Exception {
		return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + token + "\"}"));
	}

	@Test
	void refresh_rotates_newPairIssued_oldTokenRevoked() throws Exception {
		String rt1 = loginAndGetRefreshToken();

		String json = refresh(rt1).andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		String rt2 = JsonPath.read(json, "$.refreshToken");
		assertThat(rt2).isNotEqualTo(rt1);

		RefreshToken old = refreshTokenRepository.findByTokenHash(tokenCodec.hash(rt1)).orElseThrow();
		assertThat(old.getRevokedAt()).isNotNull();
		assertThat(old.getReplacedBy()).isNotNull();
	}

	@Test
	void refresh_reuseAfterGrace_revokesEntireChain_persisted() throws Exception {
		String rt1 = loginAndGetRefreshToken();
		String rt2 = JsonPath.read(
				refresh(rt1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.refreshToken");

		// Ép token cũ "bị thu hồi từ lâu" để vượt khoảng ân hạn.
		RefreshToken old = refreshTokenRepository.findByTokenHash(tokenCodec.hash(rt1)).orElseThrow();
		old.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS));
		refreshTokenRepository.save(old);

		refresh(rt1).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_REUSED"));

		// rt2 cũng bị thu hồi theo (toàn bộ phiên) — kiểm tra đã persist
		RefreshToken chained = refreshTokenRepository.findByTokenHash(tokenCodec.hash(rt2)).orElseThrow();
		assertThat(chained.getRevokedAt()).isNotNull();
		refresh(rt2).andExpect(status().isUnauthorized());
	}

	@Test
	void refresh_retryWithinGrace_isRejected_withoutRevokingChain() throws Exception {
		String rt1 = loginAndGetRefreshToken();
		String rt2 = JsonPath.read(
				refresh(rt1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.refreshToken");

		// Dùng lại rt1 ngay (trong ân hạn) -> chỉ từ chối, KHÔNG thu hồi rt2
		refresh(rt1).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));

		refresh(rt2).andExpect(status().isOk());
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
		refreshTokenRepository.save(expired);

		refresh(raw).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_EXPIRED"));
	}
}
