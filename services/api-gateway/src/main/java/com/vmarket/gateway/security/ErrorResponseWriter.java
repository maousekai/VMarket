package com.vmarket.gateway.security;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/** One escaped error envelope for gateway admission failures. */
final class ErrorResponseWriter {
	private static final ObjectMapper JSON = new ObjectMapper();
	private ErrorResponseWriter() { }

	static void write(HttpServletResponse response, int status, String code, String message, String path)
			throws IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(JSON.writeValueAsString(Map.of(
				"timestamp", Instant.now().toString(), "status", status,
				"error", Map.of("code", code, "message", message), "path", path)));
	}
}
