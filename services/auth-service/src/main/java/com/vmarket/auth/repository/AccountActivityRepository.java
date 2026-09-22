package com.vmarket.auth.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.vmarket.auth.entity.AccountActivity;

public interface AccountActivityRepository extends JpaRepository<AccountActivity, String> {

	/** Lịch sử một tài khoản; thứ tự do {@code pageable} quyết định (service: mới nhất trước). */
	Page<AccountActivity> findByUserId(String userId, Pageable pageable);
}
