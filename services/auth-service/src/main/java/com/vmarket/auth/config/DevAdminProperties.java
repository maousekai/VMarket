package com.vmarket.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Tài khoản ADMIN mẫu chỉ dùng ở profile {@code dev} — khoá {@code app.dev-admin.*}.
 *
 * <p>Mật khẩu lấy từ {@code DEV_ADMIN_PASSWORD}. Để trống thì
 * {@code DevAdminSeeder} sinh mật khẩu ngẫu nhiên và ghi ra log một lần — không
 * còn mật khẩu cố định nằm trong repo.
 */
@ConfigurationProperties(prefix = "app.dev-admin")
@Validated
@Getter
@Setter
public class DevAdminProperties {

	@NotBlank
	@Email
	@Size(max = 320)
	private String email = "admin@vmarket.local";

	@NotBlank
	@Size(min = 3, max = 50)
	private String username = "admin.vmarket";

	/** BCrypt chỉ dùng tối đa 72 byte; để trống = sinh ngẫu nhiên. */
	@Size(max = 72)
	private String password = "";
}
