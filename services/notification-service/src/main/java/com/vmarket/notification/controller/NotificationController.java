package com.vmarket.notification.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.notification.dto.DeviceTokenDeleteRequest;
import com.vmarket.notification.dto.DeviceTokenRequest;
import com.vmarket.notification.dto.NotificationPreferencesDto;
import com.vmarket.notification.dto.NotificationResponse;
import com.vmarket.notification.dto.PageResponse;
import com.vmarket.notification.security.CurrentUser;
import com.vmarket.notification.service.NotificationCenterService;
import com.vmarket.notification.service.NotificationPreferenceService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Trung tâm thông báo (FR-NOTI-01) và đăng ký thiết bị nhận push (FR-NOTI-03). Mọi
 * endpoint yêu cầu access token; userId lấy từ claim {@code sub}.
 */
@Validated
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

	private final NotificationCenterService service;
	private final NotificationPreferenceService preferences;
	private final CurrentUser currentUser;

	public NotificationController(NotificationCenterService service, NotificationPreferenceService preferences,
			CurrentUser currentUser) {
		this.service = service;
		this.preferences = preferences;
		this.currentUser = currentUser;
	}

	/** Tuỳ chọn nhận email / push theo nhóm thông báo (mặc định bật hết). */
	@GetMapping("/preferences")
	public NotificationPreferencesDto.Response preferences() {
		return preferences.get(currentUser.id());
	}

	@PutMapping("/preferences")
	public NotificationPreferencesDto.Response updatePreferences(
			@Valid @RequestBody NotificationPreferencesDto.Update request) {
		return preferences.update(currentUser.id(), request);
	}

	@GetMapping
	public PageResponse<NotificationResponse> list(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(NotificationCenterService.MAX_PAGE_SIZE) int size,
			@RequestParam(defaultValue = "false") boolean unreadOnly) {
		return service.list(currentUser.id(), page, size, unreadOnly);
	}

	@GetMapping("/unread-count")
	public Map<String, Long> unreadCount() {
		return Map.of("count", service.unreadCount(currentUser.id()));
	}

	@PatchMapping("/{id}/read")
	public NotificationResponse markRead(@PathVariable String id) {
		return service.markRead(currentUser.id(), id);
	}

	@PatchMapping("/read-all")
	public Map<String, Long> markAllRead() {
		return Map.of("updated", service.markAllRead(currentUser.id()));
	}

	@PostMapping("/devices")
	public ResponseEntity<Void> registerDevice(@Valid @RequestBody DeviceTokenRequest request) {
		service.registerDevice(currentUser.id(), request.token(), request.platform());
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/devices")
	public ResponseEntity<Void> unregisterDevice(@Valid @RequestBody DeviceTokenDeleteRequest request) {
		service.unregisterDevice(currentUser.id(), request.token());
		return ResponseEntity.noContent().build();
	}
}
