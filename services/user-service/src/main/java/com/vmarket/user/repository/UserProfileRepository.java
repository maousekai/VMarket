package com.vmarket.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vmarket.user.entity.UserProfile;

public interface UserProfileRepository extends JpaRepository<UserProfile, String> {

	Optional<UserProfile> findByUserId(String userId);

	boolean existsByUserId(String userId);

	/** Ghép hồ sơ vào một trang tài khoản trong một truy vấn (tránh N+1). */
	List<UserProfile> findByUserIdIn(Collection<String> userIds);

	/**
	 * userId của các hồ sơ khớp họ tên hoặc số điện thoại — phần tìm kiếm Admin mà chỉ
	 * user-service có dữ liệu (FR-USER-04). Kết quả được gửi sang auth-service để hợp
	 * với phần khớp email/username, xem {@code AdminUserService#search}.
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
			select p.userId from UserProfile p
			where lower(p.fullName) like lower(:q)
			   or lower(p.phone) like lower(:q)
			order by p.createdAt desc
			""")
	List<String> findUserIdsByKeyword(@Param("q") String q, Pageable pageable);
}
