package com.vmarket.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Metadata OpenAPI/Swagger cho order-service. Swagger UI: {@code /swagger-ui.html},
 * spec JSON: {@code /v3/api-docs}.
 *
 * <p>Khai security scheme {@code bearerAuth} để nút <b>Authorize</b> của Swagger UI
 * cho phép dán access token — mọi endpoint nghiệp vụ ở service này đều cần token
 * nên thiếu phần này thì không thử được endpoint nào ngay trên trang tài liệu.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI orderServiceOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("VMarket Order Service API")
						.description("Đặt hàng từ giỏ (FR-ORDER-01), theo dõi đơn của tôi "
								+ "(FR-ORDER-02) và huỷ đơn (FR-ORDER-03).")
						.version("v1"))
				.components(new Components()
						.addSecuritySchemes("bearerAuth", new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")));
	}
}