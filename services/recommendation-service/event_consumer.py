"""Consumer Event Bus (RabbitMQ) — Recommendation Service.

Theo quy ước event bus dùng chung (docs/event-bus.md):
  - topic exchange:  vmarket.events
  - routing key   :  tên sự kiện (PascalCase, khớp danh mục SRS §8.1)
  - queue         :  recommendation.events, có DLX trỏ vmarket.events.dlx

Recommendation lắng nghe ProductCreated/Updated/Deleted để cập nhật đặc trưng
gợi ý (docs/event-bus.md §5) và OrderPlaced/OrderStatusChanged cho tương quan
đơn hàng. Ở giai đoạn skeleton chỉ log payload; logic tính gợi ý sẽ điền sau.

Ghi nhớ Review 3 (worklogs/PBL6-15.md): queue consumer phải được KHAI BÁO SỚM
ngay cả khi logic chưa có — nếu không, event catalog phát ra sẽ không có queue
nào nhận (NO_ROUTE) và kẹt ở outbox phía Product Catalog.
"""

import json
import os
import threading
import time

import pika

EXCHANGE = os.getenv("EVENT_EXCHANGE", "vmarket.events")
QUEUE = os.getenv("EVENT_QUEUE", "recommendation.events")
# Quy ước dead-letter (docs/event-bus.md §2): {exchange}.dlx và {queue}.dead.
DEAD_EXCHANGE = EXCHANGE + ".dlx"
DEAD_QUEUE = QUEUE + ".dead"
DEAD_ROUTING_KEY = QUEUE + ".dead"

# Các sự kiện Recommendation quan tâm (docs/event-bus.md §5).
EVENT_TYPES = [
    "ProductCreated",
    "ProductUpdated",
    "ProductDeleted",
    "OrderPlaced",
    "OrderStatusChanged",
]


def _handle_event(event_type: str, payload: dict) -> None:
    # TODO (PBL6): cập nhật đặc trưng gợi ý cá nhân hóa - FR-REC-01..04
    print(f"[event] nhan {event_type}: {payload}", flush=True)


_HANDLERS = {event_type: _handle_event for event_type in EVENT_TYPES}


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
        # Nack không requeue → message rơi vào {queue}.dead qua vmarket.events.dlx
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
    """Kết nối RabbitMQ, khai báo exchange/queue/DLQ/binding rồi lắng nghe."""
    connection = pika.BlockingConnection(_connection_parameters())
    channel = connection.channel()

    channel.exchange_declare(exchange=EXCHANGE, exchange_type="topic", durable=True)
    channel.exchange_declare(exchange=DEAD_EXCHANGE, exchange_type="topic", durable=True)
    # DLX theo đúng convention Java (EventBusAutoConfiguration) để message lỗi
    # consumer rơi vào recommendation.events.dead thay vì bị mất.
    channel.queue_declare(queue=QUEUE, durable=True, arguments={
        "x-dead-letter-exchange": DEAD_EXCHANGE,
        "x-dead-letter-routing-key": DEAD_ROUTING_KEY,
    })
    channel.queue_declare(queue=DEAD_QUEUE, durable=True)
    channel.queue_bind(queue=DEAD_QUEUE, exchange=DEAD_EXCHANGE, routing_key=DEAD_ROUTING_KEY)
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
