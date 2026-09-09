package com.vmarket.events;

/**
 * Cổng phát sự kiện dùng chung. Service chỉ cần inject interface này rồi gọi
 * {@link #publish(String, Object)} — không cần biết chi tiết RabbitMQ.
 */
public interface EventPublisher {

	/**
	 * Phát một sự kiện lên topic exchange chung.
	 *
	 * @param eventType tên sự kiện (xem {@link EventType}) — đồng thời là routing key
	 * @param payload   dữ liệu nghiệp vụ của sự kiện
	 */
	void publish(String eventType, Object payload);
}