package com.vmarket.auth.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vmarket.auth.entity.UserRole;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRole.UserRoleId> {

	List<UserRole> findByUserId(String userId);

	/** Nạp vai trò của cả một trang tài khoản trong một truy vấn (tránh N+1). */
	List<UserRole> findByUserIdIn(Collection<String> userIds);

	boolean existsByUserIdAndRoleId(String userId, String roleId);
}
