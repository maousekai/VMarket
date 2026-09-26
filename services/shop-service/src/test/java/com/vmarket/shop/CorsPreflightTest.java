package com.vmarket.shop;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Preflight CORS trên endpoint cần đăng nhập. Bản skeleton cũ khai bean
 * {@code CorsFilter} — sau khi thêm Spring Security, kiểu đó khiến preflight
 * {@code OPTIONS /api/shops/me} bị đẩy xuống bước kiểm tra quyền và trả 401 (lỗi
 * user-service từng gặp). Preflight không mang {@code Authorization}, nên phải qua
 * được chuỗi filter mà không cần token.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsPreflightTest {

	/** Khớp {@code app.cors.allowed-origins} của {@code src/test/resources/application.yml}. */
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";

	@Autowired MockMvc mockMvc;

	@Test
	void preflight_endpointCanDangNhap_originDuocPhep_tra200_khongCanToken() throws Exception {
		mockMvc.perform(options("/api/shops/me")
						.header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
	}

	@Test
	void preflight_originLa_biChan403() throws Exception {
		mockMvc.perform(options("/api/shops/me")
						.header(HttpHeaders.ORIGIN, "https://evil.example")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
				.andExpect(status().isForbidden());
	}
}
