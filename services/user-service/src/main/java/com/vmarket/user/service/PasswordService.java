package com.vmarket.user.service;

import org.springframework.stereotype.Service;

import com.vmarket.user.client.AuthServiceClient;
import com.vmarket.user.dto.ChangePasswordRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-USER-03 — Đổi mật khẩu khi đã đăng nhập.
 *
 * <p>SRS đặt chức năng ở User Service nhưng mật khẩu nằm trong CSDL auth-service, nên
 * service này chỉ xác định <b>ai</b> đang đổi (userId từ access token đã verify, không
 * bao giờ từ body) rồi uỷ quyền cho auth-service kiểm tra mật khẩu hiện tại và ghi mật
 * khẩu mới. Không giữ bản sao mật khẩu nào ở đây: hai nguồn sự thật cho một mật khẩu
 * nghĩa là đổi xong vẫn đăng nhập được bằng mật khẩu cũ.
 *
 * <p>Hệ quả phía auth-service (xem {@code PasswordChangeService} bên đó): nhập sai mật
 * khẩu hiện tại tính chung bộ đếm khoá đăng nhập; đổi thành công thu hồi mọi refresh token.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordService {

	private final AuthServiceClient authServiceClient;

	public void changePassword(String userId, ChangePasswordRequest request) {
		authServiceClient.changePassword(userId, request.currentPassword(), request.newPassword());
		log.info("Đổi mật khẩu thành công userId={}", userId);
	}
}
