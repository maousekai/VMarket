package com.vmarket.auth.dto;

/**
 * Trạng thái tài khoản trả về cho client.
 * <ul>
 *   <li>{@code PENDING} — đã tạo, chưa xác thực email (FR-AUTH-01).</li>
 *   <li>{@code ACTIVE} — email đã xác thực.</li>
 * </ul>
 */
public enum AccountStatus {
	PENDING,
	ACTIVE;

	public static AccountStatus of(boolean emailVerified) {
		return emailVerified ? ACTIVE : PENDING;
	}
}
