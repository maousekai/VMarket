package com.vmarket.auth.entity;

import java.io.Serializable;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bảng nối many-to-many giữa {@link User} và {@link Role}. Mô hình hoá tường minh
 * (thay vì {@code @ManyToMany} ẩn) vì SRS liệt kê UserRole là thực thể riêng và
 * các subtask RBAC sau sẽ thao tác trực tiếp trên bảng này.
 */
@Entity
@Table(name = "user_roles")
@IdClass(UserRole.UserRoleId.class)
@Getter
@Setter
@NoArgsConstructor
public class UserRole {

	@Id
	@Column(name = "user_id", length = 26)
	private String userId;

	@Id
	@Column(name = "role_id", length = 26)
	private String roleId;

	@CreationTimestamp
	@Column(name = "assigned_at", nullable = false, updatable = false)
	private Instant assignedAt;

	public UserRole(String userId, String roleId) {
		this.userId = userId;
		this.roleId = roleId;
	}

	@Getter
	@Setter
	@NoArgsConstructor
	@AllArgsConstructor
	@EqualsAndHashCode
	public static class UserRoleId implements Serializable {
		private String userId;
		private String roleId;
	}
}
