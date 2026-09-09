package com.vmarket.events;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bao gói JSON (Jackson 3) dùng riêng cho event bus. Tách biệt với ObjectMapper
 * của ứng dụng để không bị xung đột bean / cấu hình module.
 *
 * <p>Đây là thành phần dùng chung: vừa serialize {@link EventEnvelope} khi
 * publish, vừa deserialize + chuyển payload ({@code Object} → kiểu cụ thể) khi
 * subscribe.
 */
public final class EventsJson {

	private final ObjectMapper mapper = JsonMapper.builder().build();

	/** Serialize object (thường là {@link EventEnvelope}) thành JSON bytes. */
	public byte[] write(Object value) {
		try {
			return mapper.writeValueAsBytes(value);
		} catch (Exception ex) {
			throw new EventBusException("Không thể serialize event sang JSON", ex);
		}
	}

	/** Deserialize JSON bytes thành {@link EventEnvelope}. */
	public EventEnvelope readEnvelope(byte[] body) {
		try {
			return mapper.readValue(body, EventEnvelope.class);
		} catch (Exception ex) {
			throw new EventBusException("Không thể parse event từ JSON", ex);
		}
	}

	/** Chuyển payload dạng khái quát (Map/JsonNode) sang kiểu cụ thể của consumer. */
	public <T> T convert(Object value, Class<T> type) {
		try {
			return mapper.convertValue(value, type);
		} catch (Exception ex) {
			throw new EventBusException("Không thể convert payload sang " + type.getSimpleName(), ex);
		}
	}
}