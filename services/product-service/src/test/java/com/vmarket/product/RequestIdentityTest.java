package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.vmarket.product.exception.ApiException;
import com.vmarket.product.security.RequestIdentity;

class RequestIdentityTest {
	private final RequestIdentity identity = new RequestIdentity();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void returnsSellerIdFromVerifiedIdentity() {
		authenticate("user-1", "ROLE_BUYER", "ROLE_SELLER");
		assertThat(identity.requireSeller()).isEqualTo("user-1");
	}

	@Test
	void acceptsAdmin() {
		authenticate("admin-1", "ROLE_ADMIN");
		identity.requireAdmin();
	}

	@Test
	void rejectsMissingRoleWithForbidden() {
		authenticate("user-1", "ROLE_BUYER");
		assertThatThrownBy(identity::requireSeller)
				.isInstanceOf(ApiException.class)
				.extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void rejectsMissingIdentityWithUnauthorized() {
		assertThatThrownBy(identity::requireAdmin)
				.isInstanceOf(ApiException.class)
				.extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void rejectsAnonymousWithUnauthorized() {
		SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
				"key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
		assertThatThrownBy(identity::requireAdmin)
				.isInstanceOf(ApiException.class)
				.extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	private void authenticate(String userId, String... authorities) {
		var granted = java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(userId, null, granted));
	}
}
