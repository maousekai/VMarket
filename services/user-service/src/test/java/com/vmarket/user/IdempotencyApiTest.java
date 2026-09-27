package com.vmarket.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.ObjectMapper;
import com.vmarket.user.config.UserJwtProperties;
import com.vmarket.user.entity.IdempotencyRecord;
import com.vmarket.user.repository.AddressRepository;
import com.vmarket.user.repository.IdempotencyRecordRepository;
import com.vmarket.user.web.IdempotencyFilter;

/**
 * Header {@code Idempotency-Key} trên các endpoint ghi dữ liệu.
 *
 * <p>Tình huống thật đang được bảo vệ: client hết thời gian chờ rồi gửi lại
 * {@code POST /api/users/me/addresses}. Không có cơ chế này thì lần gửi lại tạo
 * thêm một địa chỉ y hệt — không lỗi nào báo ra, người dùng tự phát hiện và tự dọn.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyApiTest {

	private static final String USER_A = "01JBQ9YDX7K3M8N5P2R4T6V8W0";
	private static final String USER_B = "01JBQ9YDX7K3M8N5P2R4T6V8W1";
	private static final String KEY = "6f1c2a3e-1111-4444-8888-aaaaaaaaaaaa";

	@Autowired MockMvc mockMvc;
	@Autowired AddressRepository addressRepository;
	@Autowired IdempotencyRecordRepository idempotencyRepository;
	@Autowired UserJwtProperties jwtProperties;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clean() {
		addressRepository.deleteAll();
		idempotencyRepository.deleteAll();
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

	/** {@code key == null} nghĩa là client không gửi header. */
	private ResultActions postAddress(String userId, String key, String recipient) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/users/me/addresses")
				.header(HttpHeaders.AUTHORIZATION, tokenFor(userId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(recipient));
		if (key != null) {
			request = request.header(IdempotencyFilter.HEADER, key);
		}
		return mockMvc.perform(request);
	}

	@Test
	void guiLaiCungKey_khongTaoThemDiaChi_vaTraLaiDungResponseCu() throws Exception {
		String first = postAddress(USER_A, KEY, "An")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();

		String replayed = postAddress(USER_A, KEY, "An")
				.andExpect(status().isCreated())
				.andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"))
				.andReturn().getResponse().getContentAsString();

		assertThat(replayed)
				.as("phát lại phải giống hệt response cũ, kể cả id — nếu khác thì client "
						+ "vẫn tưởng mình vừa tạo thêm một địa chỉ mới")
				.isEqualTo(first);
		assertThat(addressRepository.countByUserId(USER_A)).isEqualTo(1);
	}

	@Test
	void khongCoKey_guiLaiTaoThemBanTrung() throws Exception {
		// Ghi lại hành vi khi client KHÔNG gửi header: không có bảo vệ nào cả. Header
		// là tuỳ chọn, nên hành vi cũ phải giữ nguyên.
		postAddress(USER_A, null, "An").andExpect(status().isCreated());
		postAddress(USER_A, null, "An").andExpect(status().isCreated());

		assertThat(addressRepository.countByUserId(USER_A)).isEqualTo(2);
	}

	@Test
	void cungKey_khacNoiDung_tra422() throws Exception {
		postAddress(USER_A, KEY, "An").andExpect(status().isCreated());

		postAddress(USER_A, KEY, "Bình")
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

		assertThat(addressRepository.countByUserId(USER_A)).isEqualTo(1);
	}

	@Test
	void haiNguoiDungTrungKey_khongChePhaiNhau() throws Exception {
		postAddress(USER_A, KEY, "An").andExpect(status().isCreated());

		postAddress(USER_B, KEY, "Bình")
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

		assertThat(addressRepository.countByUserId(USER_B)).isEqualTo(1);
	}

	@Test
	void requestLoi_khongKhoaKey() throws Exception {
		// Giữ lại một kết quả lỗi sẽ biến nó thành vĩnh viễn: client sửa dữ liệu rồi
		// gửi lại cùng key vẫn nhận đúng lỗi cũ mà không hiểu vì sao.
		mockMvc.perform(post("/api/users/me/addresses")
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
				.header(IdempotencyFilter.HEADER, KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"recipientName":"An"}
						"""))
				.andExpect(status().isBadRequest());

		assertThat(idempotencyRepository.findByUserIdAndIdempotencyKey(USER_A, KEY)).isEmpty();

		postAddress(USER_A, KEY, "An").andExpect(status().isCreated());
		assertThat(addressRepository.countByUserId(USER_A)).isEqualTo(1);
	}

	@Test
	void keyRong_tra400() throws Exception {
		postAddress(USER_A, "   ", "An")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_INVALID"));
	}

	@Test
	void keyQuaDai_tra400() throws Exception {
		postAddress(USER_A, "k".repeat(IdempotencyRecord.KEY_MAX_LENGTH + 1), "An")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_INVALID"));
	}

	@Test
	void keyDangXuLy_tra409_vaKhongChayLai() throws Exception {
		postAddress(USER_A, KEY, "An").andExpect(status().isCreated());
		markInProgress();

		postAddress(USER_A, KEY, "An")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_IN_PROGRESS"));

		assertThat(addressRepository.countByUserId(USER_A)).isEqualTo(1);
	}

	@Test
	void keyTreoQuaHan_duocNhaRaChoLanGuiLaiKeTiep() throws Exception {
		// Service bị kill giữa chừng để lại một dòng "đang xử lý" không bao giờ xong.
		// Không nhả ra thì key đó hỏng vĩnh viễn.
		postAddress(USER_A, KEY, "An").andExpect(status().isCreated());
		markInProgress();
		backdateClaim(OffsetDateTime.now().minusHours(1));

		postAddress(USER_A, KEY, "An")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_IN_PROGRESS"));
		assertThat(idempotencyRepository.findByUserIdAndIdempotencyKey(USER_A, KEY)).isEmpty();

		postAddress(USER_A, KEY, "An").andExpect(status().isCreated());
		assertThat(addressRepository.countByUserId(USER_A)).isEqualTo(2);
	}

	@Test
	void xoaDiaChi_guiLaiCungKey_van204() throws Exception {
		String json = postAddress(USER_A, "key-tao-dia-chi", "An")
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String addressId = objectMapper.readTree(json).get("id").asText();

		mockMvc.perform(deleteWithKey(addressId)).andExpect(status().isNoContent());

		// Không có cơ chế này thì lần gửi lại trả 404 ADDRESS_NOT_FOUND, khiến client
		// tưởng thao tác hỏng trong khi nó đã thành công.
		mockMvc.perform(deleteWithKey(addressId))
				.andExpect(status().isNoContent())
				.andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));
	}

	private MockHttpServletRequestBuilder deleteWithKey(String addressId) {
		return delete("/api/users/me/addresses/{id}", addressId)
				.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
				.header(IdempotencyFilter.HEADER, KEY);
	}

	/** Đưa bản ghi về trạng thái "request đầu tiên vẫn đang chạy". */
	private void markInProgress() {
		IdempotencyRecord record = idempotencyRepository.findByUserIdAndIdempotencyKey(USER_A, KEY).orElseThrow();
		record.setResponseStatus(null);
		record.setResponseBody(null);
		record.setResponseContentType(null);
		record.setCompletedAt(null);
		idempotencyRepository.saveAndFlush(record);
	}

	/**
	 * Lùi {@code created_at} về quá khứ bằng SQL thuần: cột khai
	 * {@code updatable = false} nên JPA không đổi được.
	 */
	private void backdateClaim(OffsetDateTime createdAt) {
		jdbcTemplate.update("update idempotency_keys set created_at = ? where user_id = ? and idempotency_key = ?",
				createdAt, USER_A, KEY);
	}
}
