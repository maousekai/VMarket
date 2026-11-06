package com.vmarket.notification.email;

/** Gửi email thất bại — event bus sẽ retry rồi đưa vào DLQ. */
public class EmailDeliveryException extends RuntimeException {

	public EmailDeliveryException(String message) {
		super(message);
	}

	public EmailDeliveryException(String message, Throwable cause) {
		super(message, cause);
	}
}
