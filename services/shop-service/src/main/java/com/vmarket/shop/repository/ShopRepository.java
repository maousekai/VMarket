package com.vmarket.shop.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopStatus;

/**
 * Tìm kiếm cho Admin dùng {@link JpaSpecificationExecutor} (xem {@code ShopSpecifications})
 * thay vì một câu JPQL kiểu {@code (:status is null or s.status = :status)}: tham số
 * {@code null} không rõ kiểu là nguồn lỗi quen thuộc trên PostgreSQL
 * ({@code could not determine data type of parameter}), còn Specification chỉ thêm
 * điều kiện khi thật sự có bộ lọc.
 */
public interface ShopRepository extends JpaRepository<Shop, String>, JpaSpecificationExecutor<Shop> {

	/** Gian hàng của một người — mọi endpoint {@code /me} đi qua đây (chống IDOR). */
	Optional<Shop> findByOwnerId(String ownerId);

	boolean existsByOwnerId(String ownerId);

	/** Trang công khai chỉ thấy gian hàng đang hoạt động. */
	Optional<Shop> findByIdAndStatus(String id, ShopStatus status);

	boolean existsByNameKey(String nameKey);

	boolean existsByNameKeyAndIdNot(String nameKey, String id);
}
