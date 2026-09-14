package com.vmarket.events;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sổ đăng ký các {@link EventConsumer} hiện có trong app context, chỉ mục theo
 * {@code eventType}. Dùng cho {@link EventConsumerDispatcher} để định tuyến
 * thông điệp tới đúng consumer.
 */
public class EventConsumerRegistry {

	private final Map<String, List<EventConsumer<?>>> consumersByType = new LinkedHashMap<>();

	public EventConsumerRegistry(List<EventConsumer<?>> consumers) {
		for (EventConsumer<?> consumer : consumers) {
			consumersByType.computeIfAbsent(consumer.eventType(), key -> new ArrayList<>()).add(consumer);
		}
	}

	/** Trả danh sách (có thể rỗng) consumer đăng ký cho {@code eventType}. */
	public List<EventConsumer<?>> consumersFor(String eventType) {
		return consumersByType.getOrDefault(eventType, List.of());
	}
}