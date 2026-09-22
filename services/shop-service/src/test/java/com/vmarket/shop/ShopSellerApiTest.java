package com.vmarket.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopStatus;

/**
 * FR-SHOP-01 (đăng ký), FR-SHOP-02 (người bán quản lý gian hàng), FR-SHOP-03 (trang
 * công khai) và các rule phân quyền phía người bán.
 */
class ShopSellerApiTest extends ShopApiTestSupport {

	// ---------------------------------------------------------------- FR-SHOP-01

	@Test
	void dangKy_hopLe_tra201_trangThaiChoDuyet_vaGhiLichSuDauTien() throws Exception {
		mockMvc.perform(post("/api/shops")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(validShop("  Tiệm   Gốm Hội An  "))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.ownerId").value(OWNER_A))
				// Tên được chuẩn hoá: bỏ khoảng trắng đầu/cuối, gộp khoảng trắng thừa.
				.andExpect(jsonPath("$.name").value("Tiệm Gốm Hội An"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.approvedAt").value(nullValue()));

		Shop shop = shopRepository.findByOwnerId(OWNER_A).orElseThrow();
		var history = historyRepository.findByShopIdOrderByCreatedAtAscIdAsc(shop.getId());
		assertThat(history).hasSize(1);
		assertThat(history.get(0).getFromStatus()).isNull();
		assertThat(history.get(0).getToStatus()).isEqualTo(ShopStatus.PENDING);
		assertThat(history.get(0).getChangedBy()).isEqualTo(OWNER_A);

		// Nộp hồ sơ không phát sự kiện nào — chỉ duyệt / đình chỉ mới phát (SRS §8.1).
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void dangKy_khongCoToken_tra401() throws Exception {
		mockMvc.perform(post("/api/shops")
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(validShop("Tiệm Gốm"))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void dangKy_tokenSaiIssuerHoacHetHan_tra401() throws Exception {
		for (String token : List.of(TestTokens.wrongIssuerToken(jwtProperties.getSecret(), OWNER_A),
				TestTokens.expiredToken(jwtProperties.getSecret(), OWNER_A))) {
			mockMvc.perform(post("/api/shops")
							.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(token))
							.contentType(MediaType.APPLICATION_JSON)
							.content(json(validShop("Tiệm Gốm"))))
					.andExpect(status().isUnauthorized());
		}
	}

	/** Ma trận RBAC (SRS 7.2): chỉ Buyer được đăng ký mở gian hàng. */
	@Test
	void dangKy_khongCoVaiTroBuyer_tra403() throws Exception {
		String shipperOnly = TestTokens.bearer(
				TestTokens.accessToken(jwtProperties.getSecret(), OWNER_A, List.of("SHIPPER")));
		mockMvc.perform(post("/api/shops")
						.header(HttpHeaders.AUTHORIZATION, shipperOnly)
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(validShop("Tiệm Gốm"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void dangKy_lanThuHai_tra409_SHOP_ALREADY_EXISTS() throws Exception {
		register(OWNER_A, "Tiệm Gốm Hội An");

		mockMvc.perform(post("/api/shops")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(validShop("Tiệm Khác"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SHOP_ALREADY_EXISTS"));
	}

	@Test
	void dangKy_trungTen_khongPhanBietHoaThuongVaKhoangTrang_tra409() throws Exception {
		register(OWNER_A, "Tiệm Gốm Hội An");

		mockMvc.perform(post("/api/shops")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_B))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(validShop("tiệm  GỐM hội an"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SHOP_NAME_TAKEN"));
	}

	@Test
	void dangKy_duLieuSai_tra400_kemChiTietTungTruong() throws Exception {
		Map<String, Object> body = validShop("");
		body.put("contactPhone", "123");
		body.put("contactEmail", "khong-phai-email");
		// URL ảnh đổ thẳng vào <img src> ở frontend — chặn scheme lạ (XSS).
		body.put("logoUrl", "javascript:alert(1)");
		body.remove("province");

		mockMvc.perform(post("/api/shops")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(body)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.details[*].field").value(hasItem("name")))
				.andExpect(jsonPath("$.error.details[*].field").value(hasItem("contactPhone")))
				.andExpect(jsonPath("$.error.details[*].field").value(hasItem("contactEmail")))
				.andExpect(jsonPath("$.error.details[*].field").value(hasItem("logoUrl")))
				.andExpect(jsonPath("$.error.details[*].field").value(hasItem("province")));

		assertThat(shopRepository.count()).isZero();
	}

	@Test
	void dangKy_bodyKhongPhaiJson_tra400_MALFORMED_REQUEST() throws Exception {
		mockMvc.perform(post("/api/shops")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
	}

	// ---------------------------------------------------------------- FR-SHOP-02

	@Test
	void xemGianHangCuaToi_chuaDangKy_tra404() throws Exception {
		mockMvc.perform(get("/api/shops/me").header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHOP_NOT_FOUND"));
	}

	/**
	 * {@code GET /api/shops/*} là trang công khai và cũng khớp {@code /me}. Rule của /me
	 * phải đứng trước — nếu không khách vãng lai gọi được, và controller nhận principal
	 * null.
	 */
	@Test
	void xemGianHangCuaToi_khongCoToken_tra401_khongBiRuleCongKhaiNuot() throws Exception {
		mockMvc.perform(get("/api/shops/me"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/shops/me/status-history"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void xemGianHangCuaToi_chiThayGianHangCuaChinhMinh() throws Exception {
		register(OWNER_A, "Tiệm Gốm Hội An");

		mockMvc.perform(get("/api/shops/me").header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Tiệm Gốm Hội An"))
				.andExpect(jsonPath("$.contactPhone").value("0912345678"));

		mockMvc.perform(get("/api/shops/me").header(HttpHeaders.AUTHORIZATION, buyer(OWNER_B)))
				.andExpect(status().isNotFound());
	}

	@Test
	void capNhat_thayTheToanBo_truongTuyChonKhongGuiBiXoa_vaKhongDoiTrangThai() throws Exception {
		register(OWNER_A, "Tiệm Gốm Hội An");

		Map<String, Object> body = validShop("Tiệm Gốm Thanh Hà");
		body.remove("coverUrl");
		body.put("policies", "   ");

		mockMvc.perform(put("/api/shops/me")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(body)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Tiệm Gốm Thanh Hà"))
				.andExpect(jsonPath("$.coverUrl").value(nullValue()))
				.andExpect(jsonPath("$.policies").value(nullValue()))
				.andExpect(jsonPath("$.status").value("PENDING"));
	}

	@Test
	void capNhat_doiHoaThuongTenCuaChinhMinh_duocPhep() throws Exception {
		register(OWNER_A, "Tiệm Gốm Hội An");

		mockMvc.perform(put("/api/shops/me")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(validShop("TIỆM GỐM HỘI AN"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("TIỆM GỐM HỘI AN"));
	}

	@Test
	void capNhat_sangTenCuaGianHangKhac_tra409() throws Exception {
		register(OWNER_A, "Tiệm Gốm Hội An");
		register(OWNER_B, "Lụa Hà Đông");

		mockMvc.perform(put("/api/shops/me")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_B))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(validShop("Tiệm gốm Hội An"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SHOP_NAME_TAKEN"));
	}

	@Test
	void capNhat_khiDangBiDinhChi_tra409_SHOP_SUSPENDED() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");
		adminAction(shopId, "approve");
		adminAction(shopId, "suspend", "Bán hàng giả");

		mockMvc.perform(put("/api/shops/me")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(validShop("Tên Mới Để Lách"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SHOP_SUSPENDED"));

		assertThat(shopRepository.findById(shopId).orElseThrow().getName()).isEqualTo("Tiệm Gốm Hội An");
	}

	@Test
	void guiLai_khiChuaBiTuChoi_tra409() throws Exception {
		register(OWNER_A, "Tiệm Gốm Hội An");

		mockMvc.perform(post("/api/shops/me/resubmit").header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
	}

	@Test
	void lichSuCuaToi_theoThuTuThoiGian() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");
		adminAction(shopId, "reject", "Thiếu ảnh logo");

		mockMvc.perform(get("/api/shops/me/status-history").header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].toStatus").value("PENDING"))
				.andExpect(jsonPath("$[1].fromStatus").value("PENDING"))
				.andExpect(jsonPath("$[1].toStatus").value("REJECTED"))
				.andExpect(jsonPath("$[1].reason").value("Thiếu ảnh logo"))
				.andExpect(jsonPath("$[1].changedBy").value(ADMIN));
	}

	// ---------------------------------------------------------------- FR-SHOP-03

	@Test
	void trangCongKhai_gianHangChuaDuocDuyet_tra404_khongLoTrangThai() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");

		mockMvc.perform(get("/api/shops/{id}", shopId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHOP_NOT_FOUND"));
	}

	@Test
	void trangCongKhai_gianHangHoatDong_khongCanToken_vaKhongLoThongTinLienHe() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");
		adminAction(shopId, "approve");

		mockMvc.perform(get("/api/shops/{id}", shopId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(shopId))
				.andExpect(jsonPath("$.name").value("Tiệm Gốm Hội An"))
				.andExpect(jsonPath("$.province").value("Quảng Nam"))
				.andExpect(jsonPath("$.approvedAt").isNotEmpty())
				.andExpect(jsonPath("$.ownerId").doesNotExist())
				.andExpect(jsonPath("$.contactEmail").doesNotExist())
				.andExpect(jsonPath("$.contactPhone").doesNotExist())
				.andExpect(jsonPath("$.streetAddress").doesNotExist())
				.andExpect(jsonPath("$.status").doesNotExist());
	}

	@Test
	void trangCongKhai_idSaiDinhDang_tra400() throws Exception {
		mockMvc.perform(get("/api/shops/{id}", "khong-phai-ulid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}
}
