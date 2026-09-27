package com.vmarket.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.auth.config.AuthPasswordResetProperties;
import com.vmarket.auth.entity.PasswordResetToken;
import com.vmarket.auth.repository.PasswordResetTokenRepository;
import com.vmarket.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phần "khoá dòng user + kiểm tra giới hạn + tạo mã" của {@code forgotPassword},
 * tách khỏi {@link PasswordResetService} vì cần chạy trong MỘT transaction ngắn
 * (khoá dòng user bằng {@code SELECT ... FOR UPDATE}) rồi commit trước khi
 * {@code PasswordResetService} gọi HTTP gửi mail — gọi @Transactional từ một
 * method khác trong CÙNG class sẽ bỏ qua proxy AOP của Spring, nên phải là bean
 * riêng để lời gọi đi qua proxy.
 *
 * <p>Khoá theo user tuần tự hoá cooldown-check + hourly-limit-check + insert cho
 * cùng một user giữa các request song song (chống vượt giới hạn phát mã).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PasswordResetTokenIssuer {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRepository userRepository;
	private final PasswordResetTokenRepository tokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthPasswordResetProperties props;

	/**
	 * Vượt cooldown/hourly-limit → trả rỗng (KHÔNG ném lỗi): {@code forgotPassword}
	 * luôn trả response công khai giống nhau bất kể có phát mã hay không, để không
	 * lộ qua status code việc email đã đăng ký hay chưa.
	 */
	@Transactional
	Optional<String> issue(String userId, Instant now) {
		userRepository.findByIdForUpdate(userId).orElseThrow();

		boolean tooSoon = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
				.filter(latest -> latest.getConsumedAt() == null
						&& latest.getCreatedAt().isAfter(now.minus(props.getResendCooldown())))
				.isPresent();
		if (tooSoon) {
			log.info("Bỏ qua phát mã đặt lại mật khẩu (cooldown chưa hết) userId={}", userId);
			return Optional.empty();
		}

		if (tokenRepository.countByUserIdAndCreatedAtAfter(userId, now.minus(Duration.ofHours(1)))
				>= props.getHourlyLimit()) {
			log.info("Bỏ qua phát mã đặt lại mật khẩu (vượt trần theo giờ) userId={}", userId);
			return Optional.empty();
		}

		String code = String.format("%06d", RANDOM.nextInt(1_000_000));
		PasswordResetToken token = new PasswordResetToken();
		token.setUserId(userId);
		token.setCodeHash(passwordEncoder.encode(code));
		token.setExpiresAt(now.plus(props.getTtl()));
		tokenRepository.save(token);

		return Optional.of(code);
	}
}
