package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.vmarket.product.exception.ApiException;
import com.vmarket.product.security.RequestIdentity;

class RequestIdentityTest {
	private final RequestIdentity identity = new RequestIdentity();

	@Test
	void acceptsGatewayRoleFormats() {
		assertThat(identity.requireSeller("user-1", "BUYER,ROLE_SELLER")).isEqualTo("user-1");
		identity.requireAdmin("ADMIN");
	}

	@Test
	void rejectsMissingRole() {
		assertThatThrownBy(() -> identity.requireSeller("user-1", "BUYER"))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("không có quyền");
	}
}
