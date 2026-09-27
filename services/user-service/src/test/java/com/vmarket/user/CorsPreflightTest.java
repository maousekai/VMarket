package com.vmarket.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Preflight CORS trên endpoint cần đăng nhập.
 *
 * <p>Tồn tại vì một lỗi thật: {@code CorsConfig} từng khai bean {@code CorsFilter}
 * thay vì {@link CorsConfigurationSource}. Spring Security nạp CORS qua
 * {@code http.cors(...)} và cần đúng một bean {@code CorsConfigurationSource}; thiếu
 * nó thì nó tự tiêm {@code HandlerMappingIntrospector} mặc định — không có rule nào
 * từ {@code app.cors.allowed-origins} — nên {@code OPTIONS /api/users/me} không được
 * nhận là preflight, bị đẩy xuống bước kiểm tra quyền và trả <b>401</b>. Frontend
 * gọi trực tiếp cổng 8082 sẽ hỏng, còn qua gateway thì vẫn chạy nên lỗi rất dễ lọt.
 *
 * <p>Điểm mấu chốt: preflight <b>không mang</b> header {@code Authorization} (trình
 * duyệt cố tình không gửi), nên nó phải qua được chuỗi filter mà không cần token.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsPreflightTest {

	/** Khớp {@code app.cors.allowed-origins} của {@code src/test/resources/application.yml}. */
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";

	@Autowired MockMvc mockMvc;

	@Test
	void preflight_originDuocPhep_tra200_khongCanToken() throws Exception {
		mockMvc.perform(options("/api/users/me")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
	}

	@Test
	void preflight_phuongThucGhi_cungDuocPhep() throws Exception {
		mockMvc.perform(options("/api/users/me")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
	}

	/**
	 * Origin lạ bị chặn ngay ở tầng CORS (403), <b>không</b> phải 401: cấu hình thật
	 * sự có hiệu lực chứ không phải "mở cho tất cả".
	 */
	@Test
	void preflight_originLa_biChan() throws Exception {
		mockMvc.perform(options("/api/users/me")
						.header(HttpHeaders.ORIGIN, "http://evil.example.com")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	/** Request thật (không phải preflight) vẫn phải có token — CORS không mở cửa xác thực. */
	@Test
	void requestThat_khongCoToken_van401_duCoOriginDuocPhep() throws Exception {
		mockMvc.perform(get("/api/users/me")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
				.andExpect(status().isUnauthorized());
	}
}
