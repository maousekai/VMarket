package com.vmarket.user.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Bọc kết quả phân trang thành hình dạng ổn định của riêng dự án.
 *
 * <p>Cố ý <b>không</b> trả thẳng {@code Page} của Spring Data ra API: JSON của nó
 * chứa cả cấu trúc {@code pageable}/{@code sort} nội bộ, đã từng đổi hình dạng giữa
 * các phiên bản Spring và Spring cũng cảnh báo là không ổn định để serialize. Trả
 * record này thì hợp đồng API do mình kiểm soát.
 */
@Schema(name = "PageResponse", description = "Kết quả phân trang")
public record PageResponse<T>(

		List<T> items,

		@Schema(example = "0", description = "Trang hiện tại, đánh số từ 0") int page,

		@Schema(example = "20") int size,

		@Schema(example = "137", description = "Tổng số bản ghi khớp điều kiện") long totalElements,

		@Schema(example = "7") int totalPages) {
}
