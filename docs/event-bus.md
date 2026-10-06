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
| Queue | `{tên-service}.events.v{n}` (durable, do chính service nhận khai báo) | `product.events.v2` |
| Binding | Queue bind tới exchange theo từng routing key mà service nhận | `ai-search.events.v2` ← `ProductCreated` |
| Dead-letter | `{exchange}.dlx` và `{queue}.dead` | `vmarket.events.dlx`, `product.events.v2.dead` |

- Exchange, queue, binding **durable** (không tự xoá) để không mất sự kiện.
- Service phát sự kiện **không tự khai báo queue** (không cần biết ai nghe).
- Service nhận tự khai báo queue + binding cho các sự kiện nó quan tâm.
- Khi bổ sung hoặc thay đổi queue arguments (ví dụ DLX), phải tăng hậu tố phiên
  bản queue. Không redeclare một durable queue cũ với arguments khác vì RabbitMQ
  sẽ trả `PRECONDITION_FAILED`. Queue cũ chỉ được xóa sau khi đã drain và rollout
  consumer phiên bản mới hoàn tất.

### Chuyển queue Product từ `product.events` sang `product.events.v2`

Khi nâng cấp một broker đang có queue `product.events`, tạm dừng publisher,
khởi động Product Service để khai báo `product.events.v2`, rồi chuyển các message
đang chờ từ queue cũ sang queue mới bằng RabbitMQ Shovel với acknowledgement
`on-confirm` (đích là default exchange, routing key `product.events.v2`).
Kiểm tra `messages_ready` và `messages_unacknowledged` của queue cũ đều bằng 0
trước khi xóa queue cũ và bật lại publisher. Giữ nguyên `eventId` khi chuyển;
consumer xử lý các event trùng theo khóa nghiệp vụ. Không xóa queue cũ khi còn
message hoặc khi Shovel chưa xác nhận đích đã nhận.

AI Search áp dụng cùng quy trình khi chuyển `ai-search.events` sang
`ai-search.events.v2`: khai báo queue v2 cùng DLX, Shovel toàn bộ message từ queue
cũ với acknowledgement `on-confirm`, kiểm tra queue cũ đã drain, rồi mới xóa.

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
    queue: notification.events.v1         # {tên-service}.events.v{n}
    bindings:                             # routing key cần nhận
      - ProductCreated
      - OrderPlaced
```

### 4.2. Service Python (FastAPI) — qua `pika`

Không dùng được thư viện Java; Python tự khai báo theo ĐÚNG quy ước ở `§2`/`§3`
(xem mẫu `services/ai-search-service/event_consumer.py` và
`services/recommendation-service/event_consumer.py`). Queue PHẢI khai báo kèm
arguments DLX, và DLQ phải được khai báo + bind — nếu không message lỗi consumer
sẽ bị mất thay vì rơi vào `{queue}.dead`:

```python
channel.exchange_declare(exchange="vmarket.events", exchange_type="topic", durable=True)
channel.exchange_declare(exchange="vmarket.events.dlx", exchange_type="topic", durable=True)
channel.queue_declare(queue="ai-search.events.v2", durable=True, arguments={
    "x-dead-letter-exchange": "vmarket.events.dlx",
    "x-dead-letter-routing-key": "ai-search.events.v2.dead",
})
channel.queue_declare(queue="ai-search.events.v2.dead", durable=True)
channel.queue_bind(queue="ai-search.events.v2.dead", exchange="vmarket.events.dlx",
                   routing_key="ai-search.events.v2.dead")
