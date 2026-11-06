package com.vmarket.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Huỷ đăng ký token khi đăng xuất trên thiết bị. Token nằm trong body để không lộ ở access log. */
public record DeviceTokenDeleteRequest(
		@NotBlank(message = "token không được để trống") @Size(max = 4096, message = "token quá dài") String token) {
}
