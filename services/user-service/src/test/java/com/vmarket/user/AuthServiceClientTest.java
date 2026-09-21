package com.vmarket.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.vmarket.user.client.AuthServiceClient;
import com.vmarket.user.config.AuthServiceProperties;
import com.vmarket.user.exception.ApiException;

/**
 * Hợp đồng HTTP giữa user-service và API nội bộ auth-service: đúng
 * path/method/header/body, và ánh xạ lỗi (xem javadoc {@link AuthServiceClient}).
 *
 * <p>Không cần Spring context — dựng client bằng đúng
 * {@link AuthServiceClient#buildRestClient} mà cấu hình thật dùng, chỉ thay request
 * factory bằng {@link MockRestServiceServer}. Nhờ vậy test bắt được cả lỗi ở chính
 * phần dựng client (base URL, header khoá nội bộ), không riêng phần gọi.
 */
class AuthServiceClientTest {

	private static final String BASE = "http://auth-service.test:8081";
	private static final String KEY = "test-only-internal-api-key-0123456789-0123456789";
	private static final String USER = "01JBQ9YDX7K3M8N5P2R4T6V8BB";
	private static final String PASSWORD_URI = BASE + "/internal/users/" + USER + "/password";

	private MockRestServiceServer server;
	private AuthServiceClient client;

	@BeforeEach
	void setUp() {
		AuthServiceProperties props = new AuthServiceProperties();
		props.setBaseUrl(BASE);
		props.setInternalApiKey(KEY);

		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new AuthServiceClient(AuthServiceClient.buildRestClient(builder, props));
	}

	@Test
	void changePassword_guiDungPathHeaderBody() {
		server.expect(requestTo(PASSWORD_URI))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(header(AuthServiceProperties.INTERNAL_API_KEY_HEADER, KEY))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.currentPassword").value("Abcd1234@"))
				.andExpect(jsonPath("$.newPassword").value("Xyz98765#"))
				.andRespond(withNoContent());

		client.changePassword(USER, "Abcd1234@", "Xyz98765#");

		server.verify();
	}

