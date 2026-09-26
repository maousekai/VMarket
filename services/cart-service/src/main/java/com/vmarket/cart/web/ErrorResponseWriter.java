package com.vmarket.cart.web;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import tools.jackson.databind.ObjectMapper;
import com.vmarket.cart.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Ghi thẳng body lỗi chuẩn {@code { "error": { "code", "message" } }} ra response.
 *
 * <p>Dành cho những chỗ lỗi xảy ra ở tầng filter — trước khi request tới
 * controller, nên {@code GlobalExceptionHandler} không với tới được. Gom vào đây
 * để {@code SecurityConfig} không phải tự dựng một body lỗi hơi khác, khiến
 * frontend phải viết thêm nhánh xử lý.
 */
public final class ErrorResponseWriter {

	private ErrorResponseWriter() {
	}

	public static void write(HttpServletResponse response, ObjectMapper objectMapper,
			HttpStatus status, String code, String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code, message));
	}
}