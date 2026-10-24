package com.vmarket.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.product.dto.CategoryRequest;
import com.vmarket.product.service.BrandService;
import com.vmarket.product.service.CategoryService;
import com.vmarket.product.service.ProductCatalogService;

/**
 * PBL6-49: danh tính chỉ đến từ access token đã verify, không từ header
 * {@code X-User-Id} / {@code X-User-Roles} do client tự gửi.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductSecurityTest {
	private static final String CATEGORY_BODY = "{\"name\":\"Áo\",\"sortOrder\":0,\"active\":true}";

	@Autowired MockMvc mockMvc;
	@MockitoBean ProductCatalogService productService;
	@MockitoBean CategoryService categoryService;
	@MockitoBean BrandService brandService;

	@Test
	void spoofedAdminHeaderWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(post("/api/products/admin/query")
				.header("X-User-Id", "attacker")
				.header("X-User-Roles", "ADMIN"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(productService);
	}

	@Test
	void spoofedAdminHeaderCannotCreateCategory() throws Exception {
		mockMvc.perform(post("/api/products/categories")
				.header("X-User-Roles", "ROLE_ADMIN")
				.contentType(MediaType.APPLICATION_JSON)
				.content(CATEGORY_BODY))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(categoryService);
	}

	@Test
	void sellerTokenOnAdminRouteIsForbidden() throws Exception {
		mockMvc.perform(post("/api/products/brands/admin/query")
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer("seller-1", "SELLER")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		verifyNoInteractions(brandService);
	}

	@Test
	void sellerTokenWithSpoofedAdminHeaderIsStillForbidden() throws Exception {
		mockMvc.perform(post("/api/products/admin/query")
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer("seller-1", "SELLER"))
				.header("X-User-Roles", "ADMIN"))
				.andExpect(status().isForbidden());

		verifyNoInteractions(productService);
	}

	@Test
	void adminTokenCanCreateCategory() throws Exception {
		mockMvc.perform(post("/api/products/categories")
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer("admin-1", "ADMIN"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(CATEGORY_BODY))
				.andExpect(status().isCreated());

		verify(categoryService).create(any(CategoryRequest.class));
	}

	@Test
	void sellerIdComesFromTokenNotFromHeader() throws Exception {
		mockMvc.perform(post("/api/products/seller/query")
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer("seller-1", "SELLER"))
				.header("X-User-Id", "someone-else"))
				.andExpect(status().isOk());

		verify(productService).sellerProducts(eq("seller-1"));
	}

	@Test
	void tokenFromAnotherIssuerIsUnauthorized() throws Exception {
		mockMvc.perform(post("/api/products/admin/query")
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearerWrongIssuer("admin-1", "ADMIN")))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(productService);
	}

	@Test
	void expiredTokenIsUnauthorized() throws Exception {
		mockMvc.perform(post("/api/products/admin/query")
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearerExpired("admin-1", "ADMIN")))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(productService);
	}

	@Test
	void publicCatalogReadsNeedNoTokenAndStayCacheable() throws Exception {
		mockMvc.perform(get("/api/products/categories"))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
		mockMvc.perform(get("/api/products/brands")).andExpect(status().isOk());
	}

	@Test
	void unauthenticatedWriteIsRejectedBeforeValidation() throws Exception {
		mockMvc.perform(post("/api/products")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{not json"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.path").value("/api/products"));

		verifyNoInteractions(productService);
	}

	@Test
	void unauthorizedResponseCarriesCorsHeaders() throws Exception {
		mockMvc.perform(post("/api/products/admin/query")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
	}

	@Test
	void internalInventoryRouteStillUsesApiKeyNotToken() throws Exception {
		mockMvc.perform(post("/api/products/inventory/reservations/order-1/confirm"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_INTERNAL_API_KEY"));
	}

	@Test
	void openApiDeclaresBearerScheme() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(jsonPath("$.paths['/api/products/admin/query'].post.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/products/categories'].get.security").doesNotExist());
	}
}
