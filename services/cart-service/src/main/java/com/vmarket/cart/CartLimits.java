package com.vmarket.cart;

/**
 * Giới hạn nghiệp vụ của giỏ hàng (FR-CART-01).
 *
 * <p>Tồn tại vì hai lý do:
 * <ol>
 *   <li><b>Chặn tràn số.</b> {@code quantity} là {@code int} và phép cộng dồn
 *       {@code existing + request} có thể tràn (vd 2147483647 + 1 → số âm): số
 *       lượng âm làm tổng tiền âm và khiến kiểm tra {@code stock < quantity} ở
 *       FR-CART-03 luôn "đủ hàng". Có trần thì không bao giờ chạm tới ngưỡng tràn.</li>
 *   <li><b>Chặn lạm dụng.</b> Không ai mua 10.000 chiếc áo trong một dòng hàng;
 *       giới hạn giữ dữ liệu giỏ ở mức hợp lý và bảo vệ product-service khỏi
 *       truy vấn bất thường.</li>
 * </ol>
 *
 * <p>Đây là hằng số biên dịch (không phải cấu hình {@code @ConfigurationProperties})
 * vì {@link com.vmarket.cart.dto.AddCartItemRequest} dùng nó trong annotation
 * {@code @Max} — giá trị annotation phải là hằng số lúc biên dịch. Muốn đổi thì
 * sửa một chỗ duy nhất ở đây.
 */
public final class CartLimits {

	/** Số lượng tối đa của MỘT dòng hàng (sau khi cộng dồn). */
	public static final int MAX_QUANTITY_PER_ITEM = 999;

	/** Số dòng hàng tối đa trong một giỏ. */
	public static final int MAX_ITEMS_PER_CART = 50;

	private CartLimits() {
	}
}
