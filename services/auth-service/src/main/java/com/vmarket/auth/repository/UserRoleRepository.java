package com.vmarket.auth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vmarket.auth.entity.UserRole;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRole.UserRoleId> {

	List<UserRole> findByUserId(String userId);

	boolean existsByUserIdAndRoleId(String userId, String roleId);
}
