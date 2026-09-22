package com.vmarket.auth.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.vmarket.auth.config.AuthJwtProperties;
import com.vmarket.auth.dto.AccountStatus;
import com.vmarket.auth.dto.TokenResponse;
import com.vmarket.auth.entity.RefreshToken;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.security.DeviceMeta;
import com.vmarket.auth.security.JwtService;
import com.vmarket.auth.security.OpaqueTokenCodec;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phát cặp token cho một user đã xác thực — dùng chung cho mọi luồng đăng nhập
 * (mật khẩu, OTP email, ... sau này). Sinh access token JWT + một refresh token
 * mới (lưu hash), trả về theo cùng format {@link TokenResponse}.
 *
 * <p>Gọi trong transaction của caller (persist {@code refresh_tokens}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenIssuer {

	private final RoleRepository roleRepository;
	private final UserRoleRepository userRoleRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final JwtService jwtService;
	private final OpaqueTokenCodec tokenCodec;
	private final AuthJwtProperties jwtProps;

	/**
	 * Kết quả phát token: {@link TokenResponse} trả về client (không chứa refresh
	 * token) + raw refresh token để caller gắn vào cookie HttpOnly (PBL6-46).
	 */
	public record IssuedTokens(TokenResponse body, String rawRefreshToken) {
	}

	/** Sinh MỘT refresh token mới + access token, trả về response (đăng nhập / OTP). */
	public IssuedTokens issue(User user, DeviceMeta device) {
		String rawRefresh = tokenCodec.generate();
		Instant now = Instant.now();
		RefreshToken token = new RefreshToken();
		token.setUserId(user.getId());
		token.setTokenHash(tokenCodec.hash(rawRefresh));
		token.setExpiresAt(now.plus(jwtProps.getRefreshTtl()));
		token.setUserAgent(device.userAgent());
		token.setIpAddress(device.ipAddress());
		token.setLastUsedAt(now);
		refreshTokenRepository.save(token);

		log.debug("Phát token cho userId={}", user.getId());
		return new IssuedTokens(responseFor(user), rawRefresh);
	}

	/**
	 * Chỉ dựng {@link TokenResponse} cho một user đã có refresh token hợp lệ (dùng
	 * cho luồng xoay vòng {@code /refresh} — caller tự quản lý dòng {@code refresh_tokens}).
	 */
	public TokenResponse responseFor(User user) {
		List<String> roles = roleNamesOf(user.getId());
		return TokenResponse.bearer(
				jwtService.createAccessToken(user, roles),
				jwtService.getAccessTtlSeconds(),
				AccountStatus.of(user.isEmailVerified()),
				roles);
	}

	private List<String> roleNamesOf(String userId) {
		List<String> roleIds = userRoleRepository.findByUserId(userId).stream()
				.map(UserRole::getRoleId)
				.toList();
		if (roleIds.isEmpty()) {
			return List.of();
		}
		return roleRepository.findAllById(roleIds).stream()
				.map(role -> role.getName().name())
				.sorted()
				.toList();
	}
}
