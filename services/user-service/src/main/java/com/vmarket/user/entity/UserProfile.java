package com.vmarket.user.entity;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Hồ sơ cá nhân (FR-USER-01) — quan hệ 1-1 với tài khoản bên Identity Service.
 *
 * <p><b>Không</b> chứa email, username hay mật khẩu: đó là dữ liệu định danh do
 * auth-service sở hữu. Ở đây chỉ có thông tin hiển thị/liên lạc. Ai cần email thì
 * hỏi auth-service, không nhân bản sang đây để tránh hai nguồn sự thật.
 *
 * <p>{@code userId} là {@code users.id} bên auth-service, lấy từ claim {@code sub}
 * của access token đã verify. Không có khoá ngoại vì khác CSDL — xem ghi chú trong
 * {@code V1__init_user_schema.sql}.
 */
@Entity
@Table(name = "user_profiles")
@Getter
@Setter
@NoArgsConstructor
public class UserProfile extends BaseEntity {

	@Column(name = "user_id", nullable = false, unique = true, length = ID_LENGTH)
	private String userId;

	@Column(name = "full_name", length = 100)
	private String fullName;

	@Column(name = "avatar_url", length = 500)
	private String avatarUrl;

	@Column(length = 20)
	private String phone;

	@Column(name = "date_of_birth")
	private LocalDate dateOfBirth;

	/**
	 * Độ dài 20 chứ không phải 10: giá trị dài nhất của {@link Gender} là
	 * {@code UNDISCLOSED} (11 ký tự). Đặt 10 thì test trên H2 vẫn xanh cho tới khi
	 * có người thật sự chọn "không tiết lộ", rồi mới vỡ trên PostgreSQL.
	 */
	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private Gender gender;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
