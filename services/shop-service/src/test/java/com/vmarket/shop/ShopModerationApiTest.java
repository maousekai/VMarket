package com.vmarket.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.vmarket.events.EventType;
import com.vmarket.events.ShopApproved;
import com.vmarket.events.ShopSuspended;
import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopStatus;

/**
 * FR-SHOP-04 — Admin duyệt / từ chối / đình chỉ / gỡ đình chỉ, và DoD của PBL6-14:
 * luồng đăng ký → chờ duyệt → duyệt/từ chối chạy đúng, {@code ShopApproved} /
 * {@code ShopSuspended} được phát lên Event Bus với đúng payload.
 */
class ShopModerationApiTest extends ShopApiTestSupport {

	// ------------------------------------------------------------ luồng chính (DoD)

	@Test
	void duyet_choDuyetThanhHoatDong_vaPhatShopApproved() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");

		mockMvc.perform(post("/api/shops/admin/{id}/approve", shopId).header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.statusReason").value(nullValue()))
				.andExpect(jsonPath("$.approvedAt").isNotEmpty());

		verify(eventPublisher).publish(EventType.SHOP_APPROVED,
				new ShopApproved(shopId, OWNER_A, "Tiệm Gốm Hội An", ADMIN, false));
	}

	@Test
	void tuChoi_kemLyDo_nguoiBanSuaRoiGuiLai_roiDuocDuyet() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");

		// 1) Admin từ chối kèm lý do — không phát sự kiện nào.
		mockMvc.perform(post("/api/shops/admin/{id}/reject", shopId)
						.header(HttpHeaders.AUTHORIZATION, admin())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("reason", "  Ảnh logo không rõ nét  "))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.statusReason").value("Ảnh logo không rõ nét"));
		verifyNoInteractions(eventPublisher);

