package com.vmarket.auth.security;

import java.time.Duration;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.vmarket.auth.config.AuthRefreshCookieProperties;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Đọc/ghi refresh token qua cookie HttpOnly thay vì trả trong body JSON (PBL6-46)
 * — JS phía client không đọc được, giảm rủi ro bị đánh cắp qua XSS so với lưu ở
 * localStorage/biến JS. Cookie scope theo path {@code /api/auth} vì chỉ các
 * endpoint auth cần đến nó.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookieService {

	public static final String COOKIE_NAME = "refresh_token";
	private static final String PATH = "/api/auth";

	private final AuthRefreshCookieProperties props;

	/** Gắn cookie chứa refresh token gốc, sống bằng đúng TTL của token trong DB. */
	public void attach(HttpServletResponse response, String rawToken, Duration ttl) {
		response.addHeader(HttpHeaders.SET_COOKIE, build(rawToken, ttl).toString());
	}

	/** Xoá cookie (logout / revoke phiên hiện tại) — Max-Age=0. */
	public void clear(HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
	}

	/** Đọc raw refresh token từ cookie của request, nếu có. */
	public Optional<String> read(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return Optional.empty();
		}
		for (Cookie cookie : cookies) {
			if (COOKIE_NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
				return Optional.of(cookie.getValue());
			}
		}
		return Optional.empty();
	}

	private ResponseCookie build(String value, Duration maxAge) {
		return ResponseCookie.from(COOKIE_NAME, value)
				.httpOnly(true)
				.secure(props.isSecure())
				.sameSite(props.getSameSite())
				.path(PATH)
				.maxAge(maxAge)
				.build();
	}
}
