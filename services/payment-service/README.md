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
3. Buyer calls `POST /api/payments` with only `{ "orderId": "<ULID>" }`.
   Amount and deadline come from Order Service, never from client input.
   Response contains `id`, `checkoutUrl`, `qrCode`, `qrAvailable`, amount and deadline. If duplicate-code recovery only returns the checkout URL (PayOS GET has no QR field), `qrCode` is null and `qrAvailable=false`; clients must open `checkoutUrl`.
4. PayOS calls `POST /api/payments/webhooks/payos`. Gateway allows only this exact path.
   Payment verifies HMAC-SHA256 over sorted `data`, then checks order code, amount,
   currency, provider link id and bank reference. Unsigned outer fields do not control success.
5. One SQL transaction records success plus the `PaymentSucceeded` outbox entry.
   Order consumes it and moves to `PENDING` (waiting seller confirmation).
6. Expiry works even if the buyer never calls Payment Service: Order cancels outstanding
   PayOS orders after 15 minutes and emits `OrderStatusChanged(CANCELLED)` to release stock.
   Pending provider cancellation retries separately. Late payments create a pending refund;
   they do not revive canceled orders. Retryable failures can reuse the same link before expiry.

`GET /api/payments?page=0&size=20` and `GET /api/payments/{id}` show only the caller's history.
Admin-only APIs:

- `GET /api/payments/admin/refunds`: pending refund queue, paginated.
- `GET /api/payments/admin/reconciliations`: persistent queue of canceled/expired
  payments for which PayOS reports signed `PAID` but the bank webhook is missing.
  These remain flagged `reconciliationRequired=true` with scheduled provider retries;
  a verified webhook records the bank reference/amount and creates a refund once.
- `POST /api/payments/admin/{paymentId}/refunds` with `reason`: record a manual refund
  for an already paid transaction. Admin must verify cancellation/accepted return evidence.
- `PUT /api/payments/admin/refunds/{refundId}/confirm` with `transferReference`:
  mark a manual bank refund completed, recording admin id and confirmation timestamp.

COD creates `COD_PENDING` and does not call PayOS. A duplicate-safe `CodCollected` consumer records the amount, buyer, collection reference and paid timestamp, even if the buyer never created a payment record. Delivery verifies the shipper and amount before publishing. COD collection is completed by Delivery
Service in PBL6-20. PostgreSQL locks and unique constraints protect concurrent webhook,
payment creation and refund confirmation. Provider order code persists across timeouts.

## Validation and operational limits

From `services/`: `./mvnw -B --no-transfer-progress -pl payment-service,order-service,api-gateway -am test`.
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
so unresolved links do not block subsequent cancellation requests. COD cancellation tombstones
protect both event arrival orders and concurrent first inserts. Events may be redelivered
with a different envelope id; these consumers deduplicate by business order/payment state.
Live bank/PayOS acceptance, PostgreSQL concurrency and full deployed multi-service verification
are separate checks; local mocked tests do not establish them.

Order/Payment Dockerfiles install the parent POM and internal shared-events library before
resolving service dependencies. This matches the existing Shop/Product image build pattern.
The change is limited to these two build recipes; Compose is unchanged. Maven dependency
resolution and package commands were validated locally; actual image build and smoke checks
are verified separately by GitHub CI.
