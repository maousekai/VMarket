package com.vmarket.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Metadata OpenAPI/Swagger cho user-service. Swagger UI: {@code /swagger-ui.html},
 * spec JSON: {@code /v3/api-docs}.
 *
 * <p>Khai security scheme {@code bearerAuth} để nút <b>Authorize</b> của Swagger UI
 * cho phép dán access token — mọi endpoint ở service này đều cần token nên thiếu
 * phần này thì không thử được endpoint nào ngay trên trang tài liệu.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI userServiceOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("VMarket User Service API")
						.description("Hồ sơ cá nhân và sổ địa chỉ giao hàng (FR-USER-01, FR-USER-02) "
								+ "và tìm kiếm người dùng cho Admin (FR-USER-04).")
						.version("v1"))
				.components(new Components()
						.addSecuritySchemes("bearerAuth", new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")));
	}
}
