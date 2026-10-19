# Payment Service — PBL6-19

FR-PAY-01..05: PayOS link/QR, signed webhook, COD records, transaction history,
15-minute payment deadline and manually confirmed refunds. Port 8087, PostgreSQL.

## Configuration

Copy `.env.example` into the ignored `.env`, then load that file through the IDE/container.
Spring does not automatically load shell variables from a `.env` file.
JWT verification uses the same `AUTH_JWT_SECRET` as auth-service. `ORDER_SERVICE_URL`
is the private address of order-service; buyer JWTs are forwarded to its ownership-checked API.

PayOS is disabled until `PAYOS_ENABLED=true` and the three `PAYOS_*` credentials are set.
Do not commit credentials. Configure fixed return/cancel URLs on the server.

Timing is configured with Spring properties (durations such as `15m`, `2m`, `30s`):

| Service/property | Default | Meaning |
|---|---|---|
| Order: `app.payment.ttl` | `15m` | Checkout deadline, calculated with the injected Clock |
| Order: `app.payment.fallback-delay` | `15m` | Additional delay before the Order safety job cancels a payment with no terminal event |
| Payment: `app.payment.link-expiry-buffer` | `1m` | PayOS link closes before the Order deadline |
| Payment: `app.payment.settlement-grace` | `2m` | Wait for a delayed webhook before publishing EXPIRED |
| Payment: `app.payment.transaction-zone` | `Asia/Ho_Chi_Minh` | Interpret the signed bank timestamp when it has no offset |
| Payment: `app.payment.operation-lease` | `30s` | Persisted claim for a provider request; minimum 15s |
| Payment: `app.payment.max-cancel-attempts` | `8` | Stop automatic cancellation and queue reconciliation after this many attempts |

Keep Order's fallback delay longer than Payment's settlement grace. Payment persists link and
settlement deadlines on reservation, so changing configuration does not move existing deadlines.

