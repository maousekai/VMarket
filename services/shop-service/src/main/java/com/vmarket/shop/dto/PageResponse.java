package com.vmarket.shop.dto;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Bọc kết quả phân trang thành hình dạng ổn định của riêng dự án (cùng hình dạng với
 * {@code PageResponse} của user-service).
 *
 * <p>Cố ý <b>không</b> trả thẳng {@code Page} của Spring Data: JSON của nó chứa cấu
 * trúc {@code pageable}/{@code sort} nội bộ, từng đổi hình dạng giữa các phiên bản và
 * Spring cảnh báo là không ổn định để serialize.
 */
@Schema(name = "PageResponse", description = "Kết quả phân trang")
public record PageResponse<T>(

		List<T> items,

		@Schema(example = "0", description = "Trang hiện tại, đánh số từ 0") int page,

		@Schema(example = "20") int size,

		@Schema(example = "137", description = "Tổng số bản ghi khớp điều kiện") long totalElements,

		@Schema(example = "7") int totalPages) {

	public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
		return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}
}
