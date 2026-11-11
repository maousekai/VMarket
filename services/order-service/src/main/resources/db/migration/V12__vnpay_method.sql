-- Doi cong thanh toan online tu PayOS sang VNPay: cap nhat don cu de khop ma phuong thuc moi.
UPDATE orders SET payment_method='VNPAY' WHERE payment_method='PAYOS';
