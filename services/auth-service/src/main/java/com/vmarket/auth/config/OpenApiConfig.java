package com.vmarket.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Metadata OpenAPI/Swagger cho auth-service. Swagger UI: {@code /swagger-ui.html},
 * spec JSON: {@code /v3/api-docs}. Từng subtask FR-AUTH-* sẽ bổ sung annotation
 * {@code @Operation} / {@code @ApiResponse} cho endpoint của mình.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI authServiceOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("VMarket Auth Service API")
						.description("Đăng ký, đăng nhập, quản lý phiên và phân quyền RBAC cho VMarket.")
						.version("v1"))
				.components(new Components()
						.addSecuritySchemes("bearerAuth", new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")));
	}
}
