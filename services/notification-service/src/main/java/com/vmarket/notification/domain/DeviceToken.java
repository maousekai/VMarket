package com.vmarket.notification.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FCM registration token của một thiết bị (FR-NOTI-03) — collection {@code device_tokens}.
 * Token là duy nhất: đăng nhập tài khoản khác trên cùng máy sẽ chuyển token sang người mới.
 */
@Document("device_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceToken {

	public enum Platform {
		ANDROID, IOS, WEB
	}

	@Id
	private String id;

	@Indexed(unique = true)
	private String token;

	@Indexed
	private String userId;

	private Platform platform;

	private Instant createdAt;

	private Instant updatedAt;
}
