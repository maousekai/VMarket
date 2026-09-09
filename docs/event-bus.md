# Event Bus (RabbitMQ) — Quy ước dùng chung

> Ticket: **PBL6-39**. Tài liệu này là "nguồn sự thật" cho cách các service giao tiếp
> bất đồng bộ qua RabbitMQ. Mọi service (Java/Spring Boot và Python/FastAPI) PHẢI
> tuân theo quy ước này khi publish / subscribe sự kiện.

## 1. Nguyên tắc

Theo kiến trúc đã thống nhất ở PBL6-4 (database-per-service):

- Mỗi service sở hữu CSDL riêng, **KHÔNG truy cập trực tiếp CSDL của service khác**.
- Đồng bộ dữ liệu giữa các service chỉ qua **API (đồng bộ)** hoặc **sự kiện (bất đồng bộ)**.
- Một sự kiện có thể có nhiều service nhận (publish/subscribe), service phát không
  biết và không phụ thuộc ai đang nghe.

Ví dụ: Product Catalog **phát** `ProductCreated`; AI Search **nhận** để đồng bộ chỉ
mục Elasticsearch (FR-SRCH-04), Recommendation nhận để cập nhật đặc trưng gợi ý.

## 2. Topology (exchange / queue / routing key)

| Thành phần | Quy ước | Ví dụ |
|---|---|---|
| Exchange | **1 topic exchange** chung cho cả hệ: `vmarket.events` (durable) | `vmarket.events` |
| Routing key | `=` **tên sự kiện** (PascalCase, khớp SRS §8.1) | `ProductCreated` |
| Queue | `{tên-service}.events` (durable, do chính service nhận khai báo) | `ai-search.events` |
| Binding | Queue bind tới exchange theo từng routing key mà service nhận | `ai-search.events` ← `ProductCreated` |

- Exchange, queue, binding **durable** (không tự xoá) để không mất sự kiện.
- Service phát sự kiện **không tự khai báo queue** (không cần biết ai nghe).
- Service nhận tự khai báo queue + binding cho các sự kiện nó quan tâm.

## 3. Lược đồ thông điệp (message schema)

Mọi thông điệp đều là JSON theo **envelope** chuẩn:

```json
{
  "eventId": "3f2c...uuid",
  "eventType": "ProductCreated",
  "timestamp": 1730000000000,
  "payload": { }
}
```

- `eventId` — định danh duy nhất (UUID), dùng để tracing / idempotency.
- `eventType` — tên sự kiện (khớp `§2` routing key).
- `timestamp` — epoch **millis** (UTC) lúc phát sự kiện.
- `payload` — dữ liệu nghiệp vụ riêng của từng sự kiện (định nghĩa ở `§5`).

## 4. Cách dùng

### 4.1. Service Java (Spring Boot) — qua thư viện `shared-events`

Thêm dependency:

```xml
<dependency>
  <groupId>com.vmarket</groupId>
  <artifactId>shared-events</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

**Publish** (tự động cấu hình, không cần cấu hình thêm):

```java
@Service
public class ProductEventPublisher {
    private final EventPublisher eventPublisher; // inject từ shared-events

    public void publishCreated(ProductCreated payload) {
        eventPublisher.publish(EventType.PRODUCT_CREATED, payload);
    }
}
```

**Subscribe** — chỉ cần implement `EventConsumer<T>` (khai báo là Spring bean) và bật
`listen`:

```java
@Component
public class ProductIndexConsumer implements EventConsumer<ProductCreated> {
    public String eventType() { return EventType.PRODUCT_CREATED; }
    public Class<ProductCreated> payloadType() { return ProductCreated.class; }
    public void handle(ProductCreated payload, EventEnvelope envelope) {
        // đồng bộ chỉ mục...
    }
}
```

```yaml
app:
  events:
    listen: true
    queue: notification.events            # {tên-service}.events
    bindings:                             # routing key cần nhận
      - ProductCreated
      - OrderPlaced
```

### 4.2. Service Python (FastAPI) — qua `pika`

Không dùng được thư viện Java; Python tự khai báo theo ĐÚNG quy ước ở `§2`/`§3`
(xem mẫu `services/ai-search-service/event_consumer.py`):

```python
channel.exchange_declare(exchange="vmarket.events", exchange_type="topic", durable=True)
channel.queue_declare(queue="ai-search.events", durable=True)
channel.queue_bind(queue="ai-search.events", exchange="vmarket.events", routing_key="ProductCreated")
```

## 5. Danh mục sự kiện

Đầy đủ xem **SRS §8.1 Phụ lục A**. Dưới đây là một số sự kiện chính (hằng số trong
`com.vmarket.events.EventType`):

| Sự kiện | Phát | Nhận |
|---|---|---|
| `ProductCreated` / `ProductUpdated` / `ProductDeleted` | Product Catalog | AI Search, Recommendation |
| `ShopApproved` / `ShopSuspended` | Shop | Notification |
| `OrderPlaced` | Order | Notification, Delivery |
| `StockReserved` / `StockReleased` | Product Catalog | Order |
| `PaymentSucceeded` / `PaymentFailed` | Payment | Order, Notification |
| `DeliveryAssigned` | Delivery | Notification |
| `ReviewCreated` | Review | Notification, Product |

## 6. Demo end-to-end (PBL6-39)

Luồng: **Product Catalog (Java)** phát → **AI Search (Python)** nhận.

1. Khởi động RabbitMQ: `docker compose up -d rabbitmq`.
2. Chạy product-service (`mvnw spring-boot:run`) và ai-search-service (`uvicorn main:app`).
3. Gọi demo: `POST http://localhost:8084/api/products/_demo/product-created`.
4. Quan sát log ai-search-service in ra `ProductCreated` vừa nhận.

## 7. Lưu ý / hướng phát triển sau

- **Retry + DLQ** (NFR-REL-02): hiện chưa cài; thông điệp lỗi sẽ được thêm retry và
  chuyển vào Dead-Letter Queue (`{queue}.dlq`) ở giai đoạn sau.
- **Idempotency**: consumer nên dựa vào `eventId` để tránh xử lý trùng (vd khi retry).
- **Phá phiên bản schema**: khi payload thay đổi, giữ tương thích ngược hoặc bump
  version và thống nhất với các service nhận trước khi deploy.