package com.vmarket.events;

/**
 * Hợp đồng cho một service muốn NHẬN một loại sự kiện.
 *
 * <p>Service chỉ cần implement interface này (khai báo là Spring bean):
 * <pre>{@code
 * @Component
 * class ProductIndexConsumer implements EventConsumer<ProductCreated> {
 *     public String eventType() { return EventType.PRODUCT_CREATED; }
 *     public Class<ProductCreated> payloadType() { return ProductCreated.class; }
 *     public void handle(ProductCreated payload, EventEnvelope envelope) { ... }
 * }
 * }</pre>
 *
 * <p>Thư viện sẽ tự đăng ký consumer vào {@link EventConsumerRegistry} và gọi
 * {@link #handle(Object, EventEnvelope)} khi có thông điệp khớp {@code eventType}.
 *
 * @param <T> kiểu payload của sự kiện
 */
public interface EventConsumer<T> {

	/** Tên sự kiện consumer này xử lý (xem {@link EventType}). */
	String eventType();

	/** Kiểu payload để thư viện deserialize JSON thành đối tượng cụ thể. */
	Class<T> payloadType();

	/** Xử lý payload khi nhận sự kiện (payload đã deserialize đúng kiểu). */
	void handle(T payload, EventEnvelope envelope);
}