package com.vmarket.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Định tuyến một {@link EventEnvelope} đã nhận về tới các {@link EventConsumer}
 * khớp {@code eventType}, đồng thời chuyển payload JSON sang đúng kiểu Java mà
 * từng consumer khai báo.
 *
 * <p>Nếu một consumer lỗi, dispatcher vẫn chạy các consumer còn lại rồi ném lỗi
 * tổng hợp để listener áp dụng retry/DLQ (NFR-REL-02).
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
		RuntimeException firstFailure = null;
		for (EventConsumer<?> consumer : registry.consumersFor(envelope.eventType())) {
			try {
				handle(consumer, envelope);
			} catch (RuntimeException ex) {
				if (firstFailure == null) firstFailure = ex;
				log.error("Lỗi khi xử lý sự kiện {} trên consumer {}: {}",
						envelope.eventType(), consumer.getClass().getName(), ex.getMessage(), ex);
			}
		}
		if (firstFailure != null) {
			throw new EventBusException("Có consumer xử lý thất bại cho " + envelope.eventType(), firstFailure);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private <T> void handle(EventConsumer<T> consumer, EventEnvelope envelope) {
		T payload = json.convert(envelope.payload(), consumer.payloadType());
		consumer.handle(payload, envelope);
	}
}
