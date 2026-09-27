package com.vmarket.shop.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Metadata OpenAPI/Swagger cho shop-service. Swagger UI: {@code /swagger-ui.html},
 * spec JSON: {@code /v3/api-docs}.
 *
 * <p>Khai security scheme {@code bearerAuth} để nút <b>Authorize</b> của Swagger UI
 * cho phép dán access token (endpoint người bán / Admin đều cần token).
 */
@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI shopServiceOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("VMarket Shop Service API")
						.description("Vòng đời gian hàng: đăng ký (FR-SHOP-01), người bán quản lý gian hàng "
								+ "(FR-SHOP-02), trang gian hàng công khai (FR-SHOP-03), Admin duyệt / từ chối / "
								+ "đình chỉ và phát sự kiện ShopApproved / ShopSuspended (FR-SHOP-04).")
						.version("v1"))
				.components(new Components()
						.addSecuritySchemes("bearerAuth", new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")));
	}
}
