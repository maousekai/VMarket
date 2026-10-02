package com.vmarket.auth.service;

import java.time.Instant;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.vmarket.auth.config.AuthOtpProperties;
import com.vmarket.auth.dto.OtpRequestResponse;
import com.vmarket.auth.email.EmailSender;
import com.vmarket.auth.email.OtpEmailContent;
import com.vmarket.auth.entity.EmailOtp;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.EmailOtpLockRepository;
import com.vmarket.auth.repository.EmailOtpRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.security.DeviceMeta;

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

	private static final RoleName DEFAULT_ROLE = RoleName.BUYER;
	/** BCrypt hash hợp lệ dùng để so sánh giả — cân bằng thời gian phản hồi khi không có OTP. */
	private static final String DUMMY_HASH =
			"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final EmailOtpRepository otpRepository;
	private final EmailOtpLockRepository lockRepository;
	private final OtpIssuer otpIssuer;
	private final UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final UserRoleRepository userRoleRepository;
	private final PasswordEncoder passwordEncoder;
	private final EmailSender emailSender;
	private final TokenIssuer tokenIssuer;
	private final AuthOtpProperties props;

	/**
	 * KHÔNG {@code @Transactional} ở mức method: {@link OtpIssuer#issue} khoá theo
	 * email, kiểm giới hạn và lưu mã trong transaction ngắn riêng của nó, commit
	 * xong mới gọi HTTP gửi mail (không giữ connection/khoá DB). Nếu gửi lỗi, dòng
	 * {@code email_otp} vẫn còn → phần đếm rate-limit không bị mất (chống loop vô hạn
	 * khi provider hỏng).
	 */
	public OtpRequestResponse requestOtp(String rawEmail) {
		String email = normalize(rawEmail);
		String code = otpIssuer.issue(email, Instant.now());

		emailSender.send(OtpEmailContent.build(email, code, props.getTtl()));

		log.info("Đã phát OTP (ttl={}s)", props.getTtl().toSeconds());
		return new OtpRequestResponse(props.getTtl().toSeconds(), "Mã OTP đã được gửi tới email");
	}

	// noRollbackFor: tăng bộ đếm sai / vô hiệu hoá mã (ghi rồi ném) phải được commit.
	@Transactional(noRollbackFor = ApiException.class)
	public TokenIssuer.IssuedTokens verifyOtp(String rawEmail, String code, DeviceMeta device) {
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
		// Fast-path: chỉ để tránh so sánh BCrypt thừa. KHÔNG phải biên an toàn thật —
		// biên an toàn thật nằm ở điều kiện "attempts < maxAttempts" ngay trong các
		// UPDATE nguyên tử dưới đây (xem EmailOtpRepository).
		if (otp.getAttempts() >= props.getMaxAttempts()) {
			throw tooManyAttempts();
		}

		if (!passwordEncoder.matches(code, otp.getCodeHash())) {
			// 0 dòng = giới hạn đã đạt ở DB TRƯỚC lần tăng này (request song song
			// khác vừa tăng) → hết lượt, bất kể giá trị attempts đọc được ở trên.
			if (otpRepository.incrementAttempts(otp.getId(), props.getMaxAttempts()) == 0) {
				throw tooManyAttempts();
			}
			int used = otp.getAttempts() + 1;
			if (used >= props.getMaxAttempts()) {
				throw tooManyAttempts();
			}
			throw new ApiException("OTP_INVALID", HttpStatus.BAD_REQUEST,
					"Mã OTP không đúng (còn " + (props.getMaxAttempts() - used) + " lần thử)");
		}

		// Đánh dấu đã dùng NGUYÊN TỬ, có kiểm tra lại "attempts < maxAttempts" ngay
		// trong UPDATE: request mã đúng đọc attempts cũ ở trên, trước khi các request
		// mã sai khác commit, vẫn không thể consume nếu giới hạn đã đạt tại thời điểm
		// UPDATE thực thi. 0 dòng = đã dùng HOẶC đã hết lượt.
		if (otpRepository.markConsumed(otp.getId(), now, props.getMaxAttempts()) == 0) {
			EmailOtp current = otpRepository.findById(otp.getId()).orElseThrow();
			if (current.getConsumedAt() != null) {
				throw new ApiException("OTP_ALREADY_USED", HttpStatus.BAD_REQUEST, "Mã OTP đã được sử dụng");
			}
			throw tooManyAttempts();
		}

		// ForUpdate: khoá dòng user tới khi commit để Admin khoá tài khoản (FR-USER-04)
		// không xen được vào giữa kiểm tra "chưa bị khoá" trong TokenIssuer và lúc lưu
		// refresh token (review PR #22, M1).
		User user = userRepository.findByEmailForUpdate(email)
				.map(existing -> {
					if (!existing.isEmailVerified()) {
						existing.setEmailVerified(true);
						userRepository.save(existing);
					}
					return existing;
				})
				.orElseGet(() -> createOrGetVerifiedUser(email));

		log.info("Xác thực email thành công userId={}", user.getId());
		return tokenIssuer.issue(user, device);
	}

	@Transactional
	public int purgeExpired(Instant cutoff) {
		int removed = otpRepository.deleteByCreatedAtBefore(cutoff);
		lockRepository.deleteUnusedBefore(cutoff);
		return removed;
	}

	// --- helpers -----------------------------------------------------------

	/**
	 * Tạo tài khoản đã xác thực (không mật khẩu) cho email chưa có user.
	 *
	 * <p>Race trên {@code users.email} unique (request khác — đăng ký mật khẩu hoặc
	 * verify OTP song song — vừa tạo user trước): KHÔNG truy vấn lại DB ở đây. Entity
	 * hỏng vẫn nằm trong persistence context nên auto-flush của query sẽ INSERT lại
	 * và ném tiếp, còn trên PostgreSQL transaction đã abort. Thay vào đó đánh dấu
	 * rollback (bắt buộc, vì {@code noRollbackFor = ApiException.class} ở
	 * {@link #verifyOtp}) rồi trả 409: rollback khôi phục cả {@code consumed_at}
	 * của OTP, nên client gửi lại đúng mã sẽ đi nhánh "user đã tồn tại" và thành công.
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
			TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			throw new ApiException("REGISTRATION_CONFLICT", HttpStatus.CONFLICT,
					"Tài khoản vừa được tạo bởi một yêu cầu khác, vui lòng gửi lại mã OTP");
		}
		userRoleRepository.save(new UserRole(user.getId(), role.getId()));
		return user;
	}

	private static ApiException tooManyAttempts() {
		return new ApiException("OTP_TOO_MANY_ATTEMPTS", HttpStatus.BAD_REQUEST,
				"Nhập sai quá nhiều lần, hãy yêu cầu mã mới");
	}

	private static String normalize(String rawEmail) {
		return rawEmail.trim().toLowerCase(Locale.ROOT);
	}
}
