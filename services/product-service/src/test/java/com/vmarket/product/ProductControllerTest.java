package com.vmarket.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.product.dto.ProductRequest;
import com.vmarket.product.service.ProductCatalogService;

@SpringBootTest
@AutoConfigureMockMvc
class ProductControllerTest {
	@Autowired MockMvc mockMvc;
	@MockitoBean ProductCatalogService service;

	@Test
	void sellerCanSubmitValidProduct() throws Exception {
		mockMvc.perform(post("/api/products")
				.header("X-User-Id", "seller-1")
				.header("X-User-Roles", "ROLE_SELLER")
				.header("Idempotency-Key", "create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validBody("[\"https://img/1.jpg\"]")))
				.andExpect(status().isCreated());

		verify(service).create(eq("seller-1"), eq("create-1"), any(ProductRequest.class));
	}

	@Test
	void productWithMoreThanNineImagesIsRejectedBeforeServiceCall() throws Exception {
		String images = "[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\"]";
		mockMvc.perform(post("/api/products")
				.header("X-User-Id", "seller-1")
				.header("X-User-Roles", "SELLER")
				.header("Idempotency-Key", "create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validBody(images)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		verify(service, never()).create(any(), any(), any());
	}

	@Test
	void missingSellerRoleIsRejected() throws Exception {
		mockMvc.perform(post("/api/products")
				.header("X-User-Id", "buyer-1")
				.header("X-User-Roles", "BUYER")
				.header("Idempotency-Key", "create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validBody("[\"https://img/1.jpg\"]")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void fractionalVndPriceIsRejected() throws Exception {
		mockMvc.perform(post("/api/products")
				.header("X-User-Id", "seller-1")
				.header("X-User-Roles", "SELLER")
				.header("Idempotency-Key", "create-fractional")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validBody("[\"https://img/1.jpg\"]").replace("100000", "100000.5")))
				.andExpect(status().isBadRequest());
		verify(service, never()).create(any(), any(), any());
	}

	@Test
	void unsupportedCurrencyIsRejected() throws Exception {
		mockMvc.perform(post("/api/products")
				.header("X-User-Id", "seller-1")
				.header("X-User-Roles", "SELLER")
				.header("Idempotency-Key", "create-usd")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validBody("[\"https://img/1.jpg\"]").replace("\"VND\"", "\"USD\"")))
				.andExpect(status().isBadRequest());
	}

	private String validBody(String imageUrls) {
		return """
				{
				  "shopId":"shop-1",
				  "name":"Áo thun",
				  "description":"Mô tả",
				  "imageUrls":%s,
				  "categoryId":"cat-1",
				  "status":"ACTIVE",
				  "currency":"VND",
				  "variants":[{"sku":"SKU-1","attributes":{"size":"M"},"price":100000,"stock":5}]
				}
				""".formatted(imageUrls);
	}
}
