package com.vmarket.cart.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Hình dạng thật của {@code GET /api/products/{productId}} bên product-service
 * ({@code ProductDetailResponse{product, similarProducts}}). Chỉ khai báo các
 * trường cart-service cần; trường khác bị bỏ qua để product-service thêm trường
 * mới không làm hỏng việc đọc.
 *
 * <p>Tồn kho dùng {@code availableStock} (= stock − reservedStock): số còn bán được,
 * không phải tổng tồn kho vật lý.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDetailPayload(Product product) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Product(
			String id,
			String shopId,
			Long availableStock,
			List<Variant> variants) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Variant(
			String id,
			BigDecimal price,
			Long availableStock) {
	}

	/**
	 * Chuyển sang snapshot nội bộ mà {@code CheckoutService} kiểm tra.
	 *
	 * <p>Mọi sản phẩm đều có ít nhất một biến thể. Item trong giỏ không có
	 * {@code variantId} chỉ hợp lệ khi sản phẩm có đúng MỘT biến thể; khi có nhiều
	 * biến thể thì không biết lấy giá nào, nên để {@code price=null} và
	 * {@code CheckoutService} báo {@code PRODUCT_DATA_INVALID} thay vì đoán.
	 *
	 * @return {@code null} khi body thiếu {@code product}
	 */
	public ProductSnapshot toSnapshot() {
		if (product == null) {
			return null;
		}
		List<Variant> source = product.variants() == null ? List.of() : product.variants();
		List<ProductSnapshot.VariantSnapshot> variants = source.stream()
				.map(v -> new ProductSnapshot.VariantSnapshot(v.id(), v.price(), toInt(v.availableStock())))
				.toList();
		BigDecimal singlePrice = source.size() == 1 ? source.get(0).price() : null;
		return new ProductSnapshot(product.id(), product.shopId(), singlePrice,
				toInt(product.availableStock()), variants);
	}

	private static Integer toInt(Long value) {
		if (value == null) {
			return null;
		}
		return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
	}
}
