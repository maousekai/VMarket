package com.vmarket.auth.service;

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
import com.vmarket.auth.entity.AccountActivity;
import com.vmarket.auth.entity.AccountActivityType;
import com.vmarket.auth.entity.PasswordResetToken;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.AccountActivityRepository;
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

	/** BCrypt hash hợp lệ dùng để so sánh giả — cân bằng thời gian phản hồi khi không có mục tiêu. */
	private static final String DUMMY_HASH =
			"$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final PasswordResetTokenRepository tokenRepository;
	private final UserRepository userRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final EmailSender emailSender;
	private final AuthPasswordResetProperties props;
	private final PasswordResetTokenIssuer tokenIssuer;
	private final AccountActivityRepository activityRepository;

	/**
	 * KHÔNG {@code @Transactional} ở mức method: {@link PasswordResetTokenIssuer#issue}
	 * chạy trong transaction ngắn riêng của nó và commit trước khi HTTP gửi mail
	 * được gọi ở đây, để provider lỗi không xoá dấu vết rate-limit.
	 *
	 * <p>Luôn trả response công khai giống nhau (200, message như nhau) trong MỌI
	 * trường hợp — email chưa đăng ký, email tồn tại nhưng bị cooldown/hourly-limit
	 * chặn, hay email tồn tại và phát mã thành công. Trước đây nhánh bị chặn trả
	 * 429 còn nhánh "chưa đăng ký" trả 200 ngay — hai lần gọi liên tiếp là đủ để dò
	 * email nào đã đăng ký mà không cần đo thời gian; sửa bằng cách không lộ kết
	 * quả rate-limit qua status code nữa (chỉ log nội bộ ở
	 * {@link PasswordResetTokenIssuer#issue}). So sánh BCrypt giả (nhánh không tìm
	 * thấy) vẫn giữ để cân bằng chi phí CPU, dù không cân bằng được độ trễ ghi DB +
	 * gọi HTTP của nhánh phát mã thành công — đánh đổi còn sót lại, chấp nhận được.
	 */
	public ForgotPasswordResponse forgotPassword(String rawEmail) {
		String email = normalize(rawEmail);
		Instant now = Instant.now();

		User user = userRepository.findByEmail(email).orElse(null);
		if (user == null) {
			passwordEncoder.matches(email, DUMMY_HASH);
			return response();
		}

		tokenIssuer.issue(user.getId(), now).ifPresent(code -> {
			emailSender.send(PasswordResetEmailContent.build(email, code, props.getTtl()));
			log.info("Đã phát mã đặt lại mật khẩu (ttl={}s) userId={}", props.getTtl().toSeconds(), user.getId());
		});

		return response();
	}

	/**
	 * noRollbackFor: tăng bộ đếm sai / vô hiệu hoá mã (ghi rồi ném) phải được commit.
	 *
	 * <p>Nạp user bằng {@code findByEmailForUpdate} và ghi mật khẩu bằng UPDATE đúng các
	 * cột cần đổi: Admin khoá (FR-USER-04) phải chờ luồng này commit, và không còn
	 * {@code save(user)} cả entity để ghi đè {@code suspended_*} bằng ảnh chụp cũ
	 * (review PR #22, M1). Đặt lại mật khẩu vẫn KHÔNG gỡ khoá của Admin — chỉ gỡ khoá tạm.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public MessageResponse resetPassword(String rawEmail, String code, String newPassword) {
		String email = normalize(rawEmail);
		Instant now = Instant.now();

		User user = userRepository.findByEmailForUpdate(email).orElse(null);
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
		// Fast-path: chỉ để tránh so sánh BCrypt thừa. KHÔNG phải biên an toàn thật —
		// biên an toàn thật nằm ở điều kiện "attempts < maxAttempts" ngay trong các
		// UPDATE nguyên tử dưới đây (xem PasswordResetTokenRepository).
		if (token.getAttempts() >= props.getMaxAttempts()) {
			throw tooManyAttempts();
		}

		if (!passwordEncoder.matches(code, token.getCodeHash())) {
			// 0 dòng = giới hạn đã đạt ở DB TRƯỚC lần tăng này (request song song
			// khác vừa tăng) → hết lượt, bất kể giá trị attempts đọc được ở trên có
			// thể đã cũ.
			if (tokenRepository.incrementAttempts(token.getId(), props.getMaxAttempts()) == 0) {
				throw tooManyAttempts();
			}
			int used = token.getAttempts() + 1;
			if (used >= props.getMaxAttempts()) {
				throw tooManyAttempts();
			}
			throw new ApiException("PASSWORD_RESET_INVALID", HttpStatus.BAD_REQUEST,
					"Mã không đúng (còn " + (props.getMaxAttempts() - used) + " lần thử)");
		}

		// Đánh dấu đã dùng NGUYÊN TỬ, có kiểm tra lại "attempts < maxAttempts" ngay
		// trong UPDATE (chặn race H-1: request mã đúng đọc attempts cũ ở trên, trước
		// khi các request mã sai khác commit, vẫn không thể consume nếu giới hạn đã
		// đạt tại thời điểm UPDATE này thực thi). 0 dòng = đã dùng HOẶC đã hết lượt.
		if (tokenRepository.markConsumed(token.getId(), now, props.getMaxAttempts()) == 0) {
			PasswordResetToken current = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
					.orElseThrow();
			throw current.getConsumedAt() != null ? alreadyUsed() : tooManyAttempts();
		}

		userRepository.resetPasswordAndClearLock(user.getId(), passwordEncoder.encode(newPassword), now);
		activityRepository.save(AccountActivity.of(user.getId(), AccountActivityType.PASSWORD_RESET, now));
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
