package com.vmarket.cart.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình nghiệp vụ giỏ hàng (khoá {@code app.cart.*}).
 */
@ConfigurationProperties(prefix = "app.cart")
public class CartProperties {

	/**
	 * Thời gian sống của giỏ trong Redis. Mỗi lần ghi (thêm/sửa/xoá) đều refresh
	 * lại TTL — giỏ của người dùng hoạt động thường xuyên sẽ không bao giờ hết hạn.
	 */
	private int ttlDays = 30;

	/** Tiền tố key Redis: {@code cart:{userId}}. */
	private String keyPrefix = "cart:";

	public int getTtlDays() {
		return ttlDays;
	}

	public void setTtlDays(int ttlDays) {
		this.ttlDays = ttlDays;
	}

	public String getKeyPrefix() {
		return keyPrefix;
	}

	public void setKeyPrefix(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}
}
