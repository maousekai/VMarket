package com.vmarket.events;

/**
 * Danh mục tên sự kiện DÙNG CHUNG trên Event Bus.
 *
 * <p>Quy ước (PBL6-39): mỗi sự kiện có một tên duy nhất dạng PascalCase (khớp
 * danh mục SRS §8.1 Phụ lục A), tên này ĐỒNG THỜI là routing key khi publish
 * lên topic exchange {@code vmarket.events}. Các hằng số dưới đây KHÔNG liệt kê
 * hết — service có thể thêm nhưng phải cập nhật vào tài liệu {@code docs/event-bus.md}.
 */
public final class EventType {

	/** Product Catalog phát khi tạo sản phẩm mới → AI Search / Recommendation đồng bộ chỉ mục. */
	public static final String PRODUCT_CREATED = "ProductCreated";
	/** Product Catalog phát khi cập nhật sản phẩm. */
	public static final String PRODUCT_UPDATED = "ProductUpdated";
	/** Product Catalog phát khi xoá / gỡ sản phẩm. */
	public static final String PRODUCT_DELETED = "ProductDeleted";

	/** Shop Service phát khi duyệt gian hàng thành công. */
	public static final String SHOP_APPROVED = "ShopApproved";
	/** Shop Service phát khi đình chỉ gian hàng. */
	public static final String SHOP_SUSPENDED = "ShopSuspended";

	/** Order Service phát khi tạo đơn hàng. */
	public static final String ORDER_PLACED = "OrderPlaced";

	/** Product Catalog phát khi tạm giữ / giải phóng tồn kho theo đơn. */
	public static final String STOCK_RESERVED = "StockReserved";
	public static final String STOCK_RELEASED = "StockReleased";

	/** Payment Service phát theo kết quả giao dịch (PayOS webhook). */
	public static final String PAYMENT_SUCCEEDED = "PaymentSucceeded";
	public static final String PAYMENT_FAILED = "PaymentFailed";

	/** Delivery Service phát khi phân công shipper. */
	public static final String DELIVERY_ASSIGNED = "DeliveryAssigned";

	/** Review Service phát khi có đánh giá mới. */
	public static final String REVIEW_CREATED = "ReviewCreated";

	/** Order Service phát khi có yêu cầu trả hàng / hoàn tất trả hàng. */
	public static final String RETURN_REQUESTED = "ReturnRequested";
	public static final String RETURN_RESOLVED = "ReturnResolved";

	/** Gateway / Clients phát khi ghi nhận hành vi xem, thêm giỏ, mua của người dùng → Recommendation huấn luyện gợi ý. */
	public static final String USER_BEHAVIOR_TRACKED = "UserBehaviorTracked";

	private EventType() {
	}
}