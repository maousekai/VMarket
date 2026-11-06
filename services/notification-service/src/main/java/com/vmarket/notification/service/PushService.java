package com.vmarket.notification.service;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.vmarket.notification.domain.DeviceToken;
import com.vmarket.notification.push.PushSender;
import com.vmarket.notification.repository.DeviceTokenRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Gửi push tới mọi thiết bị đã đăng ký của người dùng (FR-NOTI-03). Best-effort: không
 * ném lỗi ra ngoài; token bị FCM báo không hợp lệ thì xoá.
 */
@Slf4j
@Service
public class PushService {

	private final DeviceTokenRepository devices;
	private final PushSender sender;

	public PushService(DeviceTokenRepository devices, PushSender sender) {
		this.devices = devices;
		this.sender = sender;
	}

	public void sendToUser(String userId, String title, String body, Map<String, String> data) {
		try {
			for (DeviceToken device : devices.findByUserId(userId)) {
				PushSender.Result result = sender.send(device.getToken(), title, body, data);
				if (result == PushSender.Result.INVALID_TOKEN) {
					devices.deleteByToken(device.getToken());
				}
			}
		} catch (RuntimeException ex) {
			log.warn("Không gửi được push cho user {}: {}", userId, ex.getClass().getSimpleName());
		}
	}
}
