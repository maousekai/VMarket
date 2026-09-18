package com.vmarket.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Khoá xác thực cho API nội bộ {@code /internal/**} (khoá {@code auth.internal.*} →
 * biến môi trường {@code INTERNAL_API_KEY}).
 *
 * <p>API nội bộ phục vụ service khác gọi auth-service trong mạng Docker (SRS 5.4) —
 * hiện là user-service cho FR-USER-03 (đổi mật khẩu). Gateway không định tuyến
 * {@code /internal/**}, nhưng cổng 8081 vẫn publish ra host khi debug nên không thể
 * chỉ dựa vào "không ai gọi tới được".
 *
 * <p>Giống {@code AUTH_JWT_SECRET}: không có giá trị mặc định ở {@code application.yml}
 * nền → thiếu biến ở prod là fail ngay lúc khởi động. Giá trị giả cho dev nằm ở
 * {@code application-dev.yml}.
 */
@ConfigurationProperties(prefix = "auth.internal")
@Validated
@Getter
@Setter
public class InternalApiProperties {

	/** Header mang khoá khi gọi {@code /internal/**}. */
	public static final String HEADER = "X-Internal-Api-Key";

	/** Khoá dùng chung với các service gọi vào — tối thiểu 32 ký tự. */
	@NotBlank
	@Size(min = 32, message = "INTERNAL_API_KEY phải >= 32 ký tự")
	private String apiKey;
}
