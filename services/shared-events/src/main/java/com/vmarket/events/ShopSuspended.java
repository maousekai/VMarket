package com.vmarket.events;

/**
 * Payload sự kiện {@code ShopSuspended} — Shop Service phát khi Admin đình chỉ một
 * gian hàng đang hoạt động (FR-SHOP-04, PBL6-14).
 *
 * <p>Service nhận (SRS §8.1): Product (ẩn toàn bộ sản phẩm của gian hàng khỏi phía
 * người mua — UC-15), Notification (báo cho chủ gian hàng kèm lý do), Auth.
 *
 * @param shopId      id gian hàng (ULID)
 * @param ownerId     userId của chủ gian hàng
 * @param shopName    tên gian hàng tại thời điểm đình chỉ
 * @param suspendedBy userId của Admin thực hiện
 * @param reason      lý do đình chỉ (bắt buộc khi Admin thao tác)
 */
public record ShopSuspended(
		String shopId,
		String ownerId,
		String shopName,
		String suspendedBy,
		String reason) {
}
