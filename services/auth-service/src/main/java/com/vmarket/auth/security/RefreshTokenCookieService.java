package com.vmarket.auth.security;

import java.time.Duration;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.vmarket.auth.config.AuthRefreshCookieProperties;
import com.vmarket.auth.dto.TokenResponse;

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
	public static final String CLIENT_TYPE_HEADER = "X-Client-Type";
	public static final String REFRESH_HEADER = "X-Refresh-Token";
	private static final String MOBILE_CLIENT = "mobile";
	private static final String PATH = "/api/auth";

	private final AuthRefreshCookieProperties props;

	/**
	 * Gắn cookie chứa refresh token gốc, sống bằng đúng TTL của token trong DB.
	 * Client native KHÔNG nhận cookie: token của họ đi theo body/header
	 * ({@link #decorate}, {@link #REFRESH_HEADER}); cookie jar của RN vẫn lưu Set-Cookie nên
	 * nếu ghi thêm cookie thì hai nguồn token có thể lệch nhau sau mỗi lần xoay vòng.
	 */
	public void attach(HttpServletRequest request, HttpServletResponse response, String rawToken, Duration ttl) {
		if (isNativeMobileRequest(request)) {
			return;
		}
		response.addHeader(HttpHeaders.SET_COOKIE, build(rawToken, ttl).toString());
	}

	/** Xoá cookie (logout / revoke phiên hiện tại) — Max-Age=0. */
	public void clear(HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
	}

	/**
	 * Đọc raw refresh token của request. Client native (xem {@link #isNativeMobileRequest}) ưu
	 * tiên header {@code X-Refresh-Token} — nguồn duy nhất họ ghi — rồi mới tới cookie (có thể
	 * là bản cũ trong cookie jar). Web chỉ đọc cookie HttpOnly.
	 */
	public Optional<String> read(HttpServletRequest request) {
		if (isNativeMobileRequest(request)) {
			String header = request.getHeader(REFRESH_HEADER);
			if (header != null && !header.isBlank()) {
				return Optional.of(header.trim());
			}
		}
		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (Cookie cookie : cookies) {
				if (COOKIE_NAME.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
					return Optional.of(cookie.getValue());
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Client native opt-in: {@code X-Client-Type: mobile} và KHÔNG có {@code Origin} lẫn
	 * {@code Referer}. Trình duyệt luôn gửi Origin trên POST fetch, nên payload XSS không
	 * thể tự opt-in để lấy refresh token ra body (giữ nguyên bảo vệ PBL6-46 cho web).
	 */
	public boolean isNativeMobileRequest(HttpServletRequest request) {
		return MOBILE_CLIENT.equalsIgnoreCase(request.getHeader(CLIENT_TYPE_HEADER))
				&& request.getHeader("Origin") == null
				&& request.getHeader("Referer") == null;
	}

	/** Gắn refresh token vào body nếu request là client native; ngược lại giữ nguyên body. */
	public TokenResponse decorate(HttpServletRequest request, TokenResponse body, String rawToken, Duration ttl) {
		return isNativeMobileRequest(request) ? body.withRefreshToken(rawToken, ttl.toSeconds()) : body;
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