channel.queue_bind(queue="ai-search.events.v2", exchange="vmarket.events", routing_key="ProductCreated")
```

CẢNH BÁO (bài học PBL6-15 Review 3): consumer catalog (AI Search,
Recommendation) phải khai báo queue/binding SỚM — kể cả khi logic nghiệp vụ
chưa có (skeleton chỉ log). Nếu không, `ProductCreated`/`ProductUpdated`/
`ProductDeleted` phát ra sẽ không có queue nào nhận (`NO_ROUTE`) và event bị
park phía outbox của Product Catalog (xem `§7`).

## 5. Danh mục sự kiện

Đầy đủ xem **SRS §8.1 Phụ lục A**. Dưới đây là một số sự kiện chính (hằng số trong
`com.vmarket.events.EventType`):

| Sự kiện | Phát | Nhận |
|---|---|---|
| `ProductCreated` / `ProductUpdated` / `ProductDeleted` | Product Catalog | AI Search, Recommendation |
| `ShopApproved` / `ShopSuspended` | Shop | Auth, Product Catalog, Notification |
| `OrderPlaced` | Order | Product Catalog, Payment, Notification, Recommendation |
| `OrderStatusChanged` | Order | Product Catalog, Notification, Recommendation |
| `StockReserved` / `StockReleased` / `StockReservationFailed` | Product Catalog | Order |
| `ProductModerated` | Product Catalog | Notification |
| `ProductModerationRequested` | Product Catalog | Notification/Admin workflow |
| `PaymentSucceeded` / `PaymentFailed` | Payment | Order, Notification |
| `DeliveryAssigned` | Delivery | Notification |
| `ReviewCreated` | Review | Notification, Product |
| `ReturnRequested` / `ReturnResolved` | Order | Payment, Product, Notification |
| `UserBehaviorTracked` | Gateway / Clients | Recommendation |

`ProductCreated` and `ProductUpdated` schema v4 carry integer `price`/`maxPrice` and
variant prices in VND minor units, plus ISO `currency: "VND"` at product and variant
level. VND has zero fractional minor units. Consumers must use the currency field
when mapping prices into order or payment amounts.

### 5.1. `ShopApproved` / `ShopSuspended` (PBL6-14)

Shop Service phát khi Admin duyệt / đình chỉ gian hàng (FR-SHOP-04). Record Java:
`com.vmarket.events.ShopApproved` / `ShopSuspended`. Phát **sau khi commit** nên không
có sự kiện "ma" cho thay đổi bị rollback; đổi lại là tối đa một lần (xem §7).

```json
{ "eventType": "ShopApproved", "payload": {
    "shopId": "01JBQ9YDX7K3M8N5P2R4T6V8W0",
    "ownerId": "01JBQ9YDX7K3M8N5P2R4T6V8W1",
    "shopName": "Tiệm Gốm Hội An",
    "approvedBy": "01JBQ9YDX7K3M8N5P2R4T6V8WA",
    "reinstated": false } }

{ "eventType": "ShopSuspended", "payload": {
    "shopId": "01JBQ9YDX7K3M8N5P2R4T6V8W0",
    "ownerId": "01JBQ9YDX7K3M8N5P2R4T6V8W1",
    "shopName": "Tiệm Gốm Hội An",
    "suspendedBy": "01JBQ9YDX7K3M8N5P2R4T6V8WA",
    "reason": "Bán hàng giả" } }
