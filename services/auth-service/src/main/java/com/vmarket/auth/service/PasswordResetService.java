package com.vmarket.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.auth.config.AuthPasswordResetProperties;
import com.vmarket.auth.dto.ForgotPasswordResponse;
import com.vmarket.auth.dto.MessageResponse;
import com.vmarket.auth.email.EmailSender;
import com.vmarket.auth.email.PasswordResetEmailContent;
import com.vmarket.auth.entity.PasswordResetToken;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.PasswordResetTokenRepository;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-AUTH-04 — Quên mật khẩu bằng mã OTP 6 số gửi qua email (giống pattern
 * {@link OtpService} của PBL6-44). Khác biệt chính: key theo {@code userId}
 * (mục tiêu luôn là user đã tồn tại), và {@code resetPassword} KHÔNG tự đăng
 * nhập — chỉ đổi mật khẩu, người dùng phải đăng nhập lại thủ công.
 *
 * <p>Nhân tiện đóng luôn TODO của PBL6-44: tài khoản tạo qua OTP signup có
 * {@code password_hash = NULL} — {@code resetPassword} là cách đầu tiên để đặt
 * mật khẩu cho các tài khoản đó.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

	private static final SecureRandom RANDOM = new SecureRandom();
	/** BCrypt hash hợp lệ dùng để so sánh giả — cân bằng thời gian phản hồi khi không có mục tiêu. */
	private static final String DUMMY_HASH =
			"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final PasswordResetTokenRepository tokenRepository;
	private final UserRepository userRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final EmailSender emailSender;
	private final AuthPasswordResetProperties props;

	/**
	 * KHÔNG {@code @Transactional} ở mức method — lý do giống hệt
	 * {@link OtpService#requestOtp}: dòng {@code password_reset_token} phải commit
	 * trước khi gọi HTTP gửi mail, để provider lỗi không xoá dấu vết rate-limit.
	 *
	 * <p>Email chưa đăng ký: KHÔNG tạo dòng token / KHÔNG gửi mail, nhưng vẫn trả
	 * response giống hệt nhánh tìm thấy (không tiết lộ qua nội dung response).
	 * Lưu ý: đây chỉ cân bằng chi phí CPU (so sánh BCrypt giả), không cân bằng độ
	 * trễ ghi DB + gọi HTTP của nhánh tìm thấy — chấp nhận như một đánh đổi còn
	 * sót lại (risk tương tự các đánh đổi đã ghi trong worklog PBL6-44).
	 */
	public ForgotPasswordResponse forgotPassword(String rawEmail) {
		String email = normalize(rawEmail);
		Instant now = Instant.now();

		User user = userRepository.findByEmail(email).orElse(null);
		if (user == null) {
			passwordEncoder.matches(email, DUMMY_HASH);
			return response();
		}

		tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).ifPresent(latest -> {
			if (latest.getConsumedAt() == null
					&& latest.getCreatedAt().isAfter(now.minus(props.getResendCooldown()))) {
				throw new ApiException("PASSWORD_RESET_TOO_SOON", HttpStatus.TOO_MANY_REQUESTS,
						"Vui lòng đợi " + props.getResendCooldown().toSeconds() + " giây trước khi yêu cầu mã mới");
			}
		});

		if (tokenRepository.countByUserIdAndCreatedAtAfter(user.getId(), now.minus(Duration.ofHours(1)))
				>= props.getHourlyLimit()) {
			throw new ApiException("PASSWORD_RESET_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
					"Đã yêu cầu mã quá nhiều lần, vui lòng thử lại sau");
		}

		String code = String.format("%06d", RANDOM.nextInt(1_000_000));
		PasswordResetToken token = new PasswordResetToken();
		token.setUserId(user.getId());
		token.setCodeHash(passwordEncoder.encode(code));
		token.setExpiresAt(now.plus(props.getTtl()));
		tokenRepository.save(token); // commit ngay - giữ dấu vết rate-limit dù gửi mail lỗi

		emailSender.send(PasswordResetEmailContent.build(email, code, props.getTtl()));

		log.info("Đã phát mã đặt lại mật khẩu (ttl={}s) userId={}", props.getTtl().toSeconds(), user.getId());
		return response();
	}

	// noRollbackFor: tăng bộ đếm sai / vô hiệu hoá mã (ghi rồi ném) phải được commit.
	@Transactional(noRollbackFor = ApiException.class)
	public MessageResponse resetPassword(String rawEmail, String code, String newPassword) {
		String email = normalize(rawEmail);
		Instant now = Instant.now();

		User user = userRepository.findByEmail(email).orElse(null);
		if (user == null) {
			passwordEncoder.matches(code, DUMMY_HASH);
			throw notFound();
		}

		PasswordResetToken token = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElse(null);
		if (token == null) {
			passwordEncoder.matches(code, DUMMY_HASH);
			throw notFound();
		}

		if (token.getConsumedAt() != null) {
			throw alreadyUsed();
		}
		if (!token.getExpiresAt().isAfter(now)) {
			throw new ApiException("PASSWORD_RESET_EXPIRED", HttpStatus.BAD_REQUEST,
					"Mã đã hết hạn, hãy yêu cầu mã mới");
		}
		if (token.getAttempts() >= props.getMaxAttempts()) {
			throw tooManyAttempts();
		}

		if (!passwordEncoder.matches(code, token.getCodeHash())) {
			tokenRepository.incrementAttempts(token.getId());
			int used = token.getAttempts() + 1;
			if (used >= props.getMaxAttempts()) {
				throw tooManyAttempts();
			}
			throw new ApiException("PASSWORD_RESET_INVALID", HttpStatus.BAD_REQUEST,
					"Mã không đúng (còn " + (props.getMaxAttempts() - used) + " lần thử)");
		}

		// Đánh dấu đã dùng NGUYÊN TỬ (where consumed_at is null). 0 dòng = request
		// khác đã dùng mã này → chống double-verify.
		if (tokenRepository.markConsumed(token.getId(), now) == 0) {
			throw alreadyUsed();
		}

		user.setPasswordHash(passwordEncoder.encode(newPassword));
		if (!user.isEmailVerified()) {
			user.setEmailVerified(true);
		}
		userRepository.save(user);
		userRepository.clearLock(user.getId());
		int revoked = refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);

		log.info("Đặt lại mật khẩu thành công userId={}, thu hồi {} phiên", user.getId(), revoked);
		return new MessageResponse("Đặt lại mật khẩu thành công, vui lòng đăng nhập lại");
	}

	@Transactional
	public int purgeExpired(Instant cutoff) {
		return tokenRepository.deleteByCreatedAtBefore(cutoff);
	}

	// --- helpers -------------------------------------------------------------

	private ForgotPasswordResponse response() {
		return new ForgotPasswordResponse(props.getTtl().toSeconds(),
				"Nếu email đã đăng ký, mã đặt lại mật khẩu đã được gửi tới email");
	}

	private static ApiException notFound() {
		return new ApiException("PASSWORD_RESET_NOT_FOUND", HttpStatus.BAD_REQUEST,
				"Chưa yêu cầu mã đặt lại mật khẩu hoặc mã đã bị thay thế");
	}

	private static ApiException alreadyUsed() {
		return new ApiException("PASSWORD_RESET_ALREADY_USED", HttpStatus.BAD_REQUEST, "Mã đã được sử dụng");
	}

	private static ApiException tooManyAttempts() {
		return new ApiException("PASSWORD_RESET_TOO_MANY_ATTEMPTS", HttpStatus.BAD_REQUEST,
				"Nhập sai quá nhiều lần, hãy yêu cầu mã mới");
	}

	private static String normalize(String rawEmail) {
		return rawEmail.trim().toLowerCase(Locale.ROOT);
	}
}
