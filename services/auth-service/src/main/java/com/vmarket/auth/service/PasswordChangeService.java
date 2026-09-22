package com.vmarket.auth.service;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.vmarket.auth.entity.AccountActivity;
import com.vmarket.auth.entity.AccountActivityType;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.AccountActivityRepository;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-USER-03 — Đổi mật khẩu khi đã đăng nhập (yêu cầu mật khẩu hiện tại).
 *
 * <p>Endpoint công khai nằm ở user-service ({@code PUT /api/users/me/password}); mật
 * khẩu thuộc CSDL của auth-service nên việc kiểm tra + ghi diễn ra ở đây, qua API nội bộ.
 *
 * <ul>
 *   <li>Nhập sai mật khẩu hiện tại <b>tính chung bộ đếm</b> với đăng nhập sai
 *       (FR-AUTH-02): 5 lần liên tiếp → khoá 15 phút. Không có bộ đếm thì ai cầm được
 *       access token (còn sống ≤ 15 phút) có thể dò mật khẩu không giới hạn qua đây.
 *       <p><b>Mặt trái đã cân nhắc và chấp nhận:</b> chính kẻ cầm access token cũng có
 *       thể cố tình gửi 5 lần sai để khoá tài khoản 15 phút, và
 *       {@code registerFailedAttempt} thu hồi mọi refresh token nên chủ tài khoản bị
 *       đăng xuất và chưa đăng nhập lại được trong lúc đó. Đổi lại là chặn được việc dò
 *       mật khẩu — đây mới là thiệt hại không hồi phục được. Nhánh khoá xuất phát từ
 *       endpoint này được log riêng ở mức WARN để còn phát hiện khi bị lạm dụng.</p></li>
 *   <li>Đổi thành công → <b>thu hồi mọi refresh token</b>: đổi mật khẩu thường là vì
 *       nghi bị lộ, các phiên cũ (có thể của kẻ gian) phải đăng nhập lại. Cùng quyết
 *       định với đặt lại mật khẩu (PBL6-45).</li>
 *   <li>Tài khoản tạo qua OTP chưa có mật khẩu → {@code PASSWORD_NOT_SET}: không có
 *       "mật khẩu hiện tại" để xác minh, phải dùng Quên mật khẩu để đặt lần đầu.</li>
 * </ul>
 *
 * <p>{@code noRollbackFor = ApiException}: nhánh "tăng bộ đếm sai rồi ném" phải commit.
 *
 * <p><b>Chạy lần lượt với Admin khoá (FR-USER-04).</b> Nạp user bằng
 * {@code findByIdForUpdate} (khoá dòng tới khi commit) và ghi mật khẩu bằng UPDATE đúng
 * các cột cần đổi. Trước đây đọc thường rồi {@code save(user)}: Admin khoá xen vào lúc
 * BCrypt đang chạy thì entity cũ ({@code suspended_*} = null) ghi đè mất lần khoá vừa
 * commit (review PR #22, M1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordChangeService {

	private final UserRepository userRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationService authenticationService;
	private final AccountActivityRepository activityRepository;

	@Transactional(noRollbackFor = ApiException.class)
	public void changePassword(String userId, String currentPassword, String newPassword) {
		User user = userRepository.findByIdForUpdate(userId)
				.orElseThrow(AccountAdminService::userNotFound);
		Instant now = Instant.now();

		if (user.isSuspended()) {
			throw ApiException.accountSuspended();
		}
		if (authenticationService.isLocked(user, now)) {
			throw authenticationService.accountLocked(user.getLockedUntil(), now);
		}
		if (!StringUtils.hasText(user.getPasswordHash())) {
			throw new ApiException("PASSWORD_NOT_SET", HttpStatus.BAD_REQUEST,
					"Tài khoản chưa có mật khẩu. Hãy dùng chức năng Quên mật khẩu để đặt mật khẩu");
		}

		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			Instant lockedUntil = authenticationService.registerFailedAttempt(user, now);
			if (lockedUntil != null) {
				// Log riêng cho nhánh này (đăng nhập sai đã có log của nó): khoá phát sinh
				// từ ĐỔI MẬT KHẨU nghĩa là request có access token hợp lệ. Lặp lại nhiều
				// trên cùng một tài khoản là dấu hiệu ai đó đang cố khoá chủ tài khoản,
				// không phải người dùng gõ nhầm.
				log.warn("Khoá tài khoản tới {} do nhập sai mật khẩu hiện tại nhiều lần (nguồn: đổi mật "
						+ "khẩu, request có access token hợp lệ) userId={}", lockedUntil, userId);
				throw authenticationService.accountLocked(lockedUntil, now);
			}
			// 400 chứ không phải 401: 401 khiến client tưởng access token hết hạn và
			// đăng xuất người dùng, trong khi họ chỉ gõ nhầm mật khẩu cũ.
			throw new ApiException("INVALID_CURRENT_PASSWORD", HttpStatus.BAD_REQUEST,
					"Mật khẩu hiện tại không đúng");
		}
		if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			throw new ApiException("PASSWORD_UNCHANGED", HttpStatus.BAD_REQUEST,
					"Mật khẩu mới phải khác mật khẩu hiện tại");
		}

		userRepository.updatePasswordAndClearLock(userId, passwordEncoder.encode(newPassword), now);
		activityRepository.save(AccountActivity.of(userId, AccountActivityType.PASSWORD_CHANGED, now));

		int revoked = refreshTokenRepository.revokeAllActiveByUserId(userId, now);
		log.info("Đổi mật khẩu thành công userId={}; thu hồi {} phiên", userId, revoked);
	}
}
