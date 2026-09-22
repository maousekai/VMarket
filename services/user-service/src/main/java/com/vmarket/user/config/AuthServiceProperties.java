package com.vmarket.user.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Kết nối tới API nội bộ của auth-service (khoá {@code app.auth-service.*}).
 *
 * <p>Mật khẩu, email, vai trò và trạng thái khoá tài khoản nằm trong CSDL của
 * auth-service. FR-USER-03 (đổi mật khẩu) và FR-USER-04 (Admin quản lý người dùng)
 * thuộc user-service theo SRS nhưng phải đọc/ghi dữ liệu đó → gọi REST nội bộ
 * (SRS 5.4), không đọc chéo CSDL.
 */
@ConfigurationProperties(prefix = "app.auth-service")
@Validated
@Getter
@Setter
public class AuthServiceProperties {

	/** Header mang khoá nội bộ — khớp {@code InternalApiProperties.HEADER} của auth-service. */
	public static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

	/**
	 * userId của Admin thực hiện thao tác quản trị (FR-USER-04), lấy từ access token —
	 * khớp {@code InternalAccountController.ACTOR_HEADER} của auth-service. auth-service
	 * dùng nó để kiểm tra lại theo CSDL rằng người này vẫn là Admin đang hoạt động.
	 */
	public static final String ACTOR_ID_HEADER = "X-Actor-Id";

	/** Vd {@code http://auth-service:8081} trong mạng Docker, {@code http://localhost:8081} khi chạy mvnw. */
	@NotBlank
	private String baseUrl;

	/** Dùng CHUNG giá trị {@code INTERNAL_API_KEY} với auth-service. */
	@NotBlank
	@Size(min = 32, message = "INTERNAL_API_KEY phải >= 32 ký tự")
	private String internalApiKey;

	/**
	 * Timeout ngắn có chủ ý: auth-service chậm thì trả 503 cho người dùng sớm, không
	 * giữ thread của user-service chờ tới khi cạn pool.
	 */
	@NotNull
	private Duration connectTimeout = Duration.ofSeconds(2);

	/**
	 * Đọc lâu hơn kết nối: đổi mật khẩu băm BCrypt hai lần (kiểm tra cũ + băm mới) ở
	 * phía auth-service.
	 */
	@NotNull
	private Duration readTimeout = Duration.ofSeconds(5);
}
