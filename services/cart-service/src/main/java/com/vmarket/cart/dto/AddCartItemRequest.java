package com.vmarket.cart.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.vmarket.cart.CartLimits;

/**
 * Request thêm một sản phẩm vào giỏ hàng.
 *
 * <p>{@code unitPrice} là giá tại thời điểm thêm (snapshot). Giá "mới nhất" được
 * kiểm tra lại ở FR-CART-03 qua {@code POST /api/cart/validate}, nên client ghi
 * giá nào vào đây thì tạm tính theo giá đó cho tới khi kiểm tra lại.
 *
 * @param productId sản phẩm (định danh do product-service cấp)
 * @param variantId biến thể — trống khi sản phẩm không có biến thể
 * @param shopId    gian hàng bán sản phẩm — dùng để nhóm giỏ (FR-CART-02)
 * @param quantity  số lượng, từ 1 tới {@link CartLimits#MAX_QUANTITY_PER_ITEM}
 * @param unitPrice đơn giá snapshot
 */
public record AddCartItemRequest(
		@NotBlank(message = "productId không được để trống") String productId,
		String variantId,
		@NotBlank(message = "shopId không được để trống") String shopId,
		@NotNull(message = "quantity không được để trống")
		@Min(value = 1, message = "quantity phải lớn hơn hoặc bằng 1")
		@Max(value = CartLimits.MAX_QUANTITY_PER_ITEM,
				message = "quantity tối đa " + CartLimits.MAX_QUANTITY_PER_ITEM + " cho mỗi sản phẩm")
		Integer quantity,
		@NotNull(message = "unitPrice không được để trống")
		@DecimalMin(value = "0.00", message = "unitPrice phải lớn hơn hoặc bằng 0") BigDecimal unitPrice) {
}
