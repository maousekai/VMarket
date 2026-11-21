package com.vmarket.order.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.vmarket.order.client.CartServiceClient.CartItemView;
import com.vmarket.order.client.ProductServiceClient;
import com.vmarket.order.client.ProductServiceClient.ProductPrices;
import com.vmarket.order.exception.ApiException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Định giá lại dòng hàng từ product-service lúc đặt hàng (PBL6-48).
 *
 * <p>Giá trong giỏ chỉ là snapshot lúc thêm hàng, có thể cũ hoặc bị sửa — không phải
 * nguồn sự thật. Mỗi dòng được so với giá hiện tại:
 * <ul>
 *   <li>Sản phẩm/biến thể không còn, không xác định được giá (hoặc giá không dương), hoặc
 *       gian hàng trong giỏ khác gian hàng thật → 409 {@code PRODUCT_UNAVAILABLE}.</li>
 *   <li>Giá khác giá trong giỏ → 409 {@code PRICE_CHANGED}.</li>
 *   <li>product-service lỗi → 503 {@code PRODUCT_SERVICE_UNAVAILABLE} (từ client).</li>
 * </ul>
 * Mọi trường hợp lỗi đều ném trước khi tạo đơn, nên không có đơn nào được tạo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPricing {

	private static final int MAX_LISTED = 5;

	private final ProductServiceClient productServiceClient;

	/**
	 * @return dòng hàng mang giá hiện tại, theo khoá {@link #lineKey}
	 * @throws ApiException 409 khi lệch giá / sản phẩm không còn bán; 503 khi không đọc được giá
	 */
	public Map<String, CartItemView> reprice(List<CartItemView> lines) {
		Map<String, Optional<ProductPrices>> products = new HashMap<>();
		Map<String, CartItemView> priced = new LinkedHashMap<>();
		List<String> unavailable = new ArrayList<>();
		List<String> changed = new ArrayList<>();

		for (CartItemView line : lines) {
			Optional<ProductPrices> product = products.computeIfAbsent(line.productId(),
					productServiceClient::findProduct);
			BigDecimal current = product.map(p -> p.priceOf(line.variantId())).orElse(null);
			// Giá phải dương (product-service đã kiểm, đây là lớp phòng thủ thêm).
			if (current == null || current.signum() <= 0) {
				unavailable.add(line.productId());
				continue;
			}
			// Gian hàng nhận tiền cũng lấy theo product-service: shopId trong giỏ của các
			// dòng thêm trước bản sửa này là do client gửi.
			String shopId = product.get().shopId();
			if (shopId == null || !shopId.equals(line.shopId())) {
				log.warn("shopId trong giỏ ({}) khác product-service ({}) cho productId={}", line.shopId(),
						shopId, line.productId());
				unavailable.add(line.productId());
				continue;
			}
			if (line.unitPrice() == null || line.unitPrice().compareTo(current) != 0) {
				log.info("Giá đổi cho productId={} variantId={}: giỏ={} hiện tại={}", line.productId(),
						line.variantId(), line.unitPrice(), current);
				changed.add(line.productId());
			}
			priced.put(lineKey(line.productId(), line.variantId()), new CartItemView(line.productId(),
					line.variantId(), line.shopId(), line.quantity(), current,
					current.multiply(BigDecimal.valueOf(line.quantity()))));
		}

		if (!unavailable.isEmpty()) {
			throw ApiException.conflict("PRODUCT_UNAVAILABLE",
					"Sản phẩm không còn bán hoặc chưa xác định được giá: " + summarize(unavailable)
							+ ". Hãy kiểm tra lại giỏ hàng");
		}
		if (!changed.isEmpty()) {
			throw ApiException.conflict("PRICE_CHANGED",
					"Giá đã thay đổi cho sản phẩm: " + summarize(changed)
							+ ". Hãy kiểm tra lại giỏ hàng rồi đặt lại");
		}
		return priced;
	}

	/** Thay từng dòng bằng bản đã định giá (cùng thứ tự). */
	static List<CartItemView> apply(List<CartItemView> lines, Map<String, CartItemView> priced) {
		return lines.stream()
				.map(line -> Objects.requireNonNull(priced.get(lineKey(line.productId(), line.variantId())),
						"Dòng hàng chưa được định giá"))
				.toList();
	}

	static String lineKey(String productId, String variantId) {
		return productId + "::" + Objects.toString(variantId, "");
	}

	private static String summarize(List<String> productIds) {
		List<String> distinct = productIds.stream().distinct().toList();
		String listed = String.join(", ", distinct.subList(0, Math.min(MAX_LISTED, distinct.size())));
		return distinct.size() > MAX_LISTED ? listed + " và " + (distinct.size() - MAX_LISTED) + " sản phẩm khác"
				: listed;
	}
}
