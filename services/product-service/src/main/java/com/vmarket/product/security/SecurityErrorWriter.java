package com.vmarket.product.security;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Ghi lỗi do tầng security trả (trước khi tới controller) theo đúng hình dạng của
 * {@code GlobalExceptionHandler}: {@code {timestamp, status, error: {code, message}, path}}.
 */
public final class SecurityErrorWriter {

	private SecurityErrorWriter() {
	}

	public static void write(HttpServletRequest request, HttpServletResponse response, ObjectMapper objectMapper,
			HttpStatus status, String code, String message) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("timestamp", Instant.now().toString());
		body.put("status", status.value());
		body.put("error", Map.of("code", code, "message", message));
		body.put("path", request.getRequestURI());

		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		objectMapper.writeValue(response.getOutputStream(), body);
	}
}