		// 2) Người bán thấy lý do, sửa hồ sơ (vẫn REJECTED) rồi gửi lại.
		mockMvc.perform(get("/api/shops/me").header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(jsonPath("$.statusReason").value("Ảnh logo không rõ nét"));

		Map<String, Object> fixed = validShop("Tiệm Gốm Hội An");
		fixed.put("logoUrl", "https://cdn.vmarket.vn/shops/logo-ro-net.png");
		mockMvc.perform(put("/api/shops/me")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(fixed)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"));

		mockMvc.perform(post("/api/shops/me/resubmit").header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING"))
				// Lý do từ chối cũ không còn treo trên hồ sơ đã gửi lại.
				.andExpect(jsonPath("$.statusReason").value(nullValue()));

		// 3) Admin duyệt lần này.
		adminAction(shopId, "approve");
		verify(eventPublisher).publish(EventType.SHOP_APPROVED,
				new ShopApproved(shopId, OWNER_A, "Tiệm Gốm Hội An", ADMIN, false));

		// Lịch sử ghi đủ 4 bước theo đúng thứ tự.
		var history = historyRepository.findByShopIdOrderByCreatedAtAscIdAsc(shopId);
		assertThat(history).extracting(h -> h.getToStatus())
				.containsExactly(ShopStatus.PENDING, ShopStatus.REJECTED, ShopStatus.PENDING, ShopStatus.ACTIVE);
		assertThat(history).extracting(h -> h.getChangedBy())
				.containsExactly(OWNER_A, ADMIN, OWNER_A, ADMIN);
	}

	@Test
	void dinhChi_roiGoDinhChi_phatShopSuspended_roiShopApprovedReinstated() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");
		adminAction(shopId, "approve");
		Instant firstApprovedAt = shopRepository.findById(shopId).orElseThrow().getApprovedAt();

		mockMvc.perform(post("/api/shops/admin/{id}/suspend", shopId)
						.header(HttpHeaders.AUTHORIZATION, admin())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("reason", "Bán hàng giả"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUSPENDED"))
				.andExpect(jsonPath("$.statusReason").value("Bán hàng giả"));
		verify(eventPublisher).publish(EventType.SHOP_SUSPENDED,
				new ShopSuspended(shopId, OWNER_A, "Tiệm Gốm Hội An", ADMIN, "Bán hàng giả"));

		// Bị đình chỉ: biến mất khỏi phía người mua.
		mockMvc.perform(get("/api/shops/{id}", shopId)).andExpect(status().isNotFound());

		mockMvc.perform(post("/api/shops/admin/{id}/reinstate", shopId).header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.statusReason").value(nullValue()));
		verify(eventPublisher).publish(EventType.SHOP_APPROVED,
				new ShopApproved(shopId, OWNER_A, "Tiệm Gốm Hội An", ADMIN, true));

		mockMvc.perform(get("/api/shops/{id}", shopId)).andExpect(status().isOk());
		// "Hoạt động từ" giữ mốc duyệt lần đầu, không bị reset khi gỡ đình chỉ.
		assertThat(shopRepository.findById(shopId).orElseThrow().getApprovedAt()).isEqualTo(firstApprovedAt);
	}

	// ------------------------------------------------------------ bước chuyển sai

	@Test
	void duyet_gianHangDaHoatDong_tra409_vaKhongPhatLaiSuKien() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");
		adminAction(shopId, "approve");

		mockMvc.perform(post("/api/shops/admin/{id}/approve", shopId).header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));

		verify(eventPublisher, times(1)).publish(anyString(), any());
	}

	/** Duyệt không thay được gỡ đình chỉ — hai quyết định khác nhau (xem ShopAction). */
	@Test
	void duyet_gianHangDangBiDinhChi_tra409_phaiDungGoDinhChi() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");
		adminAction(shopId, "approve");
		adminAction(shopId, "suspend", "Vi phạm chính sách");

		mockMvc.perform(post("/api/shops/admin/{id}/approve", shopId).header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
		assertThat(shopRepository.findById(shopId).orElseThrow().getStatus()).isEqualTo(ShopStatus.SUSPENDED);
	}

	@Test
	void tuChoi_hoacDinhChi_saiTrangThaiNguon_tra409() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");

		// Đình chỉ hồ sơ chưa từng hoạt động.
		mockMvc.perform(post("/api/shops/admin/{id}/suspend", shopId)
						.header(HttpHeaders.AUTHORIZATION, admin())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("reason", "x"))))
				.andExpect(status().isConflict());
		// Gỡ đình chỉ hồ sơ không bị đình chỉ.
		mockMvc.perform(post("/api/shops/admin/{id}/reinstate", shopId).header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isConflict());

