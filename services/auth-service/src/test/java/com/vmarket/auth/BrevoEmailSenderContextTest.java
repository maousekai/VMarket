package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

	private ServerSocket silent;

	@AfterEach
	void closeSilentServer() throws IOException {
		// Đóng listener cũng reset kết nối đang chờ trong backlog, nên request còn treo
		// (khi test bị @Timeout cắt ngang) sẽ thoát ra thay vì giữ thread mãi.
		if (silent != null) {
			silent.close();
		}
	}

	@Test
	void providerBrevo_contextKhoiDongDuoc() {
		assertThat(emailSender).isInstanceOf(BrevoEmailSender.class);
	}

	/**
	 * Builder BrevoEmailSender nhận phải mang read-timeout từ spring.http.clients.*:
	 * server nhận kết nối nhưng không bao giờ trả lời thì request phải dừng vì timeout.
	 *
	 * <p>Bind và gọi cùng một địa chỉ IPv4 cụ thể: getLoopbackAddress() có thể trả ::1
	 * trên JVM ưu tiên IPv6, khi đó gọi 127.0.0.1 bị từ chối kết nối ngay và test pass
	 * mà không hề kiểm tra timeout. Vì vậy cũng phải kiểm tra nguyên nhân là
	 * HttpTimeoutException (lỗi read-timeout của JDK HttpClient), không phải
	 * ConnectException. @Timeout là deadline riêng: nếu timeout không được áp dụng thì
	 * request treo vô hạn và kiểm tra thời gian bên dưới không bao giờ chạy tới.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	void builderTuCauHinh_apDungReadTimeout() throws Exception {
		InetAddress ipv4Loopback = InetAddress.getByName("127.0.0.1");
		silent = new ServerSocket(0, 1, ipv4Loopback);
		RestClient client = restClientBuilder
				.baseUrl("http://" + ipv4Loopback.getHostAddress() + ":" + silent.getLocalPort())
				.build();

		long start = System.nanoTime();
		Throwable thrown = catchThrowable(() -> client.get().retrieve().toBodilessEntity());
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(thrown).isInstanceOf(ResourceAccessException.class);
		assertThat(causeChain(thrown)).anyMatch(HttpTimeoutException.class::isInstance);
		assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
	}

	private static List<Throwable> causeChain(Throwable thrown) {
		List<Throwable> chain = new ArrayList<>();
		for (Throwable t = thrown; t != null && !chain.contains(t); t = t.getCause()) {
			chain.add(t);
		}
		return chain;
	}
}
