package com.vmarket.events.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình event bus dùng chung (khoá {@code app.events.*}).
 *
 * <p>Service CHỈ publish thì không cần set gì thêm (dùng giá trị mặc định của
 * exchange). Service muốn NHẬN sự kiện thì bật {@code listen=true} và khai báo
 * queue + danh sách routing key cần bind.
 */
@ConfigurationProperties(prefix = "app.events")
public class EventBusProperties {

	/** Tên topic exchange dùng chung. Toàn hệ thống dùng MỘT exchange này. */
	private String exchange = "vmarket.events";

	/**
	 * Bật chế độ lắng nghe (tạo queue + listener container). Service phát sự kiện
	 * để {@code false} để không mở kết nối consumer lúc khởi động.
	 */
	private boolean listen = false;

	/** Tên queue của service khi bật listen (quy ước: {@code <service>.events}). */
	private String queue = "";

	/** Danh sách routing key (tên sự kiện) mà queue này bind tới exchange. */
	private List<String> bindings = new ArrayList<>();

	public String getExchange() {
		return exchange;
	}

	public void setExchange(String exchange) {
		this.exchange = exchange;
	}

	public boolean isListen() {
		return listen;
	}

	public void setListen(boolean listen) {
		this.listen = listen;
	}

	public String getQueue() {
		return queue;
	}

	public void setQueue(String queue) {
		this.queue = queue;
	}

	public List<String> getBindings() {
		return bindings;
	}

	public void setBindings(List<String> bindings) {
		this.bindings = bindings;
	}
}