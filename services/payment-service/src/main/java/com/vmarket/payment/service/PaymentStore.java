package com.vmarket.payment.service;

import com.vmarket.events.*;
import com.vmarket.payment.client.OrderClient.OrderView;
import com.vmarket.payment.client.PayosClient;
import com.vmarket.payment.config.PaymentTimingProperties;
import com.vmarket.payment.dto.*;
import com.vmarket.payment.entity.*;
import com.vmarket.payment.event.Outbox;
import com.vmarket.payment.exception.ApiException;
import com.vmarket.payment.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PaymentStore {
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final Outbox outbox;
    private final Clock clock;
    private final PaymentOrderStateRepository states;
    private final PaymentOrderStateInitializer stateInitializer;
    private final PaymentTimingProperties timing;
    private final PlatformTransactionManager transactions;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactions);
    }

    @Transactional
    public Long reserve(String buyer, OrderView order) {
        var state = orderState(order.id());
        if (state.isCancelled())
            throw ApiException.conflict(
                    "ORDER_NOT_PAYABLE", "Đơn đã hủy, không thể tạo thanh toán");
        var existing = payments.lockByOrderId(order.id());
        if (existing.isPresent()) {
            if (!buyer.equals(existing.get().getBuyerId()))
                throw ApiException.notFound("PAYMENT_NOT_FOUND", "Không tìm thấy giao dịch");
            var payment = existing.get();
            if ("CANCELLED".equals(order.status())) {
                cancelled(order.id());
            } else if (Set.of("CREATED", "PENDING", "FAILED").contains(payment.getStatus())
                    && (!"WAITING_PAYMENT".equals(order.status()) || !order.stockReserved())) {
                throw ApiException.conflict(
                        "ORDER_NOT_PAYABLE",
                        "Đơn không còn chờ thanh toán hoặc chưa giữ đủ tồn kho");
            }
            return existing.get().getId();
        }
        if (order.totalAmount() == null || order.totalAmount().signum() <= 0)
            throw ApiException.badRequest("INVALID_AMOUNT", "Số tiền không hợp lệ");
        long amount;
        try {
            amount = order.totalAmount().longValueExact();
        } catch (ArithmeticException ex) {
            throw ApiException.badRequest("INVALID_AMOUNT", "PayOS yêu cầu số tiền VND nguyên");
        }
        String method = order.paymentMethod();
        if (!Set.of("PAYOS", "COD").contains(method == null ? "" : method))
            throw ApiException.conflict(
                    "INVALID_PAYMENT_METHOD", "Đơn không có phương thức thanh toán hợp lệ");
        if ("PAYOS".equals(method)
                && (!"WAITING_PAYMENT".equals(order.status())
                        || order.paymentExpiresAt() == null
                        || !clock.instant().isBefore(order.paymentExpiresAt())
                        || !order.stockReserved()))
            throw ApiException.conflict(
                    "ORDER_NOT_PAYABLE", "Đơn chưa giữ đủ tồn kho hoặc đã hết hạn thanh toán");
        if ("COD".equals(method)
                && !Set.of("PENDING", "PROCESSING", "SHIPPED").contains(order.status()))
            throw ApiException.conflict("ORDER_NOT_PAYABLE", "Đơn không còn nhận COD");
        var p = new PaymentTransaction();
        p.setOrderId(order.id());
        p.setBuyerId(buyer);
        p.setAmount(amount);
        p.setMethod(method);
        p.setStatus("COD".equals(method) ? "COD_PENDING" : "CREATED");
        p.setCreatedAt(clock.instant());
        p.setExpiresAt(order.paymentExpiresAt());
        if ("PAYOS".equals(method)) {
            p.setLinkExpiresAt(order.paymentExpiresAt().minus(timing.getLinkExpiryBuffer()));
            p.setSettlementExpiresAt(order.paymentExpiresAt().plus(timing.getSettlementGrace()));
            if (!clock.instant().isBefore(p.getLinkExpiresAt()))
                throw ApiException.conflict(
                        "ORDER_NOT_PAYABLE",
                        "Đã hết thời gian tạo link; đang chờ đối soát thanh toán");
        }
        return payments.saveAndFlush(p).getId();
    }

    private record LinkAttempt(
            Long id, String token, long amount, Instant expiry, PaymentResponse response) {}

    // Both database phases commit before/after HTTP; provider calls never hold the row lock.
    @Transactional(propagation = Propagation.NEVER)
    public PaymentResponse createLink(Long id, PayosClient client) {
        LinkAttempt attempt = tx().execute(ignored -> prepareLink(id));
        if (attempt.token() == null) return attempt.response();
        PayosClient.Link link;
        try {
            link = client.create(id, attempt.amount(), attempt.expiry());
        } catch (RuntimeException failure) {
            tx().executeWithoutResult(
                            ignored -> {
                                var p = lock(id);
                                if (attempt.token().equals(p.getLinkRequestToken())) {
                                    p.setLinkRequestToken(null);
                                    p.setLinkRequestExpiresAt(null);
                                    if ("CREATING".equals(p.getStatus()))
                                        p.setStatus(
                                                clock.instant().isBefore(p.getLinkExpiresAt())
                                                        ? "CREATED"
                                                        : "AWAITING_WEBHOOK");
                                }
                            });
            throw failure;
        }
        return tx().execute(ignored -> finishLink(attempt, link));
    }

    private LinkAttempt prepareLink(Long id) {
        var p = lock(id);
        if (!"PAYOS".equals(p.getMethod()))
            return new LinkAttempt(id, null, 0, null, PaymentResponse.from(p));
        if (Set.of("CREATED", "CREATING", "PENDING", "FAILED", "AWAITING_WEBHOOK")
                        .contains(p.getStatus())
                && !clock.instant().isBefore(p.getLinkExpiresAt())) {
            if (!clock.instant().isBefore(p.getSettlementExpiresAt())) expire(p);
            else {
                p.setStatus("AWAITING_WEBHOOK");
                p.setCheckoutUrl(null);
                p.setQrCode(null);
            }
            return new LinkAttempt(id, null, 0, null, PaymentResponse.from(p));
        }
        if ("FAILED".equals(p.getStatus()))
            p.setStatus(p.getPaymentLinkId() == null ? "CREATED" : "PENDING");
        boolean abandoned =
                "CREATING".equals(p.getStatus())
                        && !p.getLinkRequestExpiresAt().isAfter(clock.instant());
        if (!"CREATED".equals(p.getStatus()) && !abandoned)
            return new LinkAttempt(id, null, 0, null, PaymentResponse.from(p));
        String token = UUID.randomUUID().toString();
        p.setStatus("CREATING");
        p.setLinkRequestToken(token);
        p.setLinkCreationUncertain(true);
        p.setLinkRequestExpiresAt(clock.instant().plus(timing.getOperationLease()));
        return new LinkAttempt(id, token, p.getAmount(), p.getLinkExpiresAt(), null);
    }

    private PaymentResponse finishLink(LinkAttempt attempt, PayosClient.Link link) {
        var p = lock(attempt.id());
        if (!attempt.token().equals(p.getLinkRequestToken())) return PaymentResponse.from(p);
        p.setLinkRequestToken(null);
        p.setLinkRequestExpiresAt(null);
        p.setLinkCreationUncertain(false);
        if (p.getPaymentLinkId() != null && !p.getPaymentLinkId().equals(link.paymentLinkId())) {
            p.setReconciliationRequired(true);
            return PaymentResponse.from(p);
        }
        p.setPaymentLinkId(link.paymentLinkId());
        // A webhook/cancellation may commit while the HTTP request is in flight.
        if (p.getPaidAt() != null) return PaymentResponse.from(p);
        if (Set.of("CANCELLED", "EXPIRED").contains(p.getStatus())) {
            if (link.status() == PayosClient.Status.PENDING) scheduleCancellation(p);
            else if (link.status() == PayosClient.Status.PAID
                    || link.status() == PayosClient.Status.PROCESSING) {
                p.setCancelPending(false);
                p.setReconciliationRequired(true);
            }
            return PaymentResponse.from(p);
        }
        switch (link.status()) {
            case PENDING -> {
                boolean open = clock.instant().isBefore(p.getLinkExpiresAt());
                p.setCheckoutUrl(open ? link.checkoutUrl() : null);
                p.setQrCode(open ? link.qrCode() : null);
                p.setStatus(open ? "PENDING" : "AWAITING_WEBHOOK");
            }
            case CANCELLED -> {
                p.setCheckoutUrl(null);
                p.setQrCode(null);
                p.setStatus(link.status().name());
                outbox.add(
                        EventType.PAYMENT_FAILED,
                        new PaymentFailed(p.getOrderId(), link.status().name()));
            }
            case EXPIRED -> {
                p.setCheckoutUrl(null);
                p.setQrCode(null);
                if (clock.instant().isBefore(p.getSettlementExpiresAt())) {
                    p.setStatus("AWAITING_WEBHOOK");
                } else expire(p);
            }
            case PAID, PROCESSING -> {
                // Preserve this signed provider state durably. Only a verified success webhook
                // with a matching bank reference may emit PaymentSucceeded or request a refund.
                p.setCheckoutUrl(null);
                p.setQrCode(null);
                p.setStatus("RECONCILING");
                p.setReconciliationRequired(true);
            }
        }
        return PaymentResponse.from(p);
    }

    @Transactional
    public void webhook(Map<String, Object> data) {
        Long code = wholeNumber(data.get("orderCode"));
        var optional = payments.lock(code);
        // PayOS sends a signed sample during webhook registration; unknown references cannot mutate
        // a transaction.
        if (optional.isEmpty()) return;
        var p = optional.get();
        if (!"PAYOS".equals(p.getMethod()))
            throw ApiException.badRequest("INVALID_TRANSACTION", "Giao dịch không phải PayOS");
        if (wholeNumber(data.get("amount")) != p.getAmount() || !"VND".equals(data.get("currency")))
            throw ApiException.badRequest("PAYMENT_MISMATCH", "Số tiền hoặc tiền tệ không khớp");
        String link = Objects.toString(data.get("paymentLinkId"), "");
        String reference = Objects.toString(data.get("reference"), "");
        if (link.isBlank()
                || link.length() > 64
                || (p.getPaymentLinkId() != null && !p.getPaymentLinkId().equals(link))
                || reference.isBlank()
                || reference.length() > 128)
            throw ApiException.badRequest("PAYMENT_MISMATCH", "Mã giao dịch không khớp");
        // A verified webhook can recover a remote link created before a local crash/timeout.
        // The signed orderCode, amount and currency already match the persisted transaction.
        if (p.getPaymentLinkId() == null) p.setPaymentLinkId(link);
        // Only signed data.code controls success. Outer webhook.success/code are unsigned.
        if (p.getPaidAt() != null) return;
        if (!"00".equals(data.get("code"))) {
            if (Set.of("CREATED", "CREATING", "PENDING", "AWAITING_WEBHOOK")
                    .contains(p.getStatus())) {
                p.setStatus("FAILED");
                outbox.add(
                        EventType.PAYMENT_FAILED, new PaymentFailed(p.getOrderId(), "RETRYABLE"));
            }
            return;
        }
        Instant paidAt = transactionTime(data.get("transactionDateTime"));
        if (paidAt.isBefore(p.getCreatedAt().minusSeconds(30))
                || paidAt.isAfter(clock.instant().plusSeconds(30)))
            throw ApiException.badRequest(
                    "INVALID_TRANSACTION_TIME", "Thời gian giao dịch không hợp lệ");
        boolean closed = "EXPIRED".equals(p.getStatus()) || "CANCELLED".equals(p.getStatus());
        boolean late = closed || paidAt.isAfter(p.getExpiresAt());
        p.setReference(reference);
        p.setPaidAt(paidAt);
        p.setStatus("SUCCEEDED");
        p.setReconciliationRequired(false);
        p.setCancelPending(false);
        p.setCancelRequestToken(null);
        p.setLinkCreationUncertain(false);
        if (late) {
            refund(p, "Thanh toán đến sau khi đơn hết hạn/hủy");
            if (!closed)
                outbox.add(EventType.PAYMENT_FAILED, new PaymentFailed(p.getOrderId(), "EXPIRED"));
        } else outbox.add(EventType.PAYMENT_SUCCEEDED, new PaymentSucceeded(p.getOrderId()));
    }

    @Transactional
    public void expire(Long id) {
        var p = lock(id);
        if (Set.of("CREATED", "CREATING", "PENDING", "FAILED", "AWAITING_WEBHOOK")
                        .contains(p.getStatus())
                && !clock.instant().isBefore(p.getSettlementExpiresAt())) expire(p);
    }

    private void expire(PaymentTransaction p) {
        p.setStatus("EXPIRED");
        p.setCheckoutUrl(null);
        p.setQrCode(null);
        scheduleCancellation(p);
        outbox.add(EventType.PAYMENT_FAILED, new PaymentFailed(p.getOrderId(), "EXPIRED"));
    }

    @Transactional
    public void cancelled(String orderId) {
        orderState(orderId).setCancelled(true);
        var found = payments.lockByOrderId(orderId);
        if (found.isEmpty()) return;
        var p = found.get();
        if (p.getPaidAt() != null) refund(p, "Đơn đã thanh toán bị hủy");
        else if (!Set.of("CANCELLED", "EXPIRED").contains(p.getStatus())) {
            p.setStatus("CANCELLED");
            p.setCheckoutUrl(null);
            p.setQrCode(null);
            if ("PAYOS".equals(p.getMethod())) scheduleCancellation(p);
        }
    }

    private void scheduleCancellation(PaymentTransaction p) {
        p.setCancelPending(true);
        p.setCancelRequestToken(null);
        p.setNextCancelAttemptAt(clock.instant());
    }

    @Transactional(propagation = Propagation.NEVER)
    public void cancelRemote(Long id, PayosClient client) {
        String token =
                tx().execute(
                                ignored -> {
                                    var p = lock(id);
                                    if (!p.isCancelPending()
                                            || p.getNextCancelAttemptAt().isAfter(clock.instant()))
                                        return null;
                                    if (p.getCancelAttempts() >= timing.getMaxCancelAttempts()) {
                                        p.setCancelPending(false);
                                        p.setReconciliationRequired(true);
                                        p.setCancellationError("CANCELLATION_RETRY_LIMIT");
                                        return null;
                                    }
                                    p.setCancelAttempts(p.getCancelAttempts() + 1);
                                    String lease = UUID.randomUUID().toString();
                                    p.setCancelRequestToken(lease);
                                    p.setNextCancelAttemptAt(
                                            clock.instant().plus(timing.getOperationLease()));
                                    return lease;
                                });
        if (token == null) return;
        String error = null;
        try {
            client.cancel(id);
        } catch (ApiException failure) {
            error = failure.getCode();
        } catch (RuntimeException failure) {
            error = "PAYOS_UNAVAILABLE";
        }
        final String result = error;
        tx().executeWithoutResult(
                        ignored -> {
                            var p = lock(id);
                            if (!token.equals(p.getCancelRequestToken()) || p.getPaidAt() != null)
                                return;
                            p.setCancelRequestToken(null);
                            p.setCancellationError(result);
                            int attempts = p.getCancelAttempts();
                            if (result == null || "PAYOS_PAYMENT_NOT_FOUND".equals(result)) {
                                p.setCancelPending(false);
                                if (result != null
                                        && (p.isLinkCreationUncertain()
                                                || p.getPaymentLinkId() != null))
                                    p.setReconciliationRequired(true);
                            } else if ("PAYOS_PAID_RECONCILIATION_REQUIRED".equals(result)
                                    || attempts >= timing.getMaxCancelAttempts()) {
                                p.setCancelPending(false);
                                p.setReconciliationRequired(true);
                            } else {
                                p.setNextCancelAttemptAt(
                                        clock.instant()
                                                .plusSeconds(
                                                        Math.min(
                                                                300,
                                                                5L << Math.min(attempts - 1, 6))));
                            }
                        });
    }

    @Transactional
    public void codCollected(CodCollected event) {
        var state = orderState(event.orderId());
        if (event.amount() <= 0
                || event.reference() == null
                || event.reference().isBlank()
                || event.reference().length() > 128)
            throw ApiException.badRequest("INVALID_COD", "Thông tin thu COD không hợp lệ");
        var p =
                payments.lockByOrderId(event.orderId())
                        .orElseGet(
                                () -> {
                                    var created = new PaymentTransaction();
                                    created.setOrderId(event.orderId());
                                    created.setBuyerId(event.buyerId());
                                    created.setAmount(event.amount());
                                    created.setMethod("COD");
                                    created.setStatus("COD_PENDING");
                                    created.setCreatedAt(clock.instant());
                                    return payments.saveAndFlush(created);
                                });
        if (!"COD".equals(p.getMethod())
                || p.getAmount() != event.amount()
                || !p.getBuyerId().equals(event.buyerId()))
            throw ApiException.conflict("COD_MISMATCH", "Số tiền hoặc người mua không khớp");
        if (p.getPaidAt() != null) {
            if (!event.reference().equals(p.getReference()))
                throw ApiException.conflict(
                        "COD_ALREADY_COLLECTED", "COD đã được ghi nhận với mã khác");
            return;
        }
        boolean cancelled = state.isCancelled() || "CANCELLED".equals(p.getStatus());
        p.setReference(event.reference());
        p.setPaidAt(clock.instant());
        p.setStatus("SUCCEEDED");
        if (cancelled) refund(p, "COD đến sau khi đơn hủy");
        // Delivery owns DELIVERED; a PayOS-style PaymentSucceeded must not revive a COD order.
    }

    @Transactional(readOnly = true)
    public PaymentResponse own(String buyer, Long id) {
        var p =
                payments.findById(id)
                        .filter(x -> buyer.equals(x.getBuyerId()))
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "PAYMENT_NOT_FOUND", "Không tìm thấy giao dịch"));
        return PaymentResponse.from(p);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(String buyer, int page, int size) {
        return payments
                .findByBuyerIdOrderByCreatedAtDesc(buyer, PageRequest.of(page, size))
                .stream()
                .map(PaymentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> reconciliations(int page, int size) {
        return payments
                .findByReconciliationRequiredTrueOrderByCreatedAtAsc(PageRequest.of(page, size))
                .stream()
                .map(PaymentResponse::from)
                .toList();
    }

    @Transactional
    public RefundRequest requestRefund(Long id, String reason) {
        var p = lock(id);
        if (p.getPaidAt() == null)
            throw ApiException.conflict("PAYMENT_NOT_PAID", "Giao dịch chưa thanh toán");
        return refund(p, reason);
    }

    private RefundRequest refund(PaymentTransaction p, String reason) {
        return refunds.findByPaymentId(p.getId())
                .orElseGet(
                        () -> {
                            var r = new RefundRequest();
                            r.setPaymentId(p.getId());
                            r.setAmount(p.getAmount());
                            r.setReason(reason);
                            r.setRequestedAt(clock.instant());
                            return refunds.save(r);
                        });
    }

    @Transactional
    public RefundRequest confirm(String id, String admin, String reference) {
        var r =
                refunds.lock(id)
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "REFUND_NOT_FOUND",
                                                "Không tìm thấy yêu cầu hoàn tiền"));
        if ("COMPLETED".equals(r.getStatus())) {
            if (!reference.equals(r.getTransferReference()))
                throw ApiException.conflict(
                        "REFUND_ALREADY_COMPLETED", "Yêu cầu đã hoàn tiền với mã khác");
            return r;
        }
        r.setStatus("COMPLETED");
        r.setCompletedAt(clock.instant());
        r.setConfirmedBy(admin);
        r.setTransferReference(reference);
        return r;
    }

    @Transactional(readOnly = true)
    public List<RefundRequest> pendingRefunds(int page, int size) {
        return refunds.findByStatusOrderByRequestedAtAsc("PENDING", PageRequest.of(page, size));
    }

    private PaymentTransaction lock(Long id) {
        return payments.lock(id)
                .orElseThrow(
                        () ->
                                ApiException.notFound(
                                        "PAYMENT_NOT_FOUND", "Không tìm thấy giao dịch"));
    }

    private PaymentOrderState orderState(String orderId) {
        stateInitializer.ensure(orderId);
        return states.lock(orderId)
                .orElseThrow(() -> new IllegalStateException("Missing payment order state"));
    }

    private Long wholeNumber(Object value) {
        try {
            return new java.math.BigDecimal(String.valueOf(value)).longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            throw ApiException.badRequest(
                    "INVALID_WEBHOOK", "Số tiền hoặc mã giao dịch không hợp lệ");
        }
    }

    private Instant transactionTime(Object value) {
        String text = Objects.toString(value, "");
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException notInstant) {
            try {
                return LocalDateTime.parse(
                                text,
                                DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                                        .withResolverStyle(ResolverStyle.STRICT))
                        .atZone(timing.getTransactionZone())
                        .toInstant();
            } catch (java.time.format.DateTimeParseException invalid) {
                throw ApiException.badRequest(
                        "INVALID_TRANSACTION_TIME",
                        "Webhook thiếu hoặc sai thời gian giao dịch ngân hàng");
            }
        }
    }
}
