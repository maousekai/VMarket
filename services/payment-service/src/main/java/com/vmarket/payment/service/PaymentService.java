package com.vmarket.payment.service;

import com.vmarket.payment.client.*;
import com.vmarket.payment.config.VnpayProperties;
import com.vmarket.payment.dto.PaymentResponse;
import com.vmarket.payment.exception.ApiException;
import com.vmarket.payment.service.PaymentStore.Outcome;
import com.vmarket.payment.service.PaymentStore.VnpayResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final OrderClient orders;
    private final PaymentStore store;
    private final PaymentGatewayClient gateway;
    private final VnpaySignature signature;
    private final VnpayProperties properties;

    public PaymentResponse create(String buyer, String token, String orderId) {
        var order = orders.getOwn(token, orderId);
        if (!orderId.equals(order.id()))
            throw ApiException.conflict("ORDER_MISMATCH", "Đơn hàng không khớp");
        return store.createLink(store.reserve(buyer, order), gateway);
    }

    private boolean validSignature(Map<String, String> params) {
        return signature.verify(
                params, params.get("vnp_SecureHash"), properties.getHashSecret());
    }

    /** IPN của VNPay: luôn trả HTTP 200, kết quả nằm trong RspCode. */
    public Map<String, String> ipn(Map<String, String> params) {
        if (!validSignature(params)) return rsp("97", "Invalid signature");
        try {
            return switch (store.process(VnpayResult.from(params))) {
                case NOT_FOUND -> rsp("01", "Order not found");
                case INVALID_AMOUNT -> rsp("04", "Invalid amount");
                case ALREADY_CONFIRMED -> rsp("02", "Order already confirmed");
                case PROCESSED -> rsp("00", "Confirm Success");
            };
        } catch (ApiException rejected) {
            log.warn("VNPay IPN rejected: {}", rejected.getCode());
            return rsp("99", "Unknown error");
        } catch (RuntimeException failure) {
            log.error("VNPay IPN failed", failure);
            return rsp("99", "Unknown error");
        }
    }

    /**
     * Return URL (dev local không nhận được IPN): xác thực chữ ký rồi chạy cùng xử lý idempotent
     * như IPN.
     */
    public PaymentResponse vnpayReturn(String buyer, Map<String, String> params) {
        if (!validSignature(params))
            throw ApiException.badRequest("INVALID_SIGNATURE", "Chữ ký VNPay không hợp lệ");
        var result = VnpayResult.from(params);
        Long id = PaymentStore.paymentId(result.txnRef());
        if (id == null) throw ApiException.notFound("PAYMENT_NOT_FOUND", "Không tìm thấy giao dịch");
        store.own(buyer, id);
        Outcome outcome = store.process(result);
        if (outcome == Outcome.NOT_FOUND)
            throw ApiException.notFound("PAYMENT_NOT_FOUND", "Không tìm thấy giao dịch");
        if (outcome == Outcome.INVALID_AMOUNT)
            throw ApiException.badRequest("PAYMENT_MISMATCH", "Số tiền không khớp");
        return store.own(buyer, id);
    }

    private static Map<String, String> rsp(String code, String message) {
        return Map.of("RspCode", code, "Message", message);
    }
}
