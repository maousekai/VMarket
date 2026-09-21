package com.vmarket.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.auth.config.InternalApiProperties;

/**
 * {@code InternalApiKeyFilter} và tầng phân quyền phải nhìn cùng một đường dẫn, kể cả
 * khi service chạy dưới một context path.
 *
 * <p>Bản cũ của filter so bằng {@code request.getRequestURI().startsWith("/internal/")},
 * còn {@code SecurityConfig} khớp {@code /internal/**} theo đường dẫn <i>trong ứng
 * dụng</i>. Hai nguồn khác nhau, chỉ trùng khớp vì context path đang rỗng: đặt
 * {@code server.servlet.context-path=/auth} là URI thành {@code /auth/internal/...},
 * filter bỏ qua (không đặt danh tính) trong khi tầng phân quyền vẫn đòi vai trò nội bộ
 * → <b>mọi</b> lời gọi nội bộ hợp lệ trả 401, đổi mật khẩu chết hẳn.
 *
 * <p>Test này cố định context path để bắt đúng sự lệch đó. Không quan tâm kết quả
 * nghiệp vụ (userId bịa ra): chỉ cần request ĐI QUA được tầng xác thực, tức là không
 * còn 401 nữa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "server.servlet.context-path=" + InternalApiContextPathTest.CONTEXT_PATH)
class InternalApiContextPathTest {

	static final String CONTEXT_PATH = "/auth";

	private static final String BODY = """
			{"currentPassword":"Abcd1234@","newPassword":"Xyz98765#"}
			""";

	@Autowired MockMvc mockMvc;
	@Autowired InternalApiProperties internalApiProperties;

	@Test
	void coContextPath_khoaDung_vanQuaDuocTangXacThuc() throws Exception {
		mockMvc.perform(put(CONTEXT_PATH + "/internal/users/{id}/password", "01JBQ9YDX7K3M8N5P2R4T6V8ZZ")
				.contextPath(CONTEXT_PATH)
				.header(InternalApiProperties.HEADER, internalApiProperties.getApiKey())
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void coContextPath_thieuKhoa_van401() throws Exception {
		mockMvc.perform(put(CONTEXT_PATH + "/internal/users/{id}/password", "01JBQ9YDX7K3M8N5P2R4T6V8ZZ")
				.contextPath(CONTEXT_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}
}
