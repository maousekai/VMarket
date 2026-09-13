"""PBL6-39 — AI Search Service: consumer Event Bus (RabbitMQ).

Theo quy ước event bus dùng chung (docs/event-bus.md):
  - topic exchange:  vmarket.events
  - routing key   :  tên sự kiện (PascalCase, khớp danh mục SRS §8.1)
  - message schema:  {"eventId", "eventType", "timestamp", "payload"}

AI Search lắng nghe ProductCreated/Updated/Deleted để đồng bộ chỉ mục
Elasticsearch (FR-SRCH-04). Ở giai đoạn PBL6-39 chỉ log payload; việc ghi index
sẽ điền sau khi cài Elasticsearch client.
"""

import json
import os
import threading
import time

import pika

EXCHANGE = os.getenv("EVENT_EXCHANGE", "vmarket.events")
QUEUE = os.getenv("EVENT_QUEUE", "ai-search.events")

# Các sự kiện AI Search quan tâm (FR-SRCH-04).
EVENT_TYPES = ["ProductCreated", "ProductUpdated", "ProductDeleted"]


def _handle_product_event(event_type: str, payload: dict) -> None:
    # TODO(FR-SRCH-04): cập nhật chỉ mục Elasticsearch + trích xuất embedding
    # ảnh sản phẩm bằng mô hình CNN trong vòng tối đa 1 phút.
    print(f"[event] nhan {event_type}: {payload}", flush=True)


_HANDLERS = {
    "ProductCreated": _handle_product_event,
    "ProductUpdated": _handle_product_event,
    "ProductDeleted": _handle_product_event,
}


def _on_message(ch, method, _properties, body: bytes) -> None:
    try:
        envelope = json.loads(body.decode("utf-8"))
        event_type = envelope.get("eventType")
        handler = _HANDLERS.get(event_type)
        if handler is not None:
            handler(event_type, envelope.get("payload") or {})
        else:
            print(f"[event] bo qua su kien {event_type}", flush=True)
        ch.basic_ack(delivery_tag=method.delivery_tag)
    except Exception as exc:  # noqa: BLE001
        print(f"[event] loi xu ly thong diep: {exc}", flush=True)
        # Nack khong requeue de tranh loop vo tan; san sang cho DLQ (docs/event-bus.md §7)
        ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)


def _connection_parameters() -> pika.ConnectionParameters:
    return pika.ConnectionParameters(
        host=os.getenv("RABBITMQ_HOST", "localhost"),
        port=int(os.getenv("RABBITMQ_PORT", "5672")),
        credentials=pika.PlainCredentials(
            os.getenv("RABBITMQ_USERNAME", "guest"),
            os.getenv("RABBITMQ_PASSWORD", "guest"),
        ),
        heartbeat=30,
        blocked_connection_timeout=30,
    )


def start_consumer() -> None:
    """Kết nối RabbitMQ, khai báo exchange/queue/binding rồi lắng nghe."""
    connection = pika.BlockingConnection(_connection_parameters())
    channel = connection.channel()

    channel.exchange_declare(exchange=EXCHANGE, exchange_type="topic", durable=True)
    channel.queue_declare(queue=QUEUE, durable=True)
    for event_type in EVENT_TYPES:
        channel.queue_bind(queue=QUEUE, exchange=EXCHANGE, routing_key=event_type)

    channel.basic_qos(prefetch_count=1)
    channel.basic_consume(queue=QUEUE, on_message_callback=_on_message)

    print(f"[event] dang lang nghe exchange={EXCHANGE} queue={QUEUE}", flush=True)
    try:
        channel.start_consuming()
    finally:
        connection.close()


def start_event_consumer_thread() -> None:
    """Chạy consumer trong luồng nền (daemon), tự reconnect khi RabbitMQ chưa sẵn sàng."""

    def _run() -> None:
        while True:
            try:
                start_consumer()
            except Exception as exc:  # noqa: BLE001
                print(f"[event] chua ket noi duoc RabbitMQ ({exc}); thu lai sau 5s...", flush=True)
                time.sleep(5)

    threading.Thread(target=_run, daemon=True, name="event-consumer").start()