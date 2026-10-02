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

	/** Annotation Swagger thêm cho {@code HealthController} ở PBL6-47 thật sự vào đặc tả. */
	@Test
	void apiDocs_documentsHealthEndpoint_underHealthTag() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags[?(@.name == 'Health')]").exists())
				.andExpect(jsonPath("$.paths['/api/auth/health'].get.tags[0]").value("Health"))
				.andExpect(jsonPath("$.paths['/api/auth/health'].get.summary").isNotEmpty())
				.andExpect(jsonPath("$.paths['/api/auth/health'].get.responses['200'].content['*/*'].schema['$ref']")
						.value("#/components/schemas/HealthResponse"));
	}

	/** {@code GET /api/auth/sessions} trả {@code List<SessionSummary>} → schema phải là mảng. */
	@Test
	void apiDocs_sessionList_isArrayOfSessionSummary() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/auth/sessions'].get.responses['200'].content['*/*'].schema.type")
						.value("array"))
				.andExpect(jsonPath("$.paths['/api/auth/sessions'].get.responses['200'].content['*/*'].schema.items['$ref']")
						.value("#/components/schemas/SessionSummary"));
	}
}
