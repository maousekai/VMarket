package com.vmarket.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.vmarket.cart.CartLimits;

/**
 * Request thêm một sản phẩm vào giỏ hàng.
 *
 * <p><b>Không có giá.</b> Đơn giá luôn lấy từ product-service lúc thêm hàng (PBL6-48):
 * trước đây client tự gửi {@code unitPrice} (cho phép cả 0) và giá đó đi thẳng vào
 * đơn hàng. Client cũ còn gửi trường {@code unitPrice} thì bị bỏ qua.
 *
 * @param productId sản phẩm (định danh do product-service cấp)
 * @param variantId biến thể — trống khi sản phẩm chỉ có một biến thể
 * @param shopId    gian hàng client thấy; gian hàng thật lấy theo product-service
 * @param quantity  số lượng, từ 1 tới {@link CartLimits#MAX_QUANTITY_PER_ITEM}
 */
public record AddCartItemRequest(
		@NotBlank(message = "productId không được để trống") String productId,
		String variantId,
		@NotBlank(message = "shopId không được để trống") String shopId,
		@NotNull(message = "quantity không được để trống")
		@Min(value = 1, message = "quantity phải lớn hơn hoặc bằng 1")
		@Max(value = CartLimits.MAX_QUANTITY_PER_ITEM,
				message = "quantity tối đa " + CartLimits.MAX_QUANTITY_PER_ITEM + " cho mỗi sản phẩm")
		Integer quantity) {
}
