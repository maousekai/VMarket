package com.vmarket.cart.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.vmarket.cart.security.CartAuthenticationFilter;

/** Route công khai chỉ nhận danh tính từ access token (PBL6-50). */
class SecurityConfigTest {

	@Test
	void internalIdentity_isNotAUserAuthentication() {
		var internal = new UsernamePasswordAuthenticationToken("user-1", null,
				List.of(new SimpleGrantedAuthority(CartAuthenticationFilter.INTERNAL_AUTHORITY)));
		assertThat(SecurityConfig.isUserAuthentication(internal)).isFalse();
	}

	@Test
	void tokenIdentity_isAUserAuthentication() {
		var user = new UsernamePasswordAuthenticationToken("user-1", null,
				List.of(new SimpleGrantedAuthority("ROLE_BUYER")));
		assertThat(SecurityConfig.isUserAuthentication(user)).isTrue();
	}

	@Test
	void anonymousOrMissing_isRejected() {
		var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
				List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
		assertThat(SecurityConfig.isUserAuthentication(anonymous)).isFalse();
		assertThat(SecurityConfig.isUserAuthentication(null)).isFalse();
	}
}
