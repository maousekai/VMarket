package com.vmarket.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Cấu hình cookie chứa refresh token (PBL6-46) — khoá {@code auth.refresh-cookie.*}.
 *
 * <p>{@code secure=true} bắt buộc ở prod (cookie chỉ gửi qua HTTPS); dev override
 * xuống {@code false} ở {@code application-dev.yml} vì local chạy HTTP thuần.
 * FE/gateway/auth-service luôn cùng origin từ góc nhìn trình duyệt (nginx/Vite proxy
 * `/api` — xem {@code frontend/vite.config.js}), nên {@code SameSite=Lax} là đủ,
 * không cần {@code None}.
 */
@ConfigurationProperties(prefix = "auth.refresh-cookie")
@Validated
@Getter
@Setter
public class AuthRefreshCookieProperties {

	/** Chỉ gửi cookie qua HTTPS. Bật ở mọi môi trường trừ dev local (HTTP). */
	private boolean secure = true;

	/** Giá trị SameSite ("Lax" | "Strict" | "None"). */
	@NotBlank
	private String sameSite = "Lax";
}
