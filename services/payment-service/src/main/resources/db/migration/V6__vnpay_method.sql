-- Doi cong thanh toan online tu PayOS sang VNPay: giu lai lich su giao dich cu.
UPDATE payment_transactions SET method='VNPAY' WHERE method='PAYOS';