```

| Trường | Ý nghĩa |
|---|---|
| `ownerId` | userId chủ gian hàng — Auth cấp / giữ vai trò SELLER cho người này |
| `reinstated` | `false` = duyệt hồ sơ mới (hoặc hồ sơ gửi lại); `true` = gỡ đình chỉ. Product xử lý như nhau (hiện sản phẩm), Notification gửi nội dung khác nhau |
| `reason` | Lý do đình chỉ Admin nhập (luôn có) |

Thời điểm duyệt / đình chỉ = `timestamp` của envelope. Từ chối hồ sơ và gửi lại hồ sơ
**không** phát sự kiện (SRS §8.1 không định nghĩa).

## 6. Kiểm thử end-to-end

Luồng: **Product Catalog (Java)** phát → **AI Search (Python)** nhận.

1. Khởi động RabbitMQ: `docker compose up -d rabbitmq`.
2. Chạy product-service (`mvnw spring-boot:run`) và consumer cần kiểm tra (ai-search-service /
   recommendation-service: `uvicorn main:app`).
3. Kiểm tra queue đã được khai báo TRƯỚC khi phát event:
   `rabbitmqctl list_bindings source_name routing_key destination_name` — phải thấy
   `ai-search.events.v2` / `recommendation.events` bind với `ProductCreated`. Thiếu binding ⇒
   outbox sẽ park event (xem §7).
4. Đồng bộ một `ShopApproved`, sau đó tạo sản phẩm qua API Seller thật.
5. Quan sát `ProductCreated` schema v4 trong AI Search; event giữ nguyên `eventId`
   khi outbox phải gửi lại.

## 7. Lưu ý / hướng phát triển sau

- **Retry + DLQ** (NFR-REL-02): listener retry thêm 2 lần với exponential backoff,
  sau đó chuyển thông điệp vào `{queue}.dead` qua exchange `vmarket.events.dlx`.
- **Transactional outbox**: Product Catalog ghi domain data và outbox trong cùng
  Mongo transaction; worker atomic-claim từng message, chờ publisher confirm và
  kiểm tra returned message trước khi đánh dấu đã phát. Lỗi được retry với
  exponential backoff, tối đa `app.outbox.max-attempts` (mặc định 10) lần; vượt ngưỡng
  (hoặc park tạm thời vì unroutable) bị đánh dấu DEAD và NGỪNG retry để tránh retry vô
  hạn im lặng (vd chưa có consumer nào bind routing key). Event DEAD nằm lại collection
  `catalog_outbox` để re-drive thủ công. Metric cảnh báo (exposed qua
  `/actuator/metrics`; gauge được Micrometer đọc lại mỗi lần scrape):
  `vmarket.outbox.pending` (số event chờ), `vmarket.outbox.dead.total` (số event DEAD),
  `vmarket.outbox.retry` (số lần retry có lease), `vmarket.outbox.dead` (event vừa bị
  park/DEAD, tag `reason=no_route` khi broker từ chối route).
- **Idempotency**: consumer nên dựa vào `eventId` để tránh xử lý trùng (vd khi retry).
- **Phá phiên bản schema**: khi payload thay đổi, giữ tương thích ngược hoặc bump
  version và thống nhất với các service nhận trước khi deploy.

## Payment integration (PBL6-19)

- `PaymentFailed(orderId, reason)`: `RETRYABLE` keeps an order awaiting payment;
  `EXPIRED`/`CANCELLED` cancels the awaiting order and releases inventory.
- `CodCollected(orderId, buyerId, amount, reference)`: Delivery publishes only after
  assigned-shipper authorization and collection of the trusted order amount.
  Payment records collection idempotently by order/reference; it does not emit online
  `PaymentSucceeded` for COD or change the delivered order back to pending.
- Order and Payment SQL outboxes enable `app.events.confirmed-publication=true`,
  correlated publisher confirms and mandatory returns. Nack, timeout and missing route
  preserve the SQL row. Retries may carry a new envelope id: these consumers deduplicate
  by business order/payment state under database locks.

## 8. Contract Product Catalog bổ sung

- Product nhận `ReviewCreated(reviewId, productId, ratingAverage, ratingCount)`;
  payload mang aggregate mới nhất để xử lý lặp không cộng điểm hai lần.
- Product nhận `OrderStatusChanged`; trạng thái `CANCELLED` hoàn giữ kho, còn đơn
  COD chuyển sang `CONFIRMED`/`PREPARING` sẽ chốt kho. Thanh toán online tiếp tục
  chốt qua `PaymentSucceeded` theo FR-PROD-02.
- Product nhận `ReturnResolved(returnId, orderId, restock, items)` và chỉ nhập lại
  hàng khi `restock=true`; `returnId` là khóa idempotency.
- Shop Service bootstrap/reconcile projection qua endpoint nội bộ
  `POST /api/products/internal/shop-access/reconcile`, tối đa 500 shop mỗi trang.
  Snapshot cũ hơn `updatedAt` hiện có sẽ bị bỏ qua.
