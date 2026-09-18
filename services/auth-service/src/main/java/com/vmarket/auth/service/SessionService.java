package com.vmarket.auth.service;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.auth.dto.SessionSummary;
import com.vmarket.auth.entity.RefreshToken;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.security.OpaqueTokenCodec;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-AUTH-06 — Quản lý phiên: liệt kê / thu hồi từng thiết bị.
 *
 * <p>Không có JWT verification riêng ở auth-service (xem {@code SecurityConfig}) —
 * các endpoint ở đây dùng chính refresh token trong cookie {@code refresh_token}
 * làm định danh phiên gọi ("phiên hiện tại"), nhất quán với cách {@code /refresh}
 * đã coi refresh token là credential bền của phiên (access token là stateless,
 * chưa từng được đối chiếu lại với DB ở nơi nào trong service này).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

	private final RefreshTokenRepository refreshTokenRepository;
	private final OpaqueTokenCodec tokenCodec;

	/** Tra cứu phiên gọi hiện tại từ raw refresh token trong cookie. */
	public RefreshToken resolveCurrent(String rawRefreshToken) {
		RefreshToken token = refreshTokenRepository.findByTokenHash(tokenCodec.hash(rawRefreshToken))
				.orElseThrow(SessionService::sessionNotFound);
		Instant now = Instant.now();
		if (token.getRevokedAt() != null || !token.getExpiresAt().isAfter(now)) {
			throw sessionNotFound();
		}
		return token;
	}

	public List<SessionSummary> list(RefreshToken current) {
		return refreshTokenRepository.findActiveByUserId(current.getUserId(), Instant.now())
				.stream()
				.map(rt -> new SessionSummary(
						rt.getId(), rt.getUserAgent(), rt.getIpAddress(), rt.getCreatedAt(), rt.getLastUsedAt(),
						rt.getId().equals(current.getId())))
				.toList();
	}

	/**
	 * Thu hồi một phiên cụ thể của user gọi. Id không tồn tại / không thuộc user
	 * này (đoán id của người khác) / đã bị thu hồi từ trước → cùng 404, không phân
	 * biệt để không lộ id phiên nào tồn tại.
	 */
	@Transactional
	public void revokeOne(RefreshToken current, String sessionId) {
		int revoked = refreshTokenRepository.revokeIfActiveAndOwnedBy(sessionId, current.getUserId(), Instant.now());
		if (revoked == 0) {
			throw new ApiException("TARGET_SESSION_NOT_FOUND", HttpStatus.NOT_FOUND,
					"Phiên không tồn tại hoặc đã bị thu hồi");
		}
		log.info("Thu hồi phiên id={} userId={}", sessionId, current.getUserId());
	}

	/** Thu hồi mọi phiên khác, giữ lại phiên gọi hiện tại. Trả về số phiên đã thu hồi. */
	@Transactional
	public int revokeOthers(RefreshToken current) {
		int revoked = refreshTokenRepository.revokeAllActiveByUserIdExcept(
				current.getUserId(), current.getId(), Instant.now());
		log.info("Thu hồi {} phiên khác của userId={}", revoked, current.getUserId());
		return revoked;
	}

	/** Đăng xuất — thu hồi phiên hiện tại. Idempotent: token lạ/đã thu hồi vẫn coi như thành công. */
	@Transactional
	public void logout(String rawRefreshToken) {
		RefreshToken token = refreshTokenRepository.findByTokenHash(tokenCodec.hash(rawRefreshToken)).orElse(null);
		if (token == null) {
			return;
		}
		refreshTokenRepository.revokeIfActive(token.getId(), Instant.now(), null);
	}

	private static ApiException sessionNotFound() {
		return new ApiException("SESSION_NOT_FOUND", HttpStatus.UNAUTHORIZED,
				"Phiên không tồn tại, đã hết hạn hoặc đã bị thu hồi");
	}
}
