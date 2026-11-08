package com.vmarket.cart.dto;

import java.math.BigDecimal;

/**
 * Một dòng hàng của giỏ đúng như {@code CheckoutService.validate} đã kiểm tra.
 *
 * <p>Trả kèm trong {@link CheckoutCheckResponse#items()} để order-service dựng đơn từ CHÍNH
 * danh sách này thay vì đọc giỏ lần thứ hai (tránh sửa giỏ xen giữa hai lần đọc làm đơn lệch
 * với thứ đã được kiểm tra giá/shop/tồn kho).
 *
 * @param productId sản phẩm
 * @param variantId biến thể (có thể {@code null})
 * @param shopId    gian hàng đã được đối chiếu với Product Catalog
 * @param quantity  số lượng
 * @param unitPrice đơn giá snapshot trong giỏ (đã so với giá hiện tại)
 */
public record CheckoutItemDto(
		String productId,
		String variantId,
		String shopId,
		int quantity,
		BigDecimal unitPrice) {
}
