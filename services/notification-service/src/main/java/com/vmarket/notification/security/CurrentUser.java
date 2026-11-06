package com.vmarket.notification.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.vmarket.notification.exception.ApiException;

/** Lấy userId (claim {@code sub}) của người đang đăng nhập. */
@Component
public class CurrentUser {

	public String id() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bạn cần đăng nhập để thực hiện thao tác này");
		}
		return authentication.getName();
	}
}
