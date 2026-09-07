package com.vmarket.user;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.vmarket.user.entity.Gender;
import com.vmarket.user.entity.UserProfile;
import com.vmarket.user.repository.AddressRepository;
import com.vmarket.user.repository.UserProfileRepository;

/**
 * FR-USER-01 — hồ sơ cá nhân.
 *
 * <p>Cố ý <b>không</b> dùng {@code @Transactional} trên lớp test như
 * {@code RegistrationApiTest} của auth-service: hồ sơ được tạo trong transaction
 * riêng ({@code REQUIRES_NEW}, xem {@code UserProfileProvisioner}) nên nó commit
 * độc lập và sẽ KHÔNG bị rollback theo transaction của test — dữ liệu rò từ test
 * này sang test khác. Dọn tay ở {@code @BeforeEach} là cách trung thực ở đây.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileApiTest {

	private static final String USER_A = "01JBQ9YDX7K3M8N5P2R4T6V8W0";

	@Autowired MockMvc mockMvc;
	@Autowired UserProfileRepository userProfileRepository;
	@Autowired AddressRepository addressRepository;
	@Autowired UserJwtProperties jwtProperties;

	@BeforeEach
	void clean() {
		addressRepository.deleteAll();
		userProfileRepository.deleteAll();
	}

	private String tokenFor(String userId) {
		return TestTokens.bearer(TestTokens.accessToken(jwtProperties.getSecret(), userId, List.of("BUYER")));
	}

	@Test
	void getProfile_khongCoToken_tra401_voiBodyLoiChuan() throws Exception {
		mockMvc.perform(get("/api/users/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void getProfile_tokenHetHan_tra401() throws Exception {
		String expired = TestTokens.bearer(TestTokens.expiredToken(jwtProperties.getSecret(), USER_A));

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, expired))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void getProfile_tokenSaiChuKy_tra401() throws Exception {
		String foreign = TestTokens.bearer(TestTokens.accessToken(
				"mot-khoa-hoan-toan-khac-0123456789-0123456789", USER_A, List.of("BUYER")));

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, foreign))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void getProfile_lanDauTuTaoHoSoRong_khongTra404() throws Exception {
		assertThat(userProfileRepository.findByUserId(USER_A)).isEmpty();

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(USER_A))
				.andExpect(jsonPath("$.fullName").doesNotExist());

		assertThat(userProfileRepository.findByUserId(USER_A)).isPresent();
	}

	@Test
	void getProfile_goiHaiLan_khongTaoTrungHoSo() throws Exception {
		String token = tokenFor(USER_A);

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isOk());

		assertThat(userProfileRepository.count()).isEqualTo(1);
	}

	@Test
	void updateProfile_luuDuCacTruong() throws Exception {
		String body = """
				{"fullName":"Nguyễn Văn An","phone":"0912345678",
				 "dateOfBirth":"2003-05-17","gender":"MALE",
				 "avatarUrl":"https://cdn.vmarket.vn/a.jpg"}
				""";

		mockMvc.perform(put("/api/users/me")
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fullName").value("Nguyễn Văn An"))
				.andExpect(jsonPath("$.gender").value("MALE"));

		UserProfile saved = userProfileRepository.findByUserId(USER_A).orElseThrow();
		assertThat(saved.getPhone()).isEqualTo("0912345678");
		assertThat(saved.getGender()).isEqualTo(Gender.MALE);
	}

	@Test
	void updateProfile_laThayTheToanBo_truongKhongGuiSeBiXoa() throws Exception {
		String token = tokenFor(USER_A);
		mockMvc.perform(put("/api/users/me").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"fullName":"Nguyễn Văn An","phone":"0912345678"}
						"""))
				.andExpect(status().isOk());

		// Lần hai chỉ gửi họ tên -> số điện thoại phải bị xoá, đúng ngữ nghĩa PUT đã
		// ghi trong javadoc của UpdateProfileRequest. Test này khoá lại hành vi đó để
		// không ai âm thầm đổi sang kiểu vá từng trường mà quên sửa tài liệu.
		mockMvc.perform(put("/api/users/me").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"fullName":"Nguyễn Văn Bình"}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fullName").value("Nguyễn Văn Bình"))
				.andExpect(jsonPath("$.phone").doesNotExist());
	}

	/**
	 * Khoá lại lỗi đã gặp: {@code UNDISCLOSED} dài 11 ký tự nên cột
	 * {@code VARCHAR(10)} ban đầu không chứa nổi. Không có test này thì lỗi chỉ lộ
	 * ra khi có người dùng thật chọn "không tiết lộ" trên PostgreSQL.
	 */
	@Test
	void updateProfile_gioiTinhUndisclosed_luuDuocKhongTranCot() throws Exception {
		mockMvc.perform(put("/api/users/me").header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"gender":"UNDISCLOSED"}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.gender").value("UNDISCLOSED"));

		assertThat(userProfileRepository.findByUserId(USER_A).orElseThrow().getGender())
				.isEqualTo(Gender.UNDISCLOSED);
	}

	@Test
	void updateProfile_soDienThoaiSaiDinhDang_tra400() throws Exception {
		mockMvc.perform(put("/api/users/me").header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"phone":"12345"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.details[0].field").value("phone"));
	}

	@Test
	void updateProfile_ngaySinhTuongLai_tra400() throws Exception {
		mockMvc.perform(put("/api/users/me").header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"dateOfBirth":"3000-01-01"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void hoSoCuaHaiNguoiDungTachBiet() throws Exception {
		String userB = "01JBQ9YDX7K3M8N5P2R4T6V8W1";

		mockMvc.perform(put("/api/users/me").header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"fullName":"Người A"}
						"""))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, tokenFor(userB)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(userB))
				.andExpect(jsonPath("$.fullName").doesNotExist());
	}
}
