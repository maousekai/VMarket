package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.vmarket.auth.email.BrevoEmailSender;
import com.vmarket.auth.email.EmailSender;

/**
 * PBL6-66: provider=brevo phải dựng được context thật. BrevoEmailSenderTest tự tạo
 * RestClient.builder() nên không bắt được việc thiếu bean RestClient.Builder
 * (Boot 4 tách auto-configuration RestClient ra module spring-boot-restclient).
 */
@SpringBootTest(properties = {
		"auth.email.provider=brevo",
		"auth.email.brevo.api-key=test-key",
		"spring.http.clients.read-timeout=300ms"
})
class BrevoEmailSenderContextTest {

	@Autowired
	private EmailSender emailSender;

	@Autowired
	private RestClient.Builder restClientBuilder;

	@Test
	void providerBrevo_contextKhoiDongDuoc() {
		assertThat(emailSender).isInstanceOf(BrevoEmailSender.class);
	}

	/**
	 * Builder BrevoEmailSender nhận phải mang read-timeout từ spring.http.clients.*:
	 * server nhận kết nối nhưng không bao giờ trả lời thì request phải dừng, không treo.
	 */
	@Test
	void builderTuCauHinh_apDungReadTimeout() throws Exception {
		try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			RestClient client = restClientBuilder
					.baseUrl("http://127.0.0.1:" + silent.getLocalPort())
					.build();
			long start = System.nanoTime();
			assertThatThrownBy(() -> client.get().retrieve().toBodilessEntity())
					.isInstanceOf(ResourceAccessException.class);
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
		}
	}
}
