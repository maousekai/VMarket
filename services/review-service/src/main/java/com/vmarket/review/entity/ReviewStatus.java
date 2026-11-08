package com.vmarket.review.entity;

/** Trạng thái hiển thị của đánh giá. Chỉ {@link #VISIBLE} được tính vào điểm trung bình. */
public enum ReviewStatus {

	/** Hiển thị công khai (mặc định khi tạo). */
	VISIBLE,

	/** Admin đã ẩn vì vi phạm (FR-REV-04) — không hiển thị, không tính điểm. */
	HIDDEN
}
