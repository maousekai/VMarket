package com.vmarket.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.user.config.UserJwtProperties;
import com.vmarket.user.repository.AddressRepository;
import com.vmarket.user.repository.UserProfileRepository;

/**
 * FR-USER-04 (phần thuộc user-service) — Admin tìm kiếm / liệt kê hồ sơ.
 *
 * <p>Khoá/mở khoá tài khoản không nằm ở service này: trạng thái khoá thuộc CSDL
 * của auth-service. Xem javadoc {@code AdminUserController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserSearchApiTest {

	private static final String ADMIN = "01JBQ9YDX7K3M8N5P2R4T6V8AA";
	private static final String BUYER = "01JBQ9YDX7K3M8N5P2R4T6V8BB";

	@Autowired MockMvc mockMvc;
	@Autowired UserProfileRepository userProfileRepository;
	@Autowired AddressRepository addressRepository;
	@Autowired UserJwtProperties jwtProperties;

	@BeforeEach
	void clean() {
		addressRepository.deleteAll();
		userProfileRepository.deleteAll();
	}

	private String token(String userId, String... roles) {
		return TestTokens.bearer(TestTokens.accessToken(jwtProperties.getSecret(), userId, List.of(roles)));
	}

	/** Tạo hồ sơ có dữ liệu bằng chính API của người dùng đó. */
	private void seedProfile(String userId, String fullName, String phone) throws Exception {
		mockMvc.perform(put("/api/users/me")
				.header(HttpHeaders.AUTHORIZATION, token(userId, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"fullName":"%s","phone":"%s"}
						""".formatted(fullName, phone)))
				.andExpect(status().isOk());
	}

	@Test
	void khongCoToken_tra401() throws Exception {
		mockMvc.perform(get("/api/users"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void nguoiDungThuong_tra403_voiBodyLoiChuan() throws Exception {
		mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, token(BUYER, "BUYER")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void admin_lietKeDuocTatCa() throws Exception {
		seedProfile(BUYER, "Nguyễn Văn An", "0912345678");
		seedProfile("01JBQ9YDX7K3M8N5P2R4T6V8CC", "Trần Thị Bình", "0987654321");

		mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, token(ADMIN, "ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.page").value(0));
	}

	@Test
	void admin_timTheoHoTen_khongPhanBietHoaThuong() throws Exception {
		seedProfile(BUYER, "Nguyễn Văn An", "0912345678");
		seedProfile("01JBQ9YDX7K3M8N5P2R4T6V8CC", "Trần Thị Bình", "0987654321");

		mockMvc.perform(get("/api/users").param("q", "trần thị")
				.header(HttpHeaders.AUTHORIZATION, token(ADMIN, "ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].fullName").value("Trần Thị Bình"));
	}

	@Test
	void admin_timTheoSoDienThoai() throws Exception {
		seedProfile(BUYER, "Nguyễn Văn An", "0912345678");

		mockMvc.perform(get("/api/users").param("q", "091234")
				.header(HttpHeaders.AUTHORIZATION, token(ADMIN, "ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void admin_khongTimThay_traDanhSachRong_khongPhaiLoi() throws Exception {
		seedProfile(BUYER, "Nguyễn Văn An", "0912345678");

		mockMvc.perform(get("/api/users").param("q", "khong-ton-tai-dau")
				.header(HttpHeaders.AUTHORIZATION, token(ADMIN, "ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0))
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void admin_thamSoPhanTrangSai_tra400_khongPhai500() throws Exception {
		mockMvc.perform(get("/api/users").param("page", "-1")
				.header(HttpHeaders.AUTHORIZATION, token(ADMIN, "ADMIN")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void admin_sizeVuotTran_tra400() throws Exception {
		mockMvc.perform(get("/api/users").param("size", "1000")
				.header(HttpHeaders.AUTHORIZATION, token(ADMIN, "ADMIN")))
				.andExpect(status().isBadRequest());
	}
}
