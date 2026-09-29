package com.vmarket.order.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.vmarket.order.client.CartServiceClient;
import com.vmarket.order.client.UserServiceClient;

/**
 * Dựng hai client gọi service khác với timeout từ properties.
 *
 * <p><b>Dùng {@link JdkClientHttpRequestFactory}</b> (giống user-service): nó phân
 * biệt được hai loại timeout bằng <i>kiểu</i> exception — hết thời gian kết nối ném
 * {@code HttpConnectTimeoutException}, hết thời gian đọc ném
 * {@code HttpTimeoutException}. Điều kiện để client trả lời đúng "chưa tới nơi →
 * 503 thử lại được" hay "đã tới nơi nhưng không rõ kết quả → không xui người dùng
 * thử lại" (xem javadoc {@code CartServiceClient}).
 *
 * <p>Ghim HTTP/1.1: mặc định {@code HttpClient} thử nâng cấp lên h2c, mà Tomcat chỉ
 * bật h2c khi được cấu hình. Ghim lại để đường đi của mọi request giống nhau ở mọi
 * môi trường.
 */
@Configuration
public class DownstreamClientConfig {

	@Bean
	CartServiceClient cartServiceClient(CartServiceProperties properties) {
		return new CartServiceClient(builder(properties.getConnectTimeout(), properties.getReadTimeout())
				.baseUrl(properties.getBaseUrl())
				// Khoá nội bộ: cart-service chỉ tin header X-User-Id khi request kèm
				// khoá này (PBL6-16 — bịt lỗi mạo danh danh tính của PR #23).
				.defaultHeader(CartServiceProperties.INTERNAL_API_KEY_HEADER, properties.getInternalApiKey())
				.build());
	}

	@Bean
	UserServiceClient userServiceClient(UserServiceProperties properties) {
		return new UserServiceClient(builder(properties.getConnectTimeout(), properties.getReadTimeout())
				.baseUrl(properties.getBaseUrl())
				.build());
	}

	private static RestClient.Builder builder(Duration connectTimeout, Duration readTimeout) {
		HttpClient httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(connectTimeout)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(readTimeout);
		return RestClient.builder().requestFactory(requestFactory);
	}
}