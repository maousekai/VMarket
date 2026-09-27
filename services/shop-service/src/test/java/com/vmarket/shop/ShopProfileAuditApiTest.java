package com.vmarket.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.vmarket.shop.repository.ShopProfileChangeRepository;

/**
 * Nhật ký sửa nội dung hồ sơ (ra soát PR #24, mục A).
 *
 * <p>Người bán được sửa hồ sơ kể cả khi gian hàng đang hoạt động và việc đó không đưa
 * gian hàng về "Chờ duyệt" — nên nội dung đã duyệt có thể bị thay. Bộ test này khoá lại
 * cái bù cho đánh đổi đó: mọi trường bị đổi đều để lại dấu vết cho Admin.
 */
class ShopProfileAuditApiTest extends ShopApiTestSupport {

	@Autowired ShopProfileChangeRepository profileChangeRepository;

	private void update(String ownerId, Map<String, Object> body) throws Exception {
		mockMvc.perform(put("/api/shops/me")
						.header(HttpHeaders.AUTHORIZATION, buyer(ownerId))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(body)))
				.andExpect(status().isOk());
	}

	@Test
	void suaHoSoKhiDangHoatDong_ghiVetTungTruong_kemTrangThaiLucSua() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");
		adminAction(shopId, "approve");

		Map<String, Object> body = validShop("Tiệm Gốm Hội An");
		body.put("description", "Nội dung mới sau khi được duyệt");
		body.put("policies", "Chính sách mới");
		update(OWNER_A, body);

		mockMvc.perform(get("/api/shops/admin/{id}/profile-history", shopId)
						.header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.items[*].fieldName").value(
						org.hamcrest.Matchers.containsInAnyOrder("description", "policies")))
				.andExpect(jsonPath("$.items[0].statusAtChange").value("ACTIVE"))
				.andExpect(jsonPath("$.items[0].changedBy").value(OWNER_A));

		var change = profileChangeRepository.findAll().stream()
				.filter(c -> c.getFieldName().equals("policies"))
				.findFirst().orElseThrow();
		assertThat(change.getOldValue()).isEqualTo("Đổi trả trong 7 ngày");
		assertThat(change.getNewValue()).isEqualTo("Chính sách mới");
	}

	@Test
	void guiLaiDungHoSoCu_khongGhiVetGi() throws Exception {
		register(OWNER_A, "Tiệm Gốm Hội An");

		// Client retry / bấm lưu hai lần với đúng nội dung cũ — nhật ký không được phình.
		update(OWNER_A, validShop("Tiệm Gốm Hội An"));

		assertThat(profileChangeRepository.count()).isZero();
	}

	/** Khoảng trắng thừa chỉ là chuẩn hoá, không phải người bán đổi nội dung. */
	@Test
	void chiKhacKhoangTrangThua_khongTinhLaThayDoi() throws Exception {
		register(OWNER_A, "Tiệm Gốm Hội An");

		update(OWNER_A, validShop("  Tiệm   Gốm Hội An  "));

		assertThat(profileChangeRepository.count()).isZero();
	}

	@Test
	void xoaTruongTuyChon_ghiVetVoiGiaTriMoiLaNull() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");

		Map<String, Object> body = validShop("Tiệm Gốm Hội An");
		body.remove("coverUrl");
		update(OWNER_A, body);

		mockMvc.perform(get("/api/shops/admin/{id}/profile-history", shopId)
						.header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].fieldName").value("coverUrl"))
				.andExpect(jsonPath("$.items[0].newValue").value(nullValue()))
				.andExpect(jsonPath("$.items[0].statusAtChange").value("PENDING"));
	}

	@Test
	void nhatKy_khongPhaiAdmin_tra403_khongCoToken_tra401() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");

		mockMvc.perform(get("/api/shops/admin/{id}/profile-history", shopId)
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/shops/admin/{id}/profile-history", shopId))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void nhatKy_gianHangKhongTonTai_tra404_thamSoSai_tra400() throws Exception {
		mockMvc.perform(get("/api/shops/admin/{id}/profile-history", "01JBQ9YDX7K3M8N5P2R4T6V8ZZ")
						.header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHOP_NOT_FOUND"));

		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");
		mockMvc.perform(get("/api/shops/admin/{id}/profile-history", shopId).param("size", "1000")
						.header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	/** Nit từ rà soát: ô URL chỉ có dấu cách là "bỏ trống", không phải URL sai. */
	@Test
	void urlAnhToanKhoangTrang_coiNhuBoTrong_khongBao400() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");

		Map<String, Object> body = validShop("Tiệm Gốm Hội An");
		body.put("logoUrl", "   ");
		update(OWNER_A, body);

		assertThat(shopRepository.findById(shopId).orElseThrow().getLogoUrl()).isNull();
	}
}
