package com.vmarket.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.auth.config.AuthJwtProperties;
import com.vmarket.auth.dto.AccountStatus;
import com.vmarket.auth.dto.LoginRequest;
import com.vmarket.auth.dto.RefreshRequest;
import com.vmarket.auth.dto.TokenResponse;
import com.vmarket.auth.entity.RefreshToken;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.security.JwtService;
import com.vmarket.auth.security.OpaqueTokenCodec;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-AUTH-02 — Đăng nhập (JWT) và làm mới access token.
 *
 * <ul>
 *   <li>Đăng nhập bằng email. Sai 5 lần liên tiếp → khoá tài khoản 15 phút và thu
 *       hồi toàn bộ refresh token của user (SRS FR-AUTH-06).</li>
 *   <li>User chưa xác thực email vẫn đăng nhập được — response {@code status = PENDING}.</li>
 *   <li>{@code /refresh} xoay vòng. Dùng lại token đã thu hồi <b>quá</b> khoảng ân
 *       hạn → coi là bị đánh cắp → thu hồi toàn bộ phiên. Dùng lại trong khoảng ân
 *       hạn (retry mạng / race) → chỉ từ chối, không thu hồi.</li>
 * </ul>
 *
 * <p>{@code noRollbackFor = ApiException}: các nhánh "ghi rồi ném" (tăng bộ đếm
 * sai, thu hồi phiên khi phát hiện reuse) phải được commit dù request kết thúc
 * bằng lỗi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

	static final int MAX_FAILED_ATTEMPTS = 5;
	static final Duration LOCK_DURATION = Duration.ofMinutes(15);
	/** Trong khoảng này sau khi xoay vòng, dùng lại token cũ = retry vô hại, không thu hồi phiên. */
	static final Duration ROTATION_GRACE = Duration.ofSeconds(30);

	/** BCrypt hash hợp lệ (không ứng với mật khẩu nào dùng được) — so sánh giả để cân bằng thời gian phản hồi khi email không tồn tại. */
	private static final String DUMMY_HASH =
			"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final UserRoleRepository userRoleRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final OpaqueTokenCodec tokenCodec;
	private final AuthJwtProperties jwtProps;

	@Transactional(noRollbackFor = ApiException.class)
	public TokenResponse login(LoginRequest request) {
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		User user = userRepository.findByEmail(email).orElse(null);

		if (user == null) {
			// So sánh giả để thời gian phản hồi giống nhánh có user (chống timing oracle).
			passwordEncoder.matches(request.password(), DUMMY_HASH);
			throw invalidCredentials();
		}

		Instant now = Instant.now();
		if (isLocked(user, now)) {
			throw accountLocked(user.getLockedUntil(), now);
		}

		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			Instant lockedUntil = registerFailedAttempt(user, now);
			if (lockedUntil != null) {
				throw accountLocked(lockedUntil, now);
			}
			throw invalidCredentials();
		}

		if (user.getFailedLoginAttempts() != 0 || user.getLockedUntil() != null) {
			userRepository.clearLock(user.getId());
		}
		return issueTokens(user, now);
	}

	@Transactional(noRollbackFor = ApiException.class)
	public TokenResponse refresh(RefreshRequest request) {
		String hash = tokenCodec.hash(request.refreshToken());
		RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
				.orElseThrow(() -> new ApiException("INVALID_REFRESH_TOKEN", HttpStatus.UNAUTHORIZED,
						"Refresh token không hợp lệ"));
		Instant now = Instant.now();

		if (token.getRevokedAt() != null) {
			boolean withinGrace = token.getReplacedBy() != null
					&& token.getRevokedAt().isAfter(now.minus(ROTATION_GRACE));
			if (withinGrace) {
				throw new ApiException("INVALID_REFRESH_TOKEN", HttpStatus.UNAUTHORIZED,
						"Refresh token đã được dùng, hãy dùng token mới nhất");
			}
			int revoked = refreshTokenRepository.revokeAllActiveByUserId(token.getUserId(), now);
			log.warn("Dùng lại refresh token đã thu hồi (userId={}), thu hồi {} phiên còn lại",
					token.getUserId(), revoked);
			throw new ApiException("REFRESH_TOKEN_REUSED", HttpStatus.UNAUTHORIZED,
					"Phiên đã bị thu hồi, vui lòng đăng nhập lại");
		}
		if (!token.getExpiresAt().isAfter(now)) {
			throw new ApiException("REFRESH_TOKEN_EXPIRED", HttpStatus.UNAUTHORIZED,
					"Refresh token đã hết hạn");
		}

		User user = userRepository.findById(token.getUserId())
				.orElseThrow(() -> new ApiException("INVALID_REFRESH_TOKEN", HttpStatus.UNAUTHORIZED,
						"Tài khoản không tồn tại"));
		if (isLocked(user, now)) {
			// Thu hồi toàn bộ phiên (SRS FR-AUTH-06) — kể cả khi tài khoản bị Admin
			// khoá thủ công, không đi qua luồng login sai mật khẩu.
			int revoked = refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
			log.warn("Refresh bị từ chối do tài khoản bị khoá (userId={}), thu hồi {} phiên", user.getId(), revoked);
			throw accountLocked(user.getLockedUntil(), now);
		}

		String rawNew = tokenCodec.generate();
		RefreshToken replacement = new RefreshToken();
		replacement.setUserId(user.getId());
		replacement.setTokenHash(tokenCodec.hash(rawNew));
		replacement.setExpiresAt(now.plus(jwtProps.getRefreshTtl()));
		refreshTokenRepository.save(replacement);

		if (refreshTokenRepository.revokeIfActive(token.getId(), now, replacement.getId()) == 0) {
			refreshTokenRepository.delete(replacement);
			throw new ApiException("INVALID_REFRESH_TOKEN", HttpStatus.UNAUTHORIZED,
					"Refresh token không hợp lệ");
		}

		List<String> roles = roleNamesOf(user.getId());
		return TokenResponse.bearer(jwtService.createAccessToken(user, roles), rawNew,
				jwtService.getAccessTtlSeconds(), AccountStatus.of(user.isEmailVerified()), roles);
	}

	// --- helpers -------------------------------------------------------------

	private TokenResponse issueTokens(User user, Instant now) {
		List<String> roles = roleNamesOf(user.getId());
		String rawRefresh = tokenCodec.generate();
		RefreshToken token = new RefreshToken();
		token.setUserId(user.getId());
		token.setTokenHash(tokenCodec.hash(rawRefresh));
		token.setExpiresAt(now.plus(jwtProps.getRefreshTtl()));
		refreshTokenRepository.save(token);

		log.info("Đăng nhập thành công userId={}", user.getId());
		return TokenResponse.bearer(jwtService.createAccessToken(user, roles), rawRefresh,
				jwtService.getAccessTtlSeconds(), AccountStatus.of(user.isEmailVerified()), roles);
	}

	private boolean isLocked(User user, Instant now) {
		return user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
	}

	/**
	 * Ghi nhận một lần đăng nhập sai (UPDATE nguyên tử). Nếu chạm ngưỡng thì khoá
	 * tài khoản + thu hồi toàn bộ refresh token. Trả về {@code locked_until} nếu
	 * vừa bị khoá, ngược lại {@code null}.
	 */
	private Instant registerFailedAttempt(User user, Instant now) {
		userRepository.incrementFailedLoginAttempts(user.getId());
		int attempts = userRepository.findById(user.getId())
				.map(User::getFailedLoginAttempts).orElse(0);
		if (attempts < MAX_FAILED_ATTEMPTS) {
			return null;
		}
		Instant lockedUntil = now.plus(LOCK_DURATION);
		userRepository.lockUntil(user.getId(), lockedUntil);
		int revoked = refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
		log.warn("Khoá tài khoản userId={} do {} lần đăng nhập sai; thu hồi {} phiên",
				user.getId(), MAX_FAILED_ATTEMPTS, revoked);
		return lockedUntil;
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

	private ApiException invalidCredentials() {
		return new ApiException("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED,
				"Email hoặc mật khẩu không đúng");
	}

	private ApiException accountLocked(Instant lockedUntil, Instant now) {
		long minutes = Math.max(1, Duration.between(now, lockedUntil).toMinutes() + 1);
		return new ApiException("ACCOUNT_LOCKED", HttpStatus.LOCKED,
				"Tài khoản tạm khoá do đăng nhập sai nhiều lần. Thử lại sau khoảng " + minutes + " phút");
	}
}
