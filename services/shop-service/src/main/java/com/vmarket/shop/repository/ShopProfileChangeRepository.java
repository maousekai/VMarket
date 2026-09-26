package com.vmarket.shop.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.vmarket.shop.entity.ShopProfileChange;

public interface ShopProfileChangeRepository extends JpaRepository<ShopProfileChange, String> {

	/** Nhật ký sửa hồ sơ của một gian hàng; thứ tự do {@code Pageable} quyết định. */
	Page<ShopProfileChange> findByShopId(String shopId, Pageable pageable);
}
