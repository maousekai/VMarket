package com.vmarket.shop.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vmarket.shop.entity.ShopStatusHistory;

public interface ShopStatusHistoryRepository extends JpaRepository<ShopStatusHistory, String> {

	/** Lịch sử theo thứ tự thời gian (cũ trước) — đọc như một dòng thời gian. */
	List<ShopStatusHistory> findByShopIdOrderByCreatedAtAscIdAsc(String shopId);
}
