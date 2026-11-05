package com.vmarket.notification.dto;

import com.vmarket.notification.domain.DeviceToken;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Đăng ký FCM registration token của thiết bị đang đăng nhập (FR-NOTI-03). */
public record DeviceTokenRequest(
		@NotBlank(message = "token không được để trống") @Size(max = 4096, message = "token quá dài") String token,
		@NotNull(message = "platform phải là ANDROID, IOS hoặc WEB") DeviceToken.Platform platform) {
}
