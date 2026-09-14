package com.vmarket.events;

/**
 * Bao bì (envelope) chuẩn cho mọi sự kiện trên Event Bus.
 *
 * <p>Lược đồ JSON trên dây (khớp quy ước PBL6-39):
 * <pre>
 * {
 *   "eventId": "&lt;UUID&gt;",
 *   "eventType": "ProductCreated",
 *   "timestamp": 1730000000000,
 *   "payload": { ... }
 * }
 * </pre>
 *
 * <p>{@code eventId} dùng để tracing/idempotency; {@code timestamp} là epoch
 * millis (UTC); {@code payload} là dữ liệu riêng của từng sự kiện (vd
 * {@link ProductCreated}).
 *
 * @param eventId   định danh duy nhất của thông điệp
 * @param eventType tên sự kiện (xem {@link EventType}), đồng thời là routing key
 * @param timestamp epoch millis lúc phát sự kiện
 * @param payload   dữ liệu nghiệp vụ
 */
public record EventEnvelope(String eventId, String eventType, long timestamp, Object payload) {
}