	/** Lỗi nghiệp vụ là câu trả lời đúng cho người dùng → chuyển tiếp nguyên mã. */
	@Test
	void loiNghiepVu4xx_chuyenTiepNguyenMaVaStatus() {
		server.expect(requestTo(PASSWORD_URI))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":{\"code\":\"INVALID_CURRENT_PASSWORD\",\"message\":\"Mật khẩu hiện tại không đúng\"}}"));

		assertThatThrownBy(() -> client.changePassword(USER, "Wrong123@", "Xyz98765#"))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo("INVALID_CURRENT_PASSWORD");
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
					assertThat(ex.getMessage()).isEqualTo("Mật khẩu hiện tại không đúng");
				});
	}

	@Test
	void loi423_chuyenTiep() {
		server.expect(requestTo(PASSWORD_URI))
				.andRespond(withStatus(HttpStatus.LOCKED).contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":{\"code\":\"ACCOUNT_LOCKED\",\"message\":\"Tài khoản tạm khoá\"}}"));

		assertThatThrownBy(() -> client.changePassword(USER, "Abcd1234@", "Xyz98765#"))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo("ACCOUNT_LOCKED");
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.LOCKED);
				});
	}

	/**
	 * 401 từ auth-service là lỗi cấu hình {@code INTERNAL_API_KEY} giữa hai service,
	 * KHÔNG phải token của người dùng hết hạn. Chuyển tiếp 401 sẽ khiến frontend đăng
	 * xuất người dùng vì một sự cố họ không liên quan.
	 */
	@Test
	void authServiceTuChoiKhoaNoiBo401_khongChuyenTiep401_ma502() {
		server.expect(requestTo(PASSWORD_URI))
				.andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Thiếu hoặc sai thông tin xác thực\"}}"));

		assertThatThrownBy(() -> client.changePassword(USER, "Abcd1234@", "Xyz98765#"))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo("AUTH_SERVICE_ERROR");
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
				});
	}

	@Test
	void loi4xxKhongCoBodyChuan_502() {
		server.expect(requestTo(PASSWORD_URI))
				.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML).body("<html>404</html>"));

		assertThatThrownBy(() -> client.changePassword(USER, "Abcd1234@", "Xyz98765#"))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo("AUTH_SERVICE_ERROR"));
	}

	@Test
	void loi5xx_502() {
		server.expect(requestTo(PASSWORD_URI)).andRespond(withServerError());

		assertThatThrownBy(() -> client.changePassword(USER, "Abcd1234@", "Xyz98765#"))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo("AUTH_SERVICE_ERROR");
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
				});
	}

	@Test
	void khongKetNoiDuoc_503() {
		server.expect(requestTo(PASSWORD_URI))
				.andRespond(withException(new ConnectException("Connection refused")));

		assertThatThrownBy(() -> client.changePassword(USER, "Abcd1234@", "Xyz98765#"))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo("AUTH_SERVICE_UNAVAILABLE");
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
				});
	}

	@Test
	void saiHostname_503() {
		server.expect(requestTo(PASSWORD_URI))
				.andRespond(withException(new UnknownHostException("auth-service.test")));

		assertThatThrownBy(() -> client.changePassword(USER, "Abcd1234@", "Xyz98765#"))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo("AUTH_SERVICE_UNAVAILABLE"));
	}

	/**
	 * Hết thời gian KẾT NỐI: chưa byte nào tới auth-service nên thao tác chắc chắn
	 * chưa chạy — bảo người dùng thử lại là đúng.
	 */
	@Test
	void hetThoiGianKetNoi_503_vaLoiKhuyenLaThuLai() {
		server.expect(requestTo(PASSWORD_URI))
				.andRespond(withException(new HttpConnectTimeoutException("HTTP connect timed out")));

		assertThatThrownBy(() -> client.changePassword(USER, "Abcd1234@", "Xyz98765#"))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo("AUTH_SERVICE_UNAVAILABLE");
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
				});
	}

	/**
	 * Hết thời gian ĐỌC là chuyện khác hẳn: auth-service ĐÃ nhận request và có thể đã
	 * chạy xong (mật khẩu đã đổi, mọi phiên đã bị thu hồi). Trả 503 "vui lòng thử lại
	 * sau" ở đây đẩy người dùng vào bẫy — họ gửi lại bằng mật khẩu cũ →
	 * INVALID_CURRENT_PASSWORD → bộ đếm khoá tăng → khoá 15 phút dù không làm gì sai.
	 */
	@Test
	void hetThoiGianDoc_504_vaKhongXuiThuLai() {
		server.expect(requestTo(PASSWORD_URI))
				.andRespond(withException(new SocketTimeoutException("Read timed out")));

		assertThatThrownBy(() -> client.changePassword(USER, "Abcd1234@", "Xyz98765#"))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo("PASSWORD_CHANGE_UNKNOWN");
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
					assertThat(ex.getMessage())
							.as("thông báo phải hướng người dùng sang ĐĂNG NHẬP bằng mật khẩu mới, "
									+ "không được có chữ nào rủ họ gửi lại request")
							.contains("mật khẩu MỚI")
							.doesNotContain("thử lại sau");
				});
	}

	/** Đứt kết nối giữa chừng cũng là "không rõ kết quả", không phải "chắc chắn hỏng". */
	@Test
	void dutKetNoiGiuaChung_504() {
		server.expect(requestTo(PASSWORD_URI))
				.andRespond(withException(new java.io.IOException("Connection reset")));

		assertThatThrownBy(() -> client.changePassword(USER, "Abcd1234@", "Xyz98765#"))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo("PASSWORD_CHANGE_UNKNOWN"));
	}

	/**
	 * {@code userId} được mã hoá khi ghép vào path nên không đổi được endpoint đích.
	 * {@code userId} luôn đến từ claim {@code sub} đã verify, nhưng một biến URI không
	 * mã hoá là thứ dễ vỡ về sau nếu có chỗ khác truyền giá trị khác vào.
	 */
	@Test
	void userIdCoKyTuDacBiet_duocMaHoaTrenPath_khongDoiDuocEndpoint() {
		server.expect(requestTo(BASE + "/internal/users/..%2Fsearch/password"))
				.andRespond(withNoContent());

		client.changePassword("../search", "Abcd1234@", "Xyz98765#");

		server.verify();
	}
}
