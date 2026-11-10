package com.vmarket.payment.service;

import com.vmarket.payment.client.*;
import com.vmarket.payment.config.PayosProperties;
import com.vmarket.payment.dto.PaymentRequests.Webhook;
import com.vmarket.payment.dto.PaymentResponse;
import com.vmarket.payment.exception.ApiException;

import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentService {
    private final OrderClient orders;
    private final PaymentStore store;
    private final PayosClient payos;
    private final PayosSignature signature;
    private final PayosProperties properties;

    public PaymentResponse create(String buyer, String token, String orderId) {
        var order = orders.getOwn(token, orderId);
        if (!orderId.equals(order.id()))
            throw ApiException.conflict("ORDER_MISMATCH", "Đơn hàng không khớp");
        return store.createLink(store.reserve(buyer, order), payos);
    }

    /**
     * Hỏi thẳng PayOS trạng thái giao dịch khi người mua quay lại trang VMarket (môi trường local
     * không nhận được webhook). Trạng thái đã ký PAID đi qua đúng luồng xử lý của webhook nên kết
     * quả giống hệt khi webhook tới; webhook đến sau sẽ không làm gì thêm.
     */
    public PaymentResponse sync(String buyer, Long id) {
        PaymentResponse current = store.own(buyer, id);
        if (!"PAYOS".equals(current.method()) || current.paidAt() != null) return current;
        PayosClient.Info info = payos.info(id);
        if (info.status() == PayosClient.Status.PAID && !info.reference().isBlank()) {
            Map<String, Object> data = new HashMap<>();
            data.put("orderCode", id);
            data.put("amount", info.amount());
            data.put("currency", "VND");
            data.put("paymentLinkId", info.paymentLinkId());
            data.put("reference", info.reference());
            data.put("transactionDateTime", info.transactionDateTime());
            data.put("code", "00");
            store.webhook(data);
        }
        return store.own(buyer, id);
    }

    public void webhook(Webhook webhook) {
        if (!signature.verify(webhook.data(), webhook.signature(), properties.getChecksumKey()))
            throw ApiException.badRequest("INVALID_SIGNATURE", "Chữ ký PayOS không hợp lệ");
        store.webhook(webhook.data());
    }
}