**PayOS has no separate sandbox.** The ticket's sandbox wording does not match the
[official testing documentation](https://payos.vn/docs/moi-truong-test/).
Local tests use a mocked provider, not a live bank transaction. Real PayOS acceptance
still requires the merchant credentials, public HTTPS webhook and a human-authorized
small real payment. No real transaction was performed for this PR.

## API and flow

All APIs require an auth-service bearer JWT except health, docs and the signed webhook.

1. `POST /api/orders` with `addressId`, optional `note`, `paymentMethod: "PAYOS"`.
   Omitting the method preserves COD behavior. PayOS orders wait in `WAITING_PAYMENT`.
2. Order emits `OrderPlaced` via its SQL outbox. Product holds inventory and returns
   `StockReserved`; stock failure cancels the order. Payment is blocked before reservation.
   This step is asynchronous. Frontend polls `GET /api/orders/{id}` until `stockReserved=true`
   while the order is still `WAITING_PAYMENT`, for example every second for up to 30 seconds.
   An immediate payment request can return `409 ORDER_NOT_PAYABLE`; refresh the order before
   retrying and stop on cancellation or the payment/link deadline. Do not create a second order.
3. Buyer calls `POST /api/payments` with only `{ "orderId": "<ULID>" }`.
   Amount and deadline come from Order Service, never from client input.
   Response contains `id`, `checkoutUrl`, `qrCode`, `qrAvailable`, amount, `expiresAt` (Order
   deadline), `linkExpiresAt` (actual PayOS link deadline), and `settlementExpiresAt` (end of
   the webhook grace). If recovery has no QR, `qrAvailable=false`; clients open `checkoutUrl`.
   A concurrent request can return `CREATING` with no URL: poll `GET /api/payments/{id}`.
   At `AWAITING_WEBHOOK`, stop showing the link and wait for the terminal payment result.
4. PayOS calls `POST /api/payments/webhooks/payos`. Gateway allows only this exact path.
   Payment verifies HMAC-SHA256 over sorted `data`, then checks order code, amount,
   currency, provider link id and bank reference. Unsigned outer fields do not control success.
5. One SQL transaction records success plus the `PaymentSucceeded` outbox entry.
   Order consumes it and moves to `PENDING` (waiting seller confirmation).
6. Payment is authoritative for normal expiry: after Order's deadline plus the settlement grace,
   it publishes `PaymentFailed(EXPIRED)` once. Order consumes that event and releases stock.
   Order's job is a delayed safety net: by default it only cancels at deadline + 15 minutes,
   including when the buyer never created a Payment record. A delayed webhook within grace
   is judged by its signed bank transaction time, rather than the receipt time. A transaction
   actually after the Order deadline queues a refund and fails the order. A payment already
   expired/cancelled is not revived by a later webhook; it queues a refund.

The signed `transactionDateTime` is parsed as an ISO instant or PayOS's documented
`yyyy-MM-dd HH:mm:ss` in the configured merchant zone. Missing/malformed or impossible times
are rejected before success is committed. The payload contract is documented in the
[official webhook API](https://payos.vn/docs/api/) and
[signature examples](https://payos.vn/docs/tich-hop-webhook/kiem-tra-du-lieu-voi-signature/).

Provider operations use a persisted lease: claim under a row lock, commit, call PayOS, then
lock and apply only that claim's result. Creation and cancellation do not hold a database
transaction during HTTP. A webhook or cancellation can commit while creation is in flight;
the completion cannot reset its terminal state. Late creation on a cancelled order schedules
cancellation again, and an older cancellation response cannot erase that newer schedule.

Payment creation checks the locked cancellation tombstone even when the Order API snapshot
was read before a cancellation event. A canceled order cannot create its first PayOS/COD record.
Duplicate-code recovery verifies the signed provider status as well as code and amount.
Only `PENDING` returns a checkout URL/QR. `CANCELLED` persists a terminal payment
and emits `PaymentFailed` once. Provider `EXPIRED` waits in `AWAITING_WEBHOOK` until
the settlement grace ends, allowing a delayed webhook for a timely bank transaction.
Signed `PAID`/`PROCESSING` enter `RECONCILING` with
`reconciliationRequired=true`, no payment link and no success event until a verified webhook
supplies the bank reference. Retrying creation does not offer another payment attempt.

`GET /api/payments?page=0&size=20` and `GET /api/payments/{id}` show only the caller's history.
Admin-only APIs:

- `GET /api/payments/admin/refunds`: pending refund queue, paginated.
- `GET /api/payments/admin/reconciliations`: persistent queue of payments awaiting
  the bank webhook after signed provider `PAID`/`PROCESSING`, including late canceled/expired
  payments. PAID/PROCESSING cancels stop retrying as soon as they enter reconciliation.
  A verified webhook
  clears the flag and records success, or creates a refund once if the order expired/canceled.
- `POST /api/payments/admin/{paymentId}/refunds` with `reason`: record a manual refund
  for an already paid transaction. Admin must verify cancellation/accepted return evidence.
- `PUT /api/payments/admin/refunds/{refundId}/confirm` with `transferReference`:
  mark a manual bank refund completed, recording admin id and confirmation timestamp.

COD creates `COD_PENDING` and does not call PayOS. A duplicate-safe `CodCollected` consumer records the amount, buyer, collection reference and paid timestamp, even if the buyer never created a payment record. Delivery verifies the shipper and amount before publishing. COD collection is completed by Delivery
Service in PBL6-20. PostgreSQL locks and unique constraints protect concurrent webhook,
payment creation and refund confirmation. Provider order code persists across timeouts.

## Validation and operational limits

From `services/`: `./mvnw -B --no-transfer-progress -pl payment-service,order-service,product-service,api-gateway -am verify`.
Payment tests run the real Flyway migration and Hibernate schema validation against H2.
They cover JWT ownership/RBAC, signature tampering, the public PayOS HMAC vector,
duplicate webhook/create, COD, expiry, late payment/cancellation refunds, retries and outbox retention.

Order and Payment enable confirmed publication in shared-events with correlated broker ACKs
and mandatory returns. SQL outbox rows are removed only after ACK and no return; nack,
timeout and unroutable publication keep rows for retry. Consumers remain idempotent because
a crash after publication but before SQL commit can redeliver. Queue bindings must exist
before publishing. This is at-least-once delivery, not an exactly-once transport.
Inspect `<service>.events.dead` for messages exhausted by the shared consumer retry policy.
Provider cancellation uses ordered due-time batches and exponential backoff (up to 5 minutes)
with at most eight attempts. PAID/PROCESSING or retry exhaustion sets
`reconciliationRequired=true` and clears `cancelPending`; no endless provider loop remains.
An authenticated GET returning HTTP 404 stops cancellation with `PAYOS_PAYMENT_NOT_FOUND`;
if creation was attempted/uncertain or a link id was previously known, keep that result in the
reconciliation queue. Unknown business error codes still retry within the limit; they are not
assumed to mean "not found". Creation diagnostics preserve the original provider code and a
sanitized description even when recovery fails; credentials and raw provider bodies are not logged.

Both Order and Payment outboxes claim one due row with `FOR UPDATE SKIP LOCKED` and commit
each ACK/delete separately, up to 50 rows per tick. Failure backoff starts at 5s and caps at 5m;
after 10 failures the row becomes DEAD with payload retained. Later events of that same order
wait, while other orders continue. Inspect `state='DEAD'` before replaying a specific repaired
row by resetting its state to PENDING, attempts to 0 and next_attempt_at to CURRENT_TIMESTAMP.
One failed payment is caught independently by the expiry job and does not stop the batch.

COD and PayOS references are unique within their own payment method, so an unrelated COD
collection cannot collide with a bank's reference. Webhook paymentLinkId is limited to 64
characters to match the schema. Product acknowledges a late PaymentSucceeded after a
cancellation/release without reconfirming stock or sending a predictable conflict to the DLQ.

COD cancellation tombstones
protect both event arrival orders and concurrent first inserts. Events may be redelivered
with a different envelope id; these consumers deduplicate by business order/payment state.
Live bank/PayOS acceptance and full deployed multi-service verification are separate checks;
local provider mocks do not establish them.

Review regression coverage includes an isolated PostgreSQL container: concurrent requests
with a pool reduced to one connection after Flyway startup, scoped reference uniqueness,
and per-row outbox commits. H2 also covers migration of pending payloads/deadlines, bounded
cancellation, webhook/create/cancel races and the configurable Order clock/deadline.
Local verification on 2026-10-19 passed 223 tests: Payment 59, Order 24, Product 87,
Gateway 39 and shared-events 14, with no failures or skips. Product's RabbitMQ/MongoDB
and Payment's PostgreSQL integration tests ran in isolated Docker containers.
Payment migrations are V2/V3. Order reuses the exact V4–V6 migrations from the dependent
PBL6-20 branch for the unified outbox and aggregate backfill. Keeping that migration history
identical supports upgrades from either ticket branch without version collisions or skipped
schema changes. V4's delivery column is inert until PBL6-20 supplies its consumers.

Order/Payment Dockerfiles install the parent POM and internal shared-events library before
resolving service dependencies. This matches the existing Shop/Product image build pattern.
The change is limited to these two build recipes; Compose is unchanged. Maven dependency
resolution and package commands were validated locally; actual image build and smoke checks
are verified separately by GitHub CI.
