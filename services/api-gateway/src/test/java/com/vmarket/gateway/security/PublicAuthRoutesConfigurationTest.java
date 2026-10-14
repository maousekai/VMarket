package com.vmarket.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.vmarket.gateway.config.GatewayJwtProperties;
import com.vmarket.gateway.config.GatewaySecurityProperties;

import jakarta.servlet.http.HttpServletRequest;

/** Exercises the actual deployed allowlist, rather than a copy in the test. */
class PublicAuthRoutesConfigurationTest {

	private GatewaySecurityProperties security;
	private JwtAuthenticationFilter filter;

	@BeforeEach
	void loadDeployedConfiguration() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		for (var source : new YamlPropertySourceLoader().load("gateway", new ClassPathResource("application.yml"))) {
			environment.getPropertySources().addFirst(source);
		}
		security = Binder.get(environment).bind("app.security", Bindable.of(GatewaySecurityProperties.class)).get();
		GatewayJwtProperties jwt = new GatewayJwtProperties();
		jwt.setSecret("test-only-secret-0123456789-0123456789-0123456789");
		filter = new JwtAuthenticationFilter(new JwtService(jwt), security);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"/api/auth/otp/request", "/api/auth/otp/verify",
			"/api/auth/password/forgot", "/api/auth/password/reset"
	})
	void guestCanReachExactRecoveryEndpointWithoutForwardingSpoofedIdentity(String path) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
		request.addHeader("X-User-Id", "spoofed");
		request.addHeader("X-User-Roles", "ADMIN");
		AtomicBoolean reached = new AtomicBoolean();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, (incoming, outgoing) -> {
			reached.set(true);
			HttpServletRequest forwarded = (HttpServletRequest) incoming;
			assertThat(forwarded.getHeader("X-User-Id")).isNull();
			assertThat(forwarded.getHeader("X-User-Roles")).isNull();
		});
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(reached).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"/api/auth/otp/request/extra", "/api/auth/otp/verify/extra",
			"/api/auth/password/forgot/extra", "/api/auth/password/reset/extra",
			"/api/auth/me", "/api/auth/password/change", "/api/auth/internal/users"
	})
	void otherAuthEndpointsStillRequireBearerToken(String path) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(new MockHttpServletRequest("POST", path), response, new MockFilterChain());
		assertThat(response.getStatus()).isEqualTo(401);
	}

	@Test
	void noBroadAuthWildcardIsIntroduced() {
		assertThat(security.getPublicPaths()).doesNotContain("/api/auth/**", "/api/auth/otp/**", "/api/auth/password/**");
		assertThat(security.getPublicPaths()).containsAll(List.of(
				"/api/auth/otp/request", "/api/auth/otp/verify", "/api/auth/password/forgot", "/api/auth/password/reset"));
	}
}
