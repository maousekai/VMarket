package com.vmarket.events;

/**
 * Payload sự kiện {@code ShopApproved} — Shop Service phát khi một gian hàng chuyển
 * sang trạng thái "Hoạt động" (FR-SHOP-04, PBL6-14).
 *
 * <p>Service nhận (SRS §8.1): Auth (cấp vai trò SELLER cho chủ gian hàng), Product
 * (hiện lại sản phẩm của gian hàng), Notification (báo kết quả cho người đăng ký).
 *
 * <p>Phát trong hai trường hợp, phân biệt bằng {@code reinstated}:
 * <ul>
 *   <li>{@code false} — Admin duyệt hồ sơ đang "Chờ duyệt" (lần đầu hoặc sau khi
 *       người bán gửi lại hồ sơ bị từ chối).</li>
 *   <li>{@code true} — Admin gỡ đình chỉ một gian hàng từng hoạt động. Với Product
 *       hai trường hợp như nhau (hiện sản phẩm), nhưng Notification cần biết để gửi
 *       đúng nội dung.</li>
 * </ul>
 *
 * <p>Không có trường thời gian: thời điểm duyệt chính là {@code timestamp} của
 * {@link EventEnvelope}.
 *
 * @param shopId     id gian hàng (ULID)
 * @param ownerId    userId của chủ gian hàng — người được cấp vai trò SELLER
 * @param shopName   tên gian hàng tại thời điểm duyệt (để hiển thị trong thông báo)
 * @param approvedBy userId của Admin thực hiện
 * @param reinstated {@code true} nếu đây là gỡ đình chỉ, {@code false} nếu là duyệt hồ sơ
 */
public record ShopApproved(
		String shopId,
		String ownerId,
		String shopName,
		String approvedBy,
		boolean reinstated) {
}
