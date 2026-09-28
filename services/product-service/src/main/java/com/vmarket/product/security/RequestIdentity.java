package com.vmarket.product.security;

import java.util.Arrays;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.vmarket.product.exception.ApiException;

@Component
public class RequestIdentity {

	public String requireSeller(String userId, String roles) {
		requireRole(roles, "SELLER");
		if (userId == null || userId.isBlank()) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Thiếu danh tính người dùng");
		}
		return userId;
	}

	public void requireAdmin(String roles) {
		requireRole(roles, "ADMIN");
	}

	private void requireRole(String roles, String requiredRole) {
		boolean allowed = roles != null && Arrays.stream(roles.split(","))
				.map(String::trim)
				.map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
				.anyMatch(requiredRole::equalsIgnoreCase);
		if (!allowed) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền thực hiện thao tác này");
		}
	}
}
