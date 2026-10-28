package com.vmarket.product.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.vmarket.product.exception.ApiException;

/**
 * Kiểm tra quyền cho thao tác seller/admin dựa trên danh tính đã verify trong
 * {@code SecurityContext} (đặt bởi {@link JwtAuthenticationFilter}).
 *
 * <p>PBL6-49: không còn đọc header {@code X-User-Id} / {@code X-User-Roles} —
 * header do client gửi thẳng vào service thì giả mạo được.
 */
@Component
public class RequestIdentity {

	/** @return userId (claim {@code sub}) của seller đang đăng nhập */
	public String requireSeller() {
		Authentication authentication = requireAuthenticated();
		requireRole(authentication, "SELLER");
		return authentication.getName();
	}

	public void requireAdmin() {
		requireRole(requireAuthenticated(), "ADMIN");
	}

	private Authentication requireAuthenticated() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bạn cần đăng nhập để thực hiện thao tác này");
		}
		return authentication;
	}

	private void requireRole(Authentication authentication, String requiredRole) {
		String authority = "ROLE_" + requiredRole;
		boolean allowed = authentication.getAuthorities().stream()
				.anyMatch(granted -> authority.equals(granted.getAuthority()));
		if (!allowed) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Bạn không có quyền thực hiện thao tác này");
		}
	}
}
