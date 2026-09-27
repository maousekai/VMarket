package com.vmarket.user.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.vmarket.user.client.AuthServiceClient;

/**
 * Dựng {@link AuthServiceClient} với base URL, khoá nội bộ và timeout từ
 * {@link AuthServiceProperties}.
 *
 * <p><b>Dùng {@link JdkClientHttpRequestFactory}</b> (cũng là lựa chọn mặc định của
 * Spring Boot khi không có Apache/Jetty client trên classpath) chứ không phải
 * {@code SimpleClientHttpRequestFactory}: nó phân biệt được hai loại timeout bằng
 * <i>kiểu</i> exception — hết thời gian kết nối ném
 * {@code java.net.http.HttpConnectTimeoutException}, hết thời gian đọc ném
 * {@code HttpTimeoutException}. {@code SimpleClientHttpRequestFactory} ném
 * {@code SocketTimeoutException} cho cả hai, chỉ khác nhau ở chuỗi thông báo.
 *
 * <p>Phân biệt được hai loại đó là điều kiện để {@link AuthServiceClient} trả lời
 * đúng: chưa tới nơi thì "thử lại đi", đã tới nơi mà không rõ kết quả thì tuyệt đối
 * không được xui người dùng thử lại (xem javadoc bên đó).
 *
 * <p>Ghim HTTP/1.1: mặc định {@code HttpClient} thử nâng cấp lên h2c, mà Tomcat chỉ
 * bật h2c khi được cấu hình. Vẫn chạy được nhờ cơ chế fallback, nhưng ghim lại thì
 * đường đi của mọi request giống hệt nhau ở mọi môi trường.
 */
@Configuration
public class AuthServiceClientConfig {

	@Bean
	AuthServiceClient authServiceClient(AuthServiceProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(properties.getConnectTimeout())
				.build();

		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.getReadTimeout());

		RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
		return new AuthServiceClient(AuthServiceClient.buildRestClient(builder, properties));
	}
}
