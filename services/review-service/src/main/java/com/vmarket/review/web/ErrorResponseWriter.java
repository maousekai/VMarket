package com.vmarket.review.web;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.vmarket.review.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/** Ghi body lỗi chuẩn cho lỗi ở tầng filter (401/403), nơi {@code GlobalExceptionHandler} không tới. */
public final class ErrorResponseWriter {

	private ErrorResponseWriter() {
	}

	public static void write(HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status, String code,
			String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code, message));
	}
}
