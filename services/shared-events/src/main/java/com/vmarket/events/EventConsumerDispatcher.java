package com.vmarket.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Định tuyến một {@link EventEnvelope} đã nhận về tới các {@link EventConsumer}
 * khớp {@code eventType}, đồng thời chuyển payload JSON sang đúng kiểu Java mà
 * từng consumer khai báo.
 *
 * <p>Suy giảm có kiểm soát (NFR-REL-02): nếu một consumer ném lỗi, dispatcher chỉ
 * log và tiếp tục các consumer còn lại (không làm chết toàn bộ listener). Việc
 * retry/DLQ sẽ được bổ sung ở giai đoạn sau.
 */
public class EventConsumerDispatcher {

	private static final Logger log = LoggerFactory.getLogger(EventConsumerDispatcher.class);

	private final EventConsumerRegistry registry;
	private final EventsJson json;

	public EventConsumerDispatcher(EventConsumerRegistry registry, EventsJson json) {
		this.registry = registry;
		this.json = json;
	}

	/** Phân phối envelope cho mọi consumer khớp eventType. */
	public void dispatch(EventEnvelope envelope) {
		for (EventConsumer<?> consumer : registry.consumersFor(envelope.eventType())) {
			handle(consumer, envelope);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private <T> void handle(EventConsumer<T> consumer, EventEnvelope envelope) {
		try {
			T payload = json.convert(envelope.payload(), consumer.payloadType());
			consumer.handle(payload, envelope);
		} catch (Exception ex) {
			log.error("Lỗi khi xử lý sự kiện {} trên consumer {}: {}",
					envelope.eventType(), consumer.getClass().getName(), ex.getMessage(), ex);
		}
	}
}