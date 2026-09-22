package com.vmarket.auth.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một sự kiện trong lịch sử hoạt động cơ bản của tài khoản (FR-USER-04, migration V8).
 *
 * <p>Chỉ thêm, không sửa / xoá. Luôn ghi trong <b>cùng transaction</b> với thay đổi
 * trạng thái mà nó mô tả, nên không có trạng thái nào đổi mà thiếu lịch sử. Cột
 * {@code users.suspended_*} chỉ là trạng thái hiện tại (mở khoá sẽ xoá chúng); ai khoá,
 * vì sao, ai mở khoá, khi nào — nằm ở đây.
 *
 * <p>{@code createdAt} do service gán (không {@code @CreationTimestamp}) để sự kiện
 * {@link AccountActivityType#SUSPENDED} mang đúng thời điểm ghi vào {@code suspended_at}.
 */
@Entity
@Table(name = "account_activities")
@Getter
@Setter
@NoArgsConstructor
public class AccountActivity extends BaseEntity {

	@Column(name = "user_id", nullable = false, length = BaseEntity.ID_LENGTH)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "action", nullable = false, length = 40)
	private AccountActivityType action;

	/** userId của Admin thực hiện; {@code null} = chính người dùng hoặc hệ thống. */
	@Column(name = "actor_id", length = BaseEntity.ID_LENGTH)
	private String actorId;

	@Column(name = "reason", length = 500)
	private String reason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public static AccountActivity of(String userId, AccountActivityType action, String actorId, String reason,
			Instant at) {
		AccountActivity activity = new AccountActivity();
		activity.setUserId(userId);
		activity.setAction(action);
		activity.setActorId(actorId);
		activity.setReason(reason);
		activity.setCreatedAt(at);
		return activity;
	}

	/** Sự kiện do chính người dùng hoặc hệ thống gây ra (không có Admin, không lý do). */
	public static AccountActivity of(String userId, AccountActivityType action, Instant at) {
		return of(userId, action, null, null, at);
	}
}
