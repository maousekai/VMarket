package com.vmarket.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.auth.config.AuthOtpProperties;
import com.vmarket.auth.dto.OtpRequestResponse;
import com.vmarket.auth.dto.TokenResponse;
import com.vmarket.auth.email.EmailSender;
import com.vmarket.auth.email.OtpEmailContent;
import com.vmarket.auth.entity.EmailOtp;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.EmailOtpRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-AUTH-01 — Xác thực email bằng mã OTP 6 số.
 *
 * <ul>
 *   <li>{@code requestOtp}: sinh mã (SecureRandom), lưu <b>hash BCrypt</b> + hạn 5
 *       phút vào bảng {@code email_otp}, gửi email. Chặn resend trong 60s và trần
 *       {@code hourlyLimit} mã/giờ/email.</li>
 *   <li>{@code verifyOtp}: đối chiếu hash, kiểm hạn + số lần sai ({@code maxAttempts}),
 *       đánh dấu {@code email_verified=true} (tạo user mới không mật khẩu nếu chưa
 *       có), phát cặp token qua {@link TokenIssuer}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final RoleName DEFAULT_ROLE = RoleName.BUYER;
	/** BCrypt hash hợp lệ dùng để so sánh giả — cân bằng thời gian phản hồi khi không có OTP. */
	private static final String DUMMY_HASH =
			"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final EmailOtpRepository otpRepository;
	private final UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final UserRoleRepository userRoleRepository;
	private final PasswordEncoder passwordEncoder;
	private final EmailSender emailSender;
	private final TokenIssuer tokenIssuer;
	private final AuthOtpProperties props;

	/**
	 * KHÔNG {@code @Transactional} ở mức method: {@code otpRepository.save} tự commit,
	 * sau đó mới gọi HTTP gửi mail (không giữ connection DB). Nếu gửi lỗi, dòng
	 * {@code email_otp} vẫn còn → phần đếm rate-limit không bị mất (chống loop vô hạn
	 * khi provider hỏng).
	 */
	public OtpRequestResponse requestOtp(String rawEmail) {
		String email = normalize(rawEmail);
		Instant now = Instant.now();

		otpRepository.findFirstByEmailOrderByCreatedAtDesc(email).ifPresent(latest -> {
			if (latest.getConsumedAt() == null
					&& latest.getCreatedAt().isAfter(now.minus(props.getResendCooldown()))) {
				throw new ApiException("OTP_RESEND_TOO_SOON", HttpStatus.TOO_MANY_REQUESTS,
						"Vui lòng đợi " + props.getResendCooldown().toSeconds() + " giây trước khi yêu cầu mã mới");
			}
		});

		if (otpRepository.countByEmailAndCreatedAtAfter(email, now.minus(Duration.ofHours(1))) >= props.getHourlyLimit()) {
			throw new ApiException("OTP_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
					"Đã yêu cầu mã quá nhiều lần, vui lòng thử lại sau");
		}

		String code = String.format("%06d", RANDOM.nextInt(1_000_000));
		EmailOtp otp = new EmailOtp();
		otp.setEmail(email);
		otp.setCodeHash(passwordEncoder.encode(code));
		otp.setExpiresAt(now.plus(props.getTtl()));
		otpRepository.save(otp); // commit ngay - giữ dấu vết rate-limit dù gửi mail lỗi

		emailSender.send(OtpEmailContent.build(email, code, props.getTtl()));

		log.info("Đã phát OTP (ttl={}s)", props.getTtl().toSeconds());
		return new OtpRequestResponse(props.getTtl().toSeconds(), "Mã OTP đã được gửi tới email");
	}

	// noRollbackFor: tăng bộ đếm sai / vô hiệu hoá mã (ghi rồi ném) phải được commit.
	@Transactional(noRollbackFor = ApiException.class)
	public TokenResponse verifyOtp(String rawEmail, String code) {
		String email = normalize(rawEmail);
		Instant now = Instant.now();

		EmailOtp otp = otpRepository.findFirstByEmailOrderByCreatedAtDesc(email).orElse(null);
		if (otp == null) {
			// So sánh giả để thời gian phản hồi giống nhánh có OTP (chống timing oracle).
			passwordEncoder.matches(code, DUMMY_HASH);
			throw new ApiException("OTP_NOT_FOUND", HttpStatus.BAD_REQUEST,
					"Chưa yêu cầu mã OTP hoặc mã đã bị thay thế");
		}

		if (otp.getConsumedAt() != null) {
			throw new ApiException("OTP_ALREADY_USED", HttpStatus.BAD_REQUEST, "Mã OTP đã được sử dụng");
		}
		if (!otp.getExpiresAt().isAfter(now)) {
			throw new ApiException("OTP_EXPIRED", HttpStatus.BAD_REQUEST, "Mã OTP đã hết hạn, hãy yêu cầu mã mới");
		}
		if (otp.getAttempts() >= props.getMaxAttempts()) {
			throw new ApiException("OTP_TOO_MANY_ATTEMPTS", HttpStatus.BAD_REQUEST,
					"Nhập sai quá nhiều lần, hãy yêu cầu mã mới");
		}

		if (!passwordEncoder.matches(code, otp.getCodeHash())) {
			otpRepository.incrementAttempts(otp.getId());
			int used = otp.getAttempts() + 1;
			// Chạm ngưỡng: guard `attempts >= maxAttempts` ở trên đã khiến mã không
			// còn verify được lần sau -> không cần markConsumed riêng.
			if (used >= props.getMaxAttempts()) {
				throw new ApiException("OTP_TOO_MANY_ATTEMPTS", HttpStatus.BAD_REQUEST,
						"Nhập sai quá nhiều lần, hãy yêu cầu mã mới");
			}
			throw new ApiException("OTP_INVALID", HttpStatus.BAD_REQUEST,
					"Mã OTP không đúng (còn " + (props.getMaxAttempts() - used) + " lần thử)");
		}

		// Đánh dấu đã dùng NGUYÊN TỬ (where consumed_at is null). 0 dòng = request
		// khác đã dùng mã này → chống double-verify.
		if (otpRepository.markConsumed(otp.getId(), now) == 0) {
			throw new ApiException("OTP_ALREADY_USED", HttpStatus.BAD_REQUEST, "Mã OTP đã được sử dụng");
		}

		User user = userRepository.findByEmail(email)
				.map(existing -> {
					if (!existing.isEmailVerified()) {
						existing.setEmailVerified(true);
						userRepository.save(existing);
					}
					return existing;
				})
				.orElseGet(() -> createOrGetVerifiedUser(email));

		log.info("Xác thực email thành công userId={}", user.getId());
		return tokenIssuer.issue(user);
	}

	@Transactional
	public int purgeExpired(Instant cutoff) {
		return otpRepository.deleteByCreatedAtBefore(cutoff);
	}

	// --- helpers -----------------------------------------------------------

	/**
	 * Tạo tài khoản đã xác thực (không mật khẩu) cho email chưa có user. Nếu một
	 * request khác vừa tạo trước (race trên {@code users.email} unique) → đọc lại
	 * và dùng user đó.
	 */
	private User createOrGetVerifiedUser(String email) {
		Role role = roleRepository.findByName(DEFAULT_ROLE)
				.orElseThrow(() -> new IllegalStateException(
						"Vai trò mặc định " + DEFAULT_ROLE + " chưa được seed (migration V1)"));

		User user = new User();
		user.setEmail(email);
		user.setUsername(UsernameGenerator.generate(email, userRepository::existsByUsername));
		user.setPasswordHash(null); // tài khoản tạo qua OTP - chưa có mật khẩu
		user.setEmailVerified(true);
		try {
			userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException ex) {
			return userRepository.findByEmail(email).orElseThrow(() -> new ApiException(
					"REGISTRATION_CONFLICT", HttpStatus.CONFLICT, "Không tạo được tài khoản, vui lòng thử lại"));
		}
		userRoleRepository.save(new UserRole(user.getId(), role.getId()));
		return user;
	}

	private static String normalize(String rawEmail) {
		return rawEmail.trim().toLowerCase(Locale.ROOT);
	}
}
