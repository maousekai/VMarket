package com.vmarket.user.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.user.entity.UserProfile;

public interface UserProfileRepository extends JpaRepository<UserProfile, String> {

	Optional<UserProfile> findByUserId(String userId);

	boolean existsByUserId(String userId);

	/**
	 * Tìm kiếm hồ sơ cho Admin (FR-USER-04) theo họ tên hoặc số điện thoại.
	 *
	 * <p>{@code lower(...) like lower(...)} thay vì {@code ilike} để câu truy vấn
	 * chạy được trên cả PostgreSQL (chạy thật) lẫn H2 (test) — {@code ilike} là cú
	 * pháp riêng của PostgreSQL.
	 *
	 * <p>Tham số {@code q} đã được service chuẩn hoá và bọc sẵn dấu {@code %}. Đây
	 * là truy vấn có tham số nên không có nguy cơ SQL injection; ký tự {@code %}
	 * hay {@code _} người dùng nhập vào chỉ làm kết quả rộng hơn, không phá cú pháp.
	 */
	@Query("""
			select p from UserProfile p
			where :q is null
			   or lower(p.fullName) like lower(:q)
			   or lower(p.phone) like lower(:q)
			""")
	Page<UserProfile> search(@Param("q") String q, Pageable pageable);
}
