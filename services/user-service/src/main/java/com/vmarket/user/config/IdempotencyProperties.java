package com.vmarket.user.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Cấu hình cơ chế {@code Idempotency-Key} (prefix {@code app.idempotency}).
 *
 * <p>Hai mốc thời gian, hai việc khác nhau — dễ nhầm nên tách rõ:
 * {@code claim-timeout} là "bao lâu thì coi một request đang chạy dở là đã chết",
 * còn {@code retention} là "giữ kết quả đã xử lý xong bao lâu để còn phát lại".
 */
@ConfigurationProperties(prefix = "app.idempotency")
@Validated
@Getter
@Setter
public class IdempotencyProperties {

	/** Tắt hẳn cơ chế (header bị bỏ qua) — để dành cho việc gỡ lỗi ở môi trường dev. */
	private boolean enabled = true;

	/**
	 * Quá mốc này mà một request vẫn chưa xong thì coi như tiến trình xử lý nó đã
	 * chết (service bị kill giữa chừng) và nhả key ra cho lần gửi lại.
	 *
	 * <p>Phải dài hơn thời gian xử lý thật của endpoint chậm nhất, nếu không hai
	 * request đang chạy song song thật sự sẽ cùng được chạy.
	 */
	@NotNull
	private Duration claimTimeout = Duration.ofMinutes(5);

	/**
	 * Giữ kết quả bao lâu. Retry của client xảy ra trong vài giây tới vài phút; một
	 * ngày là rộng rãi mà vẫn không để bảng phình vô hạn.
	 */
	@NotNull
	private Duration retention = Duration.ofDays(1);
}
