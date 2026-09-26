package com.vmarket.cart.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.vmarket.cart.dto.CheckoutCheckResponse;
import com.vmarket.cart.dto.CheckoutIssueDto;
import com.vmarket.cart.dto.ProductSnapshot;
import com.vmarket.cart.exception.ProductCatalogUnavailableException;

import lombok.extern.slf4j.Slf4j;

/**
 * Kiểm tra giỏ hàng trước thanh toán (FR-CART-03).
 *
 * <p>Với từng item trong giỏ, lấy giá/tồn kho MỚI NHẤT từ product-service (qua
 * API đồng bộ — không đọc CSDL của service khác) rồi so với snapshot trong giỏ:
 * <ul>
 *   <li>Sản phẩm/biến thể đã bị xoá → issue {@code NOT_FOUND}.</li>
 *   <li>Giá snapshot ≠ giá hiện tại → issue {@code PRICE_CHANGED}.</li>
 *   <li>Tồn kho < số lượng trong giỏ → issue {@code OUT_OF_STOCK}.</li>
 *   <li>product-service không trả lời → issue {@code SERVICE_UNAVAILABLE}
 *       (kiểm tra lại khi dịch vụ ổn định, không trả 500 cho người dùng).</li>
 * </ul>
 * Trả về 200 kèm {@code ok=false} + danh sách issue để FE hiển thị cảnh báo.
 */
@Slf4j
@Service
public class CheckoutService {

	public static final String TYPE_NOT_FOUND = "NOT_FOUND";
	public static final String TYPE_PRICE_CHANGED = "PRICE_CHANGED";
	public static final String TYPE_OUT_OF_STOCK = "OUT_OF_STOCK";
	public static final String TYPE_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
	public static final String TYPE_CART_EMPTY = "CART_EMPTY";

	/**
	 * Sản phẩm đã thuộc gian hàng khác so với {@code shopId} lưu trong giỏ.
	 * {@code shopId} trong giỏ do client gửi lúc thêm hàng nên phải đối chiếu với
	 * dữ liệu thật, nếu không item bị nhóm vào sai gian hàng (FR-CART-02) mà
	 * validate vẫn báo {@code ok=true}.
	 */
	public static final String TYPE_SHOP_CHANGED = "SHOP_CHANGED";

	/**
	 * Product Catalog trả dữ liệu thiếu trường bắt buộc (vd không có giá) — không
	 * kiểm tra được đơn giá, và KHÔNG nên coi là "hết hàng" hay "giá đổi".
	 */
	public static final String TYPE_PRODUCT_DATA_INVALID = "PRODUCT_DATA_INVALID";

	private final CartService cartService;
	private final ProductCatalogClient productCatalogClient;

	public CheckoutService(CartService cartService, ProductCatalogClient productCatalogClient) {
		this.cartService = cartService;
		this.productCatalogClient = productCatalogClient;
	}