		adminAction(shopId, "approve");
		// Từ chối gian hàng đã hoạt động (phải dùng đình chỉ).
		mockMvc.perform(post("/api/shops/admin/{id}/reject", shopId)
						.header(HttpHeaders.AUTHORIZATION, admin())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("reason", "x"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.message").value("Không thể từ chối gian hàng đang ở trạng thái \"Hoạt động\""));
	}

	@Test
	void tuChoi_thieuLyDo_tra400_vaKhongDoiTrangThai() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");

		mockMvc.perform(post("/api/shops/admin/{id}/reject", shopId)
						.header(HttpHeaders.AUTHORIZATION, admin())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("reason", "   "))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.details[0].field").value("reason"));

		assertThat(shopRepository.findById(shopId).orElseThrow().getStatus()).isEqualTo(ShopStatus.PENDING);
	}

	@Test
	void thaoTac_gianHangKhongTonTai_tra404() throws Exception {
		mockMvc.perform(post("/api/shops/admin/{id}/approve", "01JBQ9YDX7K3M8N5P2R4T6V8ZZ")
						.header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("SHOP_NOT_FOUND"));
	}

	// ------------------------------------------------------------ độ tin cậy sự kiện

	/**
	 * Sự kiện phát SAU commit: RabbitMQ lỗi lúc đó thì quyết định của Admin vẫn đã lưu và
	 * response vẫn 200 — không trả 500 cho một thao tác thật ra đã thành công.
	 */
	@Test
	void duyet_khiEventBusLoi_trangThaiVanDuocLuu_vaTra200() throws Exception {
		doThrow(new AmqpConnectException(new java.net.ConnectException("Connection refused")))
				.when(eventPublisher).publish(anyString(), any());
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");

		mockMvc.perform(post("/api/shops/admin/{id}/approve", shopId).header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		assertThat(shopRepository.findById(shopId).orElseThrow().getStatus()).isEqualTo(ShopStatus.ACTIVE);
	}

	/** Bước chuyển bị từ chối (rollback) thì không có sự kiện nào lọt ra ngoài. */
	@Test
	void buocChuyenThatBai_khongPhatSuKien() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");

		mockMvc.perform(post("/api/shops/admin/{id}/reinstate", shopId).header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isConflict());

		verify(eventPublisher, never()).publish(anyString(), any());
	}

	// ------------------------------------------------------------ danh sách / chi tiết

	@Test
	void danhSach_locTheoTrangThaiVaTuKhoa_moiNopTruoc() throws Exception {
		String gom = register(OWNER_A, "Tiệm Gốm Hội An");
		String lua = register(OWNER_B, "Lụa Hà Đông");
		String discount = register("01JBQ9YDX7K3M8N5P2R4T6V8W2", "Siêu sale 50% mỗi ngày");
		adminAction(gom, "approve");

		mockMvc.perform(get("/api/shops/admin").param("status", "PENDING").header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.items[0].id").value(discount))
				.andExpect(jsonPath("$.items[1].id").value(lua));

		mockMvc.perform(get("/api/shops/admin").param("keyword", "GỐM").header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].id").value(gom))
				.andExpect(jsonPath("$.items[0].status").value("ACTIVE"));

		// "%" trong từ khoá được hiểu là ký tự thường, không phải wildcard của LIKE.
		mockMvc.perform(get("/api/shops/admin").param("keyword", "50%").header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].id").value(discount));
		mockMvc.perform(get("/api/shops/admin").param("keyword", "%").header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(jsonPath("$.items", hasSize(1)));

		mockMvc.perform(get("/api/shops/admin").param("size", "2").param("page", "1")
						.header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.totalPages").value(2));
	}

	@Test
	void danhSach_thamSoSai_tra400() throws Exception {
		for (Map<String, String> params : List.of(Map.of("status", "KHONG_CO"), Map.of("size", "1000"),
				Map.of("page", "-1"))) {
			var request = get("/api/shops/admin").header(HttpHeaders.AUTHORIZATION, admin());
			params.forEach(request::param);
			mockMvc.perform(request)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		}
	}

	@Test
	void chiTietVaLichSu_choAdmin_thayMoiTrangThai() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");
		adminAction(shopId, "reject", "Thiếu giấy phép");

		mockMvc.perform(get("/api/shops/admin/{id}", shopId).header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ownerId").value(OWNER_A))
				.andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.contactEmail").value("lienhe@gomhoian.vn"));

		mockMvc.perform(get("/api/shops/admin/{id}/status-history", shopId).header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[1].reason").value("Thiếu giấy phép"));
	}

	// ------------------------------------------------------------ phân quyền

	@Test
	void endpointAdmin_khongPhaiAdmin_tra403_khongCoToken_tra401() throws Exception {
		String shopId = register(OWNER_A, "Tiệm Gốm Hội An");

		// Chính chủ gian hàng (BUYER) cũng không tự duyệt được.
		mockMvc.perform(post("/api/shops/admin/{id}/approve", shopId).header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		// GET /api/shops/admin cũng khớp rule công khai GET /api/shops/* — phải bị chặn trước.
		mockMvc.perform(get("/api/shops/admin").header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/shops/admin"))
				.andExpect(status().isUnauthorized());

		Shop shop = shopRepository.findById(shopId).orElseThrow();
		assertThat(shop.getStatus()).isEqualTo(ShopStatus.PENDING);
		verifyNoInteractions(eventPublisher);
	}
}
