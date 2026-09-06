package com.vmarket.auth.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Vai trò RBAC. Bảng {@code roles} được seed đủ 5 dòng trong V1 — subtask sau chỉ
 * đọc, không tạo thêm role động.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
public class Role extends BaseEntity {

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, unique = true, length = 20)
	private RoleName name;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
