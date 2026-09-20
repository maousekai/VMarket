package com.vmarket.user.config;

import java.util.Objects;
import java.util.stream.Stream;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.vmarket.user.entity.IdempotencyRecord;
import com.vmarket.user.web.IdempotencyFilter;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
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
						.description("Hồ sơ cá nhân (FR-USER-01) và sổ địa chỉ giao hàng (FR-USER-02) "
								+ "của người dùng đang đăng nhập.")
						.version("v1"))
				.components(new Components()
						.addSecuritySchemes("bearerAuth", new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")));
	}

	/**
	 * Gắn header {@code Idempotency-Key} vào MỌI operation ghi dữ liệu.
	 *
	 * <p>Làm ở một chỗ thay vì {@code @Parameter} trên từng method: header do
	 * {@code IdempotencyFilter} xử lý chứ không phải controller, nên endpoint mới
	 * nào cũng có nó — khai tay thì sớm muộn sẽ có chỗ quên, và tài liệu nói sai về
	 * hành vi thật còn hại hơn là không nói.
	 */
	@Bean
	OpenApiCustomizer idempotencyKeyHeader() {
		return openApi -> openApi.getPaths().values().forEach(OpenApiConfig::addIdempotencyKeyTo);
	}

	private static void addIdempotencyKeyTo(PathItem pathItem) {
		Stream.of(pathItem.getPost(), pathItem.getPut(), pathItem.getPatch(), pathItem.getDelete())
				.filter(Objects::nonNull)
				.forEach(operation -> operation.addParametersItem(new Parameter()
						.in("header")
						.name(IdempotencyFilter.HEADER)
						.required(false)
						.description("Tuỳ chọn. Gửi lại cùng một key sẽ nhận lại đúng response của "
								+ "lần gọi trước thay vì tạo thêm dữ liệu trùng — dùng khi client "
								+ "tự thử lại sau khi hết thời gian chờ.")
						.schema(new StringSchema().maxLength(IdempotencyRecord.KEY_MAX_LENGTH))));
	}
}
