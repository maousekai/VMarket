package com.vmarket.payment.service;
import com.vmarket.payment.client.*;
import com.vmarket.payment.config.PayosProperties;
import com.vmarket.payment.dto.PaymentResponse;
import com.vmarket.payment.dto.PaymentRequests.Webhook;
import com.vmarket.payment.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class PaymentService {
    private final OrderClient orders;
    private final PaymentStore store;
    private final PayosClient payos;
    private final PayosSignature signature;
    private final PayosProperties properties;
    public PaymentResponse create(String buyer, String token, String orderId) {
        var order = orders.getOwn(token, orderId);
        if (!orderId.equals(order.id())) throw ApiException.conflict("ORDER_MISMATCH", "Đơn hàng không khớp");
        return store.createLink(store.reserve(buyer, order), payos);
    }
    public void webhook(Webhook webhook) {
        if (!signature.verify(webhook.data(), webhook.signature(), properties.getChecksumKey())) throw ApiException.badRequest("INVALID_SIGNATURE", "Chữ ký PayOS không hợp lệ");
        store.webhook(webhook.data());
    }
}
