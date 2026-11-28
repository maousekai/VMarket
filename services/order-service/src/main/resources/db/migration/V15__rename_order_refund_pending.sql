-- V14 left previously cancelled COD and VNPAY orders UNPAID because their payment history
-- cannot be inferred from the order status. Keep those rows as backfilled.
-- The order service used REFUNDED when it queued cancellation, before payment confirmed a refund.
UPDATE orders SET payment_status = 'REFUND_PENDING' WHERE payment_status = 'REFUNDED';
