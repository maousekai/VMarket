package com.vmarket.review.entity;

/** Trạng thái hiển thị của đánh giá. Chỉ {@link #VISIBLE} được tính vào điểm trung bình. */
public enum ReviewStatus {
	/** Hiển thị công khai (mặc định khi tạo). */
	VISIBLE,
	/** Admin đã ẩn vì vi phạm (FR-REV-04) — không hiển thị, không tính điểm. */
	HIDDEN,
	/**
	 * Đơn đã trả hàng / hoàn tiền (Order Service phát {@code ReturnResolved}) — đánh giá chỉ có giá trị
	 * từ khi nhận hàng tới trước khi trả hàng, nên không hiển thị, không tính điểm và admin không khôi
	 * phục được.
	 */
	RETURNED
}
