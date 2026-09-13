package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.vmarket.auth.config.AuthEmailProperties;
import com.vmarket.auth.email.BrevoEmailSender;
import com.vmarket.auth.email.EmailMessage;
import com.vmarket.auth.exception.ApiException;

class BrevoEmailSenderTest {

	private final EmailMessage MSG = new EmailMessage("u@example.com", "Mã: 123456", "<b>123456</b>", "123456");

	private BrevoEmailSender newSender(RestClient.Builder builder) {
		AuthEmailProperties props = new AuthEmailProperties();
		props.setProvider("brevo");
		props.setFrom("no-reply@vmarket.local");
		props.setFromName("VMarket");
		props.getBrevo().setApiKey("test-key");
		return new BrevoEmailSender(props, builder);
	}

	@Test
	void postsExpectedRequestToBrevo() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo(BrevoEmailSender.SEND_URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("api-key", "test-key"))
				.andExpect(jsonPath("$.to[0].email").value("u@example.com"))
				.andExpect(jsonPath("$.sender.email").value("no-reply@vmarket.local"))
				.andExpect(jsonPath("$.subject").value("Mã: 123456"))
				.andRespond(withSuccess("{\"messageId\":\"<x>\"}", MediaType.APPLICATION_JSON));

		newSender(builder).send(MSG);
		server.verify();
	}

	@Test
	void mapsProviderErrorTo_EMAIL_SEND_FAILED() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo(BrevoEmailSender.SEND_URL)).andRespond(withServerError());

		BrevoEmailSender sender = newSender(builder);
		assertThatThrownBy(() -> sender.send(MSG))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo("EMAIL_SEND_FAILED"));
	}

	@Test
	void failsFast_whenApiKeyMissing() {
		AuthEmailProperties props = new AuthEmailProperties();
		props.setProvider("brevo");
		assertThatThrownBy(() -> new BrevoEmailSender(props, RestClient.builder()))
				.isInstanceOf(IllegalStateException.class);
	}
}
