package com.vmarket.order.entity;

/**
 * Trạng thái của một yêu cầu trả hàng (FR-ORD-06, FR-ORD-07).
 */
public enum ReturnStatus {
	/** Buyer vừa gửi yêu cầu, chờ Seller phản hồi. */
	PENDING,

	/** Seller chấp nhận yêu cầu trả hàng, chờ thu hồi hàng. */
	APPROVED,

	/** Seller từ chối yêu cầu trả hàng (kèm lý do). */
	REJECTED,

	/** Buyer khiếu nại quyết định từ chối của Seller, chờ Admin phân xử. */
	DISPUTED,

	/** Yêu cầu trả hàng đã được giải quyết thành công (đã thu hồi và kích hoạt hoàn tiền). */
	RESOLVED,

	/** Buyer tự hủy yêu cầu trả hàng. */
	CANCELLED
}