	/**
	 * Kiểm tra toàn bộ giỏ của người dùng.
	 */
	public CheckoutCheckResponse validate(String userId) {
		List<CartService.StoredItem> items = cartService.items(userId);
		if (items.isEmpty()) {
			return CheckoutCheckResponse.withIssues(List.of(new CheckoutIssueDto(
					null, null, TYPE_CART_EMPTY,
					"Giỏ hàng trống, không thể thanh toán",
					null, null, null, null)));
		}

		List<CheckoutIssueDto> issues = new ArrayList<>();
		for (CartService.StoredItem item : items) {
			Optional<ProductSnapshot> snapshot;
			try {
				snapshot = productCatalogClient.findProduct(item.productId());
			} catch (ProductCatalogUnavailableException ex) {
				// Không kiểm tra được do hạ tầng — báo một issue chung rồi dừng,
				// tránh việc mỗi item sinh một issue không có ý nghĩa với FE.
				log.warn("validate: product-service không khả dụng, dừng kiểm tra userId={}", userId);
				issues.add(new CheckoutIssueDto(
						item.productId(), item.variantId(), TYPE_SERVICE_UNAVAILABLE,
						"Không kiểm tra được giá/tồn kho lúc này, vui lòng thử lại sau",
						item.unitPrice(), null, item.quantity(), null));
				return CheckoutCheckResponse.withIssues(issues);
			}

			if (snapshot.isEmpty()) {
				issues.add(new CheckoutIssueDto(
						item.productId(), item.variantId(), TYPE_NOT_FOUND,
						"Sản phẩm không còn tồn tại, hãy xoá khỏi giỏ hàng",
						item.unitPrice(), null, item.quantity(), null));
				continue;
			}

			ProductSnapshot product = snapshot.get();

			// shopId trong giỏ do CLIENT gửi lúc thêm hàng, nên phải đối chiếu với dữ
			// liệu thật của Product Catalog: nếu không, item bị nhóm vào sai gian hàng
			// (FR-CART-02) mà validate vẫn trả ok=true (P2 của review PR #23).
			// Chỉ so khi cả hai phía đều có giá trị — product-service cũ có thể chưa trả
			// shopId, khi đó bỏ qua chứ không chặn oan.
			if (product.shopId() != null && item.shopId() != null
					&& !product.shopId().equals(item.shopId())) {
				issues.add(new CheckoutIssueDto(
						item.productId(), item.variantId(), TYPE_SHOP_CHANGED,
						"Sản phẩm hiện thuộc gian hàng khác, hãy xoá khỏi giỏ và thêm lại",
						item.unitPrice(), null, item.quantity(), null));
				continue;
			}

			PriceStock current = resolvePriceStock(product, item.variantId());
			if (current == null) {
				issues.add(new CheckoutIssueDto(
						item.productId(), item.variantId(), TYPE_NOT_FOUND,
						"Biến thể không còn tồn tại, hãy xoá khỏi giỏ hàng",
						item.unitPrice(), null, item.quantity(), null));
				continue;
			}

			// Thiếu giá = phản hồi sai hợp đồng, không phải giá 0. Không kiểm tra thì
			// compareTo(null) ném NPE → HTTP 500 (P2 của review PR #23).
			if (current.price() == null) {
				issues.add(new CheckoutIssueDto(
						item.productId(), item.variantId(), TYPE_PRODUCT_DATA_INVALID,
						"Dữ liệu sản phẩm không hợp lệ (thiếu giá), vui lòng thử lại sau",
						item.unitPrice(), null, item.quantity(), null));
				continue;
			}

			if (item.unitPrice().compareTo(current.price()) != 0) {
				issues.add(new CheckoutIssueDto(
						item.productId(), item.variantId(), TYPE_PRICE_CHANGED,
						"Giá đã thay đổi từ " + item.unitPrice() + " thành " + current.price(),
						item.unitPrice(), current.price(), item.quantity(), null));
			}
			if (current.stock() < item.quantity()) {
				issues.add(new CheckoutIssueDto(
						item.productId(), item.variantId(), TYPE_OUT_OF_STOCK,
						"Chỉ còn " + current.stock() + " sản phẩm, giỏ đang có " + item.quantity(),
						item.unitPrice(), current.price(), item.quantity(), current.stock()));
			}
		}
		return CheckoutCheckResponse.withIssues(issues);
	}

	/**
	 * Chọn giá/tồn kho theo biến thể (nếu item có variantId) hoặc của chính
	 * sản phẩm. Trả {@code null} khi biến thể không tìm thấy.
	 */
	private static PriceStock resolvePriceStock(ProductSnapshot product, String variantId) {
		if (variantId == null) {
			return new PriceStock(product.price(), orZero(product.stock()));
		}
		return product.variants() == null ? null : product.variants().stream()
				.filter(v -> variantId.equals(v.variantId()))
				.findFirst()
				.map(v -> new PriceStock(v.price(), orZero(v.stock())))
				.orElse(null);
	}

	private static int orZero(Integer stock) {
		return stock == null ? 0 : stock;
	}

	private record PriceStock(BigDecimal price, int stock) {
	}
}
