package com.vmarket.order.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.order.config.InternalApiProperties;
import com.vmarket.order.entity.ReturnStatus;
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderReturnRepository;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;

/**
 * Endpoint nội bộ cho review-service: sản phẩm nào của một đơn đã được trả hàng xong
 * ({@code RESOLVED}). Review-service dùng để đối chiếu dữ liệu trả hàng cũ chỉ ghi theo đơn
 * (PBL6-35). Bảo vệ bằng {@code X-Internal-Api-Key} như các endpoint {@code /internal/**} khác.
 */
@Hidden
@RestController
@RequestMapping("/internal/orders")
@RequiredArgsConstructor
public class InternalReturnController {

	private final OrderReturnRepository returns;
	private final InternalApiProperties properties;

	@GetMapping("/{orderId}/returned-products")
	@Transactional(readOnly = true)
	public ReturnedProducts returnedProducts(@PathVariable String orderId,
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String key) {
		if (key == null || !MessageDigest.isEqual(properties.getApiKey().getBytes(StandardCharsets.UTF_8),
				key.getBytes(StandardCharsets.UTF_8))) {
			throw new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Sai khoá nội bộ");
		}
		List<String> products = returns.findByOrderId(orderId).stream()
				.filter(r -> r.getStatus() == ReturnStatus.RESOLVED)
				.flatMap(r -> r.getItems().stream())
				.map(item -> item.getProductId())
				.distinct()
				.toList();
		return new ReturnedProducts(orderId, products);
	}

	public record ReturnedProducts(String orderId, List<String> productIds) {
	}
}
