package com.vmarket.shop.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.shop.dto.ShopOwnerResponse;
import com.vmarket.shop.exception.ApiException;
import com.vmarket.shop.repository.ShopRepository;

/**
 * API nội bộ cho notification-service (PBL6-23): tra chủ gian hàng từ {@code shopId} để
 * báo cho Seller khi có đơn. Trả mọi trạng thái (kể cả bị đình chỉ) vì đơn cũ vẫn cần
 * báo cho chủ shop. Xác thực bằng {@code X-Internal-Api-Key}, so sánh constant-time.
 */
@RestController
@RequestMapping("/internal/shops")
public class InternalShopController {

	private final ShopRepository shops;
	private final byte[] apiKey;

	public InternalShopController(ShopRepository shops, @Value("${app.internal-api-key}") String apiKey) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("app.internal-api-key (INTERNAL_API_KEY) không được để trống");
		}
		this.shops = shops;
		this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
	}

	@GetMapping("/{shopId}/owner")
	@Transactional(readOnly = true)
	public ShopOwnerResponse owner(@PathVariable String shopId,
			@RequestHeader(value = "X-Internal-Api-Key", required = false) String key) {
		if (key == null || !MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8))) {
			throw new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Sai khoá nội bộ");
		}
		return shops.findById(shopId)
				.map(shop -> new ShopOwnerResponse(shop.getId(), shop.getOwnerId(), shop.getName()))
				.orElseThrow(() -> ApiException.notFound("SHOP_NOT_FOUND", "Không tìm thấy gian hàng"));
	}
}
