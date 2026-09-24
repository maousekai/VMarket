package com.vmarket.shop.repository;

import org.springframework.data.jpa.domain.Specification;

import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopStatus;

/** Bộ lọc danh sách gian hàng cho Admin (FR-SHOP-04). */
public final class ShopSpecifications {

	private static final char LIKE_ESCAPE = '\\';

	private ShopSpecifications() {
	}

	/**
	 * Lọc theo trạng thái (bỏ qua nếu {@code null}) và tên chứa {@code keyword} (không
	 * phân biệt hoa thường, bỏ qua nếu rỗng).
	 *
	 * <p>So trên {@code nameKey} (đã chuẩn hoá + viết thường) bằng cùng phép chuẩn hoá
	 * với {@link Shop#nameKeyOf} — không cần {@code lower()} phía CSDL. Ký tự {@code %}
	 * và {@code _} trong từ khoá được escape: người dùng gõ "50%" là tìm đúng "50%",
	 * không phải "mọi tên bắt đầu bằng 50".
	 *
	 * <p>TODO (khi số gian hàng lớn): {@code LIKE '%tu-khoa%'} không dùng được B-tree
	 * index nên PostgreSQL phải quét toàn bảng. Lúc đó bật extension {@code pg_trgm} và
	 * tạo GIN index trên {@code name_key}. Ở quy mô hiện tại (danh sách kiểm duyệt của
	 * Admin) thì quét bảng vẫn rẻ hơn chi phí duy trì thêm index.
	 */
	public static Specification<Shop> matching(ShopStatus status, String keyword) {
		return (root, query, cb) -> {
			var predicate = cb.conjunction();
			if (status != null) {
				predicate = cb.and(predicate, cb.equal(root.get("status"), status));
			}
			if (keyword != null && !keyword.isBlank()) {
				String pattern = "%" + escapeLike(Shop.nameKeyOf(keyword)) + "%";
				predicate = cb.and(predicate, cb.like(root.get("nameKey"), pattern, LIKE_ESCAPE));
			}
			return predicate;
		};
	}

	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
