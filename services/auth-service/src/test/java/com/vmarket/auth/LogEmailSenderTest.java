package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.vmarket.auth.email.LogEmailSender;

class LogEmailSenderTest {

	@Test
	void failsFast_whenActiveProfileIsProd() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("prod");

		assertThatThrownBy(() -> new LogEmailSender(env)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void allowsConstruction_whenActiveProfileIsDev() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("dev");

		assertThatCode(() -> new LogEmailSender(env)).doesNotThrowAnyException();
	}
}
