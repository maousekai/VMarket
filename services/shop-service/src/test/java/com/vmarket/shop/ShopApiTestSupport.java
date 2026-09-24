package com.vmarket.shop;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.vmarket.events.EventPublisher;
import com.vmarket.shop.config.ShopJwtProperties;
import com.vmarket.shop.repository.ShopProfileChangeRepository;
import com.vmarket.shop.repository.ShopRepository;
import com.vmarket.shop.repository.ShopStatusHistoryRepository;

/**
 * Nền chung cho test API: MockMvc + token thật + dữ liệu sạch trước mỗi test.
 *
 * <p>{@link EventPublisher} được thay bằng mock: test không cần RabbitMQ, và kiểm tra
 * được chính xác sự kiện nào được phát với payload gì (DoD của PBL6-14). Mock nằm ở
 * lớp nền để mọi lớp test dùng chung một Spring context (khác bộ mock = khác context).
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class ShopApiTestSupport {

	static final String OWNER_A = "01JBQ9YDX7K3M8N5P2R4T6V8W0";
	static final String OWNER_B = "01JBQ9YDX7K3M8N5P2R4T6V8W1";
	static final String ADMIN = "01JBQ9YDX7K3M8N5P2R4T6V8WA";

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired ShopJwtProperties jwtProperties;
	@Autowired ShopRepository shopRepository;
	@Autowired ShopStatusHistoryRepository historyRepository;
	@Autowired ShopProfileChangeRepository profileChanges;

	@MockitoBean EventPublisher eventPublisher;

	@BeforeEach
	void cleanDatabase() {
		// Xoá tường minh cả hai bảng con: schema của test do Hibernate sinh nên không có
		// khoá ngoại ON DELETE CASCADE như migration thật.
		profileChanges.deleteAll();
		historyRepository.deleteAll();
		shopRepository.deleteAll();
	}

	String buyer(String userId) {
		return TestTokens.bearer(TestTokens.accessToken(jwtProperties.getSecret(), userId, List.of("BUYER")));
	}

	String admin() {
		return TestTokens.bearer(TestTokens.accessToken(jwtProperties.getSecret(), ADMIN, List.of("ADMIN")));
	}

	/** Hồ sơ hợp lệ đầy đủ; test sửa từng trường qua {@link #json(Map)}. */
	static Map<String, Object> validShop(String name) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("description", "Gốm thủ công làng Thanh Hà");
		body.put("logoUrl", "https://cdn.vmarket.vn/shops/logo.png");
		body.put("coverUrl", "https://cdn.vmarket.vn/shops/cover.jpg");
		body.put("policies", "Đổi trả trong 7 ngày");
		body.put("contactEmail", "lienhe@gomhoian.vn");
		body.put("contactPhone", "0912345678");
		body.put("province", "Quảng Nam");
		body.put("district", "Hội An");
		body.put("ward", "Thanh Hà");
		body.put("streetAddress", "12 Phạm Phán");
		return body;
	}

	String json(Map<String, Object> body) {
		return objectMapper.writeValueAsString(body);
	}

	/** Đăng ký qua API thật và trả về id gian hàng. */
	String register(String ownerId, String name) throws Exception {
		String response = mockMvc.perform(post("/api/shops")
						.header(HttpHeaders.AUTHORIZATION, buyer(ownerId))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(validShop(name))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("id").asText();
	}

	/** Admin thực hiện một hành động không cần body (approve / reinstate). */
	void adminAction(String shopId, String action) throws Exception {
		mockMvc.perform(post("/api/shops/admin/{id}/{action}", shopId, action)
						.header(HttpHeaders.AUTHORIZATION, admin()))
				.andExpect(status().isOk());
	}

	/** Admin thực hiện một hành động cần lý do (reject / suspend). */
	void adminAction(String shopId, String action, String reason) throws Exception {
		mockMvc.perform(post("/api/shops/admin/{id}/{action}", shopId, action)
						.header(HttpHeaders.AUTHORIZATION, admin())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(Map.of("reason", reason))))
				.andExpect(status().isOk());
	}
}
