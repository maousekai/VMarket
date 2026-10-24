package com.vmarket.product.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Metadata OpenAPI cho product-service. Swagger UI: {@code /swagger-ui.html},
 * spec JSON: {@code /v3/api-docs}.
 *
 * <p>Khai security scheme {@code bearerAuth} (PBL6-49). Endpoint của seller/admin
 * gắn {@code @SecurityRequirement(name = "bearerAuth")}; endpoint xem công khai
 * thì không, để spec phản ánh đúng chỗ nào cần token.
 */
@Configuration
public class OpenApiConfig {

	public static final String BEARER_AUTH = "bearerAuth";

	@Bean
	OpenAPI productServiceOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("VMarket Product Service API")
						.description("Danh mục, thương hiệu, sản phẩm và tồn kho.")
						.version("v1"))
				.components(new Components()
						.addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")));
	}
}
