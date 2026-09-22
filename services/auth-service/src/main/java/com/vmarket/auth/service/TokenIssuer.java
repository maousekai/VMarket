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
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRoleRepository;
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
	 * Sinh MỘT refresh token mới + access token, trả về response (đăng nhập / OTP).
	 *
	 * <p>Từ chối tài khoản bị Admin khoá (FR-USER-04) ngay tại đây — điểm chung của
	 * mọi luồng đăng nhập — để luồng mới thêm sau (Google...) không thể quên kiểm tra.
	 * Caller đã xác thực danh tính (mật khẩu/OTP đúng) trước khi gọi, nên người đoán
	 * sai mật khẩu không dò được tài khoản nào đang bị khoá.
	 *
	 * <p><b>Hợp đồng với caller:</b> {@code user} phải được nạp bằng
	 * {@code UserRepository.findByIdForUpdate} / {@code findByEmailForUpdate} trong CÙNG
	 * transaction. Chỉ khi dòng user bị khoá tới lúc commit thì kiểm tra dưới đây mới còn
	 * đúng lúc refresh token được lưu — nếu không, Admin có thể khoá + thu hồi phiên xen
	 * vào giữa và token này thoát lưới (review PR #22, M1).
	 */
	public TokenResponse issue(User user) {
		if (user.isSuspended()) {
			log.warn("Từ chối phát token cho tài khoản bị Admin khoá userId={}", user.getId());
			throw ApiException.accountSuspended();
		}
		String rawRefresh = tokenCodec.generate();
		RefreshToken token = new RefreshToken();
		token.setUserId(user.getId());
		token.setTokenHash(tokenCodec.hash(rawRefresh));
		token.setExpiresAt(Instant.now().plus(jwtProps.getRefreshTtl()));
		refreshTokenRepository.save(token);

		log.debug("Phát token cho userId={}", user.getId());
		return responseFor(user, rawRefresh);
	}

	/**
	 * Chỉ dựng {@link TokenResponse} từ một refresh token đã được caller persist
	 * (dùng cho luồng xoay vòng {@code /refresh}). KHÔNG tạo thêm dòng
	 * {@code refresh_tokens}.
	 */
	public TokenResponse responseFor(User user, String rawRefreshToken) {
		List<String> roles = roleNamesOf(user.getId());
		return TokenResponse.bearer(
				jwtService.createAccessToken(user, roles),
				rawRefreshToken,
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
