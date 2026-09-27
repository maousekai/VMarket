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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.vmarket.user.client.AuthAccount;
import com.vmarket.user.client.AuthAccountActivityPage;
import com.vmarket.user.client.AuthAccountPage;
import com.vmarket.user.client.AuthAccountSearch;
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
	private static final String ACCOUNT_URI = BASE + "/internal/users/" + USER;
	private static final String ADMIN = "01JBQ9YDX7K3M8N5P2R4T6V8AA";

	private static final String ACCOUNT_JSON = """
			{"userId":"%s","email":"an@example.com","username":"an","roles":["BUYER"],
			 "emailVerified":true,"suspended":true,"suspendedAt":"2026-09-14T10:00:00Z",
			 "suspendedReason":"Spam","suspendedBy":"%s",
			 "lockedUntil":null,"createdAt":"2026-09-01T00:00:00Z"}
			""".formatted(USER, ADMIN);

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

	@Test
	void getAccount_docDungResponse() {
		server.expect(requestTo(ACCOUNT_URI))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(AuthServiceProperties.INTERNAL_API_KEY_HEADER, KEY))
				.andExpect(header(AuthServiceProperties.ACTOR_ID_HEADER, ADMIN))
				.andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));

		AuthAccount account = client.getAccount(USER, ADMIN);

		assertThat(account.userId()).isEqualTo(USER);
		assertThat(account.suspended()).isTrue();
		assertThat(account.suspendedAt()).isEqualTo(Instant.parse("2026-09-14T10:00:00Z"));
		assertThat(account.suspendedBy()).isEqualTo(ADMIN);
		assertThat(account.roles()).containsExactly("BUYER");
		server.verify();
	}

	@Test
	void suspend_guiDungPathHeaderBody_adminDiTrongHeader() {
		server.expect(requestTo(ACCOUNT_URI + "/suspension"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(header(AuthServiceProperties.INTERNAL_API_KEY_HEADER, KEY))
				.andExpect(header(AuthServiceProperties.ACTOR_ID_HEADER, ADMIN))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.reason").value("Spam"))
				.andExpect(jsonPath("$.actorId").doesNotExist())
				.andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));

		AuthAccount account = client.suspend(USER, "Spam", ADMIN);

		assertThat(account.suspended()).isTrue();
		server.verify();
	}

	@Test
	void unsuspend_dungMethodDelete_guiAdminThucHien() {
		server.expect(requestTo(ACCOUNT_URI + "/suspension"))
				.andExpect(method(HttpMethod.DELETE))
				.andExpect(header(AuthServiceProperties.INTERNAL_API_KEY_HEADER, KEY))
				.andExpect(header(AuthServiceProperties.ACTOR_ID_HEADER, ADMIN))
				.andRespond(withSuccess(ACCOUNT_JSON, MediaType.APPLICATION_JSON));

		client.unsuspend(USER, ADMIN);

		server.verify();
	}

	@Test
	void searchAccounts_guiDieuKien_docTrang() {
		server.expect(requestTo(BASE + "/internal/users/search"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(AuthServiceProperties.INTERNAL_API_KEY_HEADER, KEY))
				.andExpect(header(AuthServiceProperties.ACTOR_ID_HEADER, ADMIN))
				.andExpect(jsonPath("$.q").value("an"))
				.andExpect(jsonPath("$.status").value("SUSPENDED"))
				.andExpect(jsonPath("$.userIds[0]").value(USER))
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(10))
				.andRespond(withSuccess("{\"items\":[" + ACCOUNT_JSON + "],\"page\":1,\"size\":10,"
						+ "\"totalElements\":11,\"totalPages\":2}", MediaType.APPLICATION_JSON));

		AuthAccountPage page = client.searchAccounts(new AuthAccountSearch("an", "SUSPENDED", List.of(USER), 1, 10), ADMIN);

		assertThat(page.items()).hasSize(1);
		assertThat(page.totalElements()).isEqualTo(11);
		assertThat(page.totalPages()).isEqualTo(2);
		server.verify();
	}

	@Test
	void getActivities_dungPathQueryHeader_docTrang() {
		server.expect(requestTo(ACCOUNT_URI + "/activities?page=1&size=5"))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header(AuthServiceProperties.INTERNAL_API_KEY_HEADER, KEY))
				.andExpect(header(AuthServiceProperties.ACTOR_ID_HEADER, ADMIN))
				.andRespond(withSuccess("""
						{"items":[{"id":"01JBQ9YDX7K3M8N5P2R4T6V8D0","action":"SUSPENDED","actorId":"%s",
						  "actorUsername":"admin","reason":"Spam","createdAt":"2026-09-14T10:00:00Z"}],
						 "page":1,"size":5,"totalElements":6,"totalPages":2}
						""".formatted(ADMIN), MediaType.APPLICATION_JSON));

		AuthAccountActivityPage page = client.getActivities(USER, 1, 5, ADMIN);

		assertThat(page.items()).hasSize(1);
		assertThat(page.items().get(0).action()).isEqualTo("SUSPENDED");
		assertThat(page.items().get(0).reason()).isEqualTo("Spam");
		assertThat(page.items().get(0).createdAt()).isEqualTo(Instant.parse("2026-09-14T10:00:00Z"));
		assertThat(page.totalElements()).isEqualTo(6);
		server.verify();
	}

	/**
	 * 403 {@code ADMIN_ACCESS_REVOKED}: auth-service xác nhận Admin gọi vào đã bị khoá /
	 * mất vai trò (FR-USER-04, review PR #22 M2) — câu trả lời đúng cho người gọi, chuyển
	 * tiếp nguyên 403 thay vì coi là lỗi cấu hình khoá nội bộ.
	 */
	@Test
	void loi403AdminAccessRevoked_chuyenTiep403() {
		server.expect(requestTo(ACCOUNT_URI + "/suspension"))
				.andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":{\"code\":\"ADMIN_ACCESS_REVOKED\",\"message\":\"Không còn quyền quản trị\"}}"));

		assertThatThrownBy(() -> client.unsuspend(USER, ADMIN))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo("ADMIN_ACCESS_REVOKED");
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
					assertThat(ex.getMessage()).isEqualTo("Không còn quyền quản trị");
				});
	}

	/** 403 khác (vd. khoá nội bộ không đủ quyền) vẫn là lỗi cấu hình → 502, không chuyển tiếp. */
	@Test
	void loi403Khac_van502() {
		server.expect(requestTo(ACCOUNT_URI))
				.andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Không có quyền\"}}"));

		assertThatThrownBy(() -> client.getAccount(USER, ADMIN))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo("AUTH_SERVICE_ERROR");
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
				});
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
