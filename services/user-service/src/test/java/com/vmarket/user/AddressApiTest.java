package com.vmarket.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.vmarket.user.config.UserJwtProperties;
import com.vmarket.user.entity.Address;
import com.vmarket.user.repository.AddressRepository;
import com.vmarket.user.repository.UserProfileRepository;

/** FR-USER-02 — sổ địa chỉ: CRUD và bất biến "địa chỉ mặc định". */
@SpringBootTest
@AutoConfigureMockMvc
class AddressApiTest {

	private static final String USER_A = "01JBQ9YDX7K3M8N5P2R4T6V8W0";
	private static final String USER_B = "01JBQ9YDX7K3M8N5P2R4T6V8W1";

	@Autowired MockMvc mockMvc;
	@Autowired AddressRepository addressRepository;
	@Autowired UserProfileRepository userProfileRepository;
	@Autowired UserJwtProperties jwtProperties;
	@Autowired ObjectMapper objectMapper;

	@BeforeEach
	void clean() {
		addressRepository.deleteAll();
		userProfileRepository.deleteAll();
	}

	private String tokenFor(String userId) {
		return TestTokens.bearer(TestTokens.accessToken(jwtProperties.getSecret(), userId, List.of("BUYER")));
	}

	private static String body(String recipient) {
		return """
				{"recipientName":"%s","phone":"0912345678","province":"Đà Nẵng",
				 "district":"Hải Châu","ward":"Thạch Thang","streetAddress":"54 Nguyễn Lương Bằng"}
				""".formatted(recipient);
	}

	/** Tạo địa chỉ qua API và trả về id — dùng API thật để test đi đúng luồng người dùng. */
	private String createAddress(String userId, String recipient) throws Exception {
		String json = mockMvc.perform(post("/api/users/me/addresses")
				.header(HttpHeaders.AUTHORIZATION, tokenFor(userId))
				.contentType(MediaType.APPLICATION_JSON).content(body(recipient)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		JsonNode node = objectMapper.readTree(json);
		return node.get("id").asText();
	}

	@Test
	void themDiaChi_khongCoToken_tra401() throws Exception {
		mockMvc.perform(post("/api/users/me/addresses")
				.contentType(MediaType.APPLICATION_JSON).content(body("An")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void diaChiDauTien_tuDongThanhMacDinh() throws Exception {
		mockMvc.perform(post("/api/users/me/addresses")
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
				.contentType(MediaType.APPLICATION_JSON).content(body("An")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.isDefault").value(true));
	}

	@Test
	void diaChiThuHai_khongTuDongThanhMacDinh() throws Exception {
		createAddress(USER_A, "An");

		mockMvc.perform(post("/api/users/me/addresses")
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
				.contentType(MediaType.APPLICATION_JSON).content(body("Bình")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.isDefault").value(false));
	}

	@Test
	void datMacDinh_chuyenCoSangDiaChiMoi_vaChiConDungMotMacDinh() throws Exception {
		createAddress(USER_A, "An");
		String second = createAddress(USER_A, "Bình");

		mockMvc.perform(put("/api/users/me/addresses/{id}/default", second)
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isDefault").value(true));

		List<Address> defaults = addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(USER_A).stream()
				.filter(Address::isDefault)
				.toList();
		assertThat(defaults).hasSize(1);
		assertThat(defaults.get(0).getId()).isEqualTo(second);
	}

	@Test
	void datMacDinh_tenChinhDiaChiDangMacDinh_khongDoiGi() throws Exception {
		String first = createAddress(USER_A, "An");

		mockMvc.perform(put("/api/users/me/addresses/{id}/default", first)
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.isDefault").value(true));

		assertThat(addressRepository.findByUserIdAndIsDefaultTrue(USER_A)).isPresent();
	}

	@Test
	void xoaDiaChiMacDinh_diaChiConLaiDuocLenLamMacDinh() throws Exception {
		String first = createAddress(USER_A, "An");
		String second = createAddress(USER_A, "Bình");

		// first đang là mặc định (địa chỉ đầu tiên). Xoá nó đi.
		mockMvc.perform(delete("/api/users/me/addresses/{id}", first)
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isNoContent());

		Address remaining = addressRepository.findById(second).orElseThrow();
		assertThat(remaining.isDefault())
				.as("còn địa chỉ thì phải luôn có đúng một cái mặc định, nếu không trang thanh "
						+ "toán không tự chọn được địa chỉ giao")
				.isTrue();
	}

	@Test
	void xoaDiaChiCuoiCung_khongConGiVaKhongLoi() throws Exception {
		String only = createAddress(USER_A, "An");

		mockMvc.perform(delete("/api/users/me/addresses/{id}", only)
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isNoContent());

		assertThat(addressRepository.countByUserId(USER_A)).isZero();
	}

	@Test
	void suaDiaChi_khongLamMatCoMacDinh() throws Exception {
		String first = createAddress(USER_A, "An");

		mockMvc.perform(put("/api/users/me/addresses/{id}", first)
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
				.contentType(MediaType.APPLICATION_JSON).content(body("An sửa tên")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recipientName").value("An sửa tên"))
				.andExpect(jsonPath("$.isDefault").value(true));
	}

	@Test
	void danhSach_diaChiMacDinhDungDau() throws Exception {
		createAddress(USER_A, "An");
		String second = createAddress(USER_A, "Bình");
		mockMvc.perform(put("/api/users/me/addresses/{id}/default", second)
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/users/me/addresses").header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value(second))
				.andExpect(jsonPath("$[0].isDefault").value(true));
	}

	@Test
	void khongDocDuocDiaChiCuaNguoiKhac_duBietId() throws Exception {
		String cuaB = createAddress(USER_B, "Bình");

		mockMvc.perform(get("/api/users/me/addresses/{id}", cuaB)
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ADDRESS_NOT_FOUND"));
	}

	@Test
	void khongXoaDuocDiaChiCuaNguoiKhac_duBietId() throws Exception {
		String cuaB = createAddress(USER_B, "Bình");

		mockMvc.perform(delete("/api/users/me/addresses/{id}", cuaB)
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isNotFound());

		assertThat(addressRepository.findById(cuaB)).isPresent();
	}

	@Test
	void khongDatMacDinhDuocChoDiaChiCuaNguoiKhac() throws Exception {
		String cuaB = createAddress(USER_B, "Bình");

		mockMvc.perform(put("/api/users/me/addresses/{id}/default", cuaB)
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isNotFound());
	}

	@Test
	void themDiaChi_thieuTruongBatBuoc_tra400() throws Exception {
		mockMvc.perform(post("/api/users/me/addresses")
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"recipientName":"An"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void diaChiKhongTonTai_tra404() throws Exception {
		mockMvc.perform(get("/api/users/me/addresses/{id}", "01JBQ9YDX7K3M8N5P2R4T6V8ZZ")
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ADDRESS_NOT_FOUND"));
	}
}
