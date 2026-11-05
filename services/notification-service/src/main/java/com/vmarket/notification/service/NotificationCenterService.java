package com.vmarket.notification.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.vmarket.notification.domain.DeviceToken;
import com.vmarket.notification.domain.Notification;
import com.vmarket.notification.dto.NotificationResponse;
import com.vmarket.notification.dto.PageResponse;
import com.vmarket.notification.exception.ApiException;
import com.vmarket.notification.repository.DeviceTokenRepository;
import com.vmarket.notification.repository.NotificationRepository;

/**
 * Trung tâm thông báo của người dùng đang đăng nhập (FR-NOTI-01) và đăng ký thiết bị
 * nhận push (FR-NOTI-03). Mọi truy vấn đều lọc theo {@code userId} từ token: người
 * dùng không đọc / đánh dấu được thông báo của người khác.
 */
@Service
public class NotificationCenterService {

	public static final int MAX_PAGE_SIZE = 100;

	private final NotificationRepository notifications;
	private final DeviceTokenRepository devices;
	private final MongoTemplate mongo;
	private final Clock clock;

	public NotificationCenterService(NotificationRepository notifications, DeviceTokenRepository devices,
			MongoTemplate mongo, Clock clock) {
		this.notifications = notifications;
		this.devices = devices;
		this.mongo = mongo;
		this.clock = clock;
	}

	public PageResponse<NotificationResponse> list(String userId, int page, int size, boolean unreadOnly) {
		PageRequest pageable = PageRequest.of(page, size);
		Page<Notification> result = unreadOnly
				? notifications.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId, pageable)
				: notifications.findByUserIdOrderByCreatedAtDesc(userId, pageable);
		return PageResponse.of(result, NotificationResponse::from);
	}

	public long unreadCount(String userId) {
		return notifications.countByUserIdAndReadFalse(userId);
	}

	/** Idempotent: đánh dấu lại thông báo đã đọc giữ nguyên {@code readAt} ban đầu. */
	public NotificationResponse markRead(String userId, String notificationId) {
		Query owned = Query.query(Criteria.where("_id").is(notificationId).and("userId").is(userId));
		Notification updated = mongo.findAndModify(
				Query.query(Criteria.where("_id").is(notificationId).and("userId").is(userId).and("read").is(false)),
				new Update().set("read", true).set("readAt", Instant.now(clock)),
				FindAndModifyOptions.options().returnNew(true), Notification.class);
		if (updated == null) {
			updated = mongo.findOne(owned, Notification.class);
		}
		if (updated == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Không tìm thấy thông báo");
		}
		return NotificationResponse.from(updated);
	}

	/** @return số thông báo vừa được đánh dấu đã đọc */
	public long markAllRead(String userId) {
		return mongo.updateMulti(
				Query.query(Criteria.where("userId").is(userId).and("read").is(false)),
				new Update().set("read", true).set("readAt", Instant.now(clock)),
				Notification.class).getModifiedCount();
	}

	/**
	 * Đăng ký token cho người dùng hiện tại. Token đã thuộc người khác (đổi tài khoản trên
	 * cùng thiết bị) được chuyển sang người mới để người cũ không nhận push nữa.
	 */
	public void registerDevice(String userId, String token, DeviceToken.Platform platform) {
		Instant now = Instant.now(clock);
		mongo.upsert(
				Query.query(Criteria.where("token").is(token)),
				new Update().set("userId", userId).set("platform", platform).set("updatedAt", now)
						.setOnInsert("createdAt", now),
				DeviceToken.class);
	}

	public void unregisterDevice(String userId, String token) {
		devices.deleteByTokenAndUserId(token, userId);
	}
}
