package com.vmarket.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Xác nhận springdoc sinh được đặc tả OpenAPI mà không lỗi (vd trùng
 * {@code operationId}, {@code @Schema} tham chiếu hỏng) — trước PBL6-47 chưa
 * có test nào chạm tới {@code /v3/api-docs}, dù mọi controller đều đã gắn
 * annotation Swagger từ trước.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsSmokeTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void apiDocsJson_generatesWithoutError() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openapi").exists())
				.andExpect(jsonPath("$.paths").exists());
	}
}
