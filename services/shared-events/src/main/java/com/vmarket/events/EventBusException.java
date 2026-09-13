package com.vmarket.events;

/**
 * Ngoại lệ runtime nội bộ của event bus (lỗi serialize/parse JSON...).
 * Không mong service lõi bắt loại lỗi này — chỉ để log/tra soát.
 */
public class EventBusException extends RuntimeException {

	public EventBusException(String message, Throwable cause) {
		super(message, cause);
	}
}