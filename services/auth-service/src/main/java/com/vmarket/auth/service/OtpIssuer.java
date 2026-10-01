package com.vmarket.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.vmarket.auth.config.AuthOtpProperties;
import com.vmarket.auth.entity.EmailOtp;
import com.vmarket.auth.entity.EmailOtpLock;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.EmailOtpLockRepository;
import com.vmarket.auth.repository.EmailOtpRepository;

import lombok.RequiredArgsConstructor;

/**
 * Phần "khoá theo email + kiểm tra giới hạn + tạo mã" của {@code requestOtp}: chạy
 * trong MỘT transaction ngắn và commit trước khi {@link OtpService} gọi HTTP gửi
 * mail (giống {@link PasswordResetTokenIssuer}).
 *
 * <p>Khoá dòng {@code email_otp_lock} của email ({@code SELECT ... FOR UPDATE})
 * tuần tự hoá cooldown-check + hourly-limit-check + insert giữa các request song
 * song. Không khoá được dòng {@code users} như luồng đặt lại mật khẩu vì email xin
 * OTP có thể chưa có tài khoản.
 */
@Component
@RequiredArgsConstructor
class OtpIssuer {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final EmailOtpRepository otpRepository;
	private final EmailOtpLockRepository lockRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthOtpProperties props;
	private final TransactionTemplate transactionTemplate;

	/**
	 * Trả mã 6 số vừa phát (đã lưu hash); vượt cooldown/trần theo giờ → 429.
	 *
	 * <p>Lặp tối đa 2 lần: {@code OtpCleanupJob} có thể xoá dòng khoá (email không
	 * còn OTP) đúng giữa lúc tạo dòng và lúc khoá dòng.
	 */
	String issue(String email, Instant now) {
		for (int i = 0; i < 2; i++) {
			ensureLockRow(email);
			String code = transactionTemplate.execute(status -> issueLocked(email, now));
			if (code != null) {
				return code;
			}
		}
		throw new IllegalStateException("Không khoá được email_otp_lock");
	}

	/**
	 * Tạo dòng khoá trong transaction RIÊNG (tự commit qua repository). Trùng khoá
	 * chính = request khác vừa tạo → bỏ qua. Tách khỏi transaction chính vì trên
	 * PostgreSQL vi phạm UNIQUE làm abort cả transaction đang chạy.
	 */
	private void ensureLockRow(String email) {
		if (lockRepository.existsById(email)) {
			return;
		}
		try {
			lockRepository.saveAndFlush(new EmailOtpLock(email));
		} catch (DataIntegrityViolationException ex) {
			// request song song cùng email đã tạo trước - dòng khoá đã có
		}
	}

	/** {@code null} = dòng khoá vừa bị dọn mất, gọi phải thử lại. */
	private String issueLocked(String email, Instant now) {
		if (lockRepository.findByEmailForUpdate(email).isEmpty()) {
			return null;
		}

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
		otpRepository.save(otp);
		return code;
	}
}
