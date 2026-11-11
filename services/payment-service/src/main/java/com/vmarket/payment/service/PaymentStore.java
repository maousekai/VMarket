package com.vmarket.payment.service;

import com.vmarket.events.*;
import com.vmarket.payment.client.OrderClient.OrderView;
import com.vmarket.payment.client.PaymentGatewayClient;
import com.vmarket.payment.config.PaymentTimingProperties;
import com.vmarket.payment.dto.*;
import com.vmarket.payment.entity.*;
import com.vmarket.payment.event.Outbox;
import com.vmarket.payment.exception.ApiException;
import com.vmarket.payment.repository.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentStore {
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PendingReturnRefundRepository pendingRefunds;
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
            throw ApiException.badRequest("INVALID_AMOUNT", "VNPay yêu cầu số tiền VND nguyên");
        }
        String method = order.paymentMethod();
        if (!Set.of("VNPAY", "COD").contains(method == null ? "" : method))
            throw ApiException.conflict(
                    "INVALID_PAYMENT_METHOD", "Đơn không có phương thức thanh toán hợp lệ");
        if ("VNPAY".equals(method)
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
        if ("VNPAY".equals(method)) {
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
            Long id,
            String token,
            String txnRef,
            long amount,
            Instant expiry,
            PaymentResponse response) {}

    /**
     * Mã giao dịch gửi sang VNPay: ổn định cho một payment, nhưng thêm thời điểm tạo để không trùng
     * khi dev reset DB (id chạy lại từ đầu) trên cùng một merchant sandbox.
     */
    static String txnRef(PaymentTransaction p) {
        return p.getId() + "T" + p.getCreatedAt().getEpochSecond();
    }

    // Both database phases commit before/after the provider call; it never holds the row lock.
    @Transactional(propagation = Propagation.NEVER)
    public PaymentResponse createLink(Long id, PaymentGatewayClient client) {
        LinkAttempt attempt = tx().execute(ignored -> prepareLink(id));
        if (attempt.token() == null) return attempt.response();
        PaymentGatewayClient.Link link;
        try {
            link = client.create(attempt.txnRef(), attempt.amount(), attempt.expiry());
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
        if (!"VNPAY".equals(p.getMethod()))
            return new LinkAttempt(id, null, null, 0, null, PaymentResponse.from(p));
        if (Set.of("CREATED", "CREATING", "PENDING", "FAILED", "AWAITING_WEBHOOK")
                        .contains(p.getStatus())
                && !clock.instant().isBefore(p.getLinkExpiresAt())) {
            if (!clock.instant().isBefore(p.getSettlementExpiresAt())) expire(p);
            else {
                p.setStatus("AWAITING_WEBHOOK");
                p.setCheckoutUrl(null);
                p.setQrCode(null);
            }
            return new LinkAttempt(id, null, null, 0, null, PaymentResponse.from(p));
        }
        if ("FAILED".equals(p.getStatus()))
            p.setStatus(p.getPaymentLinkId() == null ? "CREATED" : "PENDING");
        boolean abandoned =
                "CREATING".equals(p.getStatus())
                        && !p.getLinkRequestExpiresAt().isAfter(clock.instant());
        if (!"CREATED".equals(p.getStatus()) && !abandoned)
            return new LinkAttempt(id, null, null, 0, null, PaymentResponse.from(p));
        String token = UUID.randomUUID().toString();
        p.setStatus("CREATING");
        p.setLinkRequestToken(token);
        p.setLinkCreationUncertain(true);
        p.setLinkRequestExpiresAt(clock.instant().plus(timing.getOperationLease()));
        return new LinkAttempt(
                id, token, txnRef(p), p.getAmount(), p.getLinkExpiresAt(), null);
    }

    private PaymentResponse finishLink(LinkAttempt attempt, PaymentGatewayClient.Link link) {
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
            if (link.status() == PaymentGatewayClient.Status.PENDING) scheduleCancellation(p);
            else if (link.status() == PaymentGatewayClient.Status.PAID
                    || link.status() == PaymentGatewayClient.Status.PROCESSING) {
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
                // Preserve this provider state durably. Only a verified success callback
                // with a matching bank reference may emit PaymentSucceeded or request a refund.
                p.setCheckoutUrl(null);
                p.setQrCode(null);
                p.setStatus("RECONCILING");
                p.setReconciliationRequired(true);
            }
        }
        return PaymentResponse.from(p);
    }

    /** Kết quả VNPay đã được xác minh chữ ký (IPN hoặc return URL), chưa xử lý nghiệp vụ. */
    public record VnpayResult(
            String txnRef,
            String amount,
            String responseCode,
            String transactionStatus,
            String transactionNo,
            String payDate) {
        public static VnpayResult from(Map<String, String> params) {
            return new VnpayResult(
                    params.getOrDefault("vnp_TxnRef", ""),
                    params.getOrDefault("vnp_Amount", ""),
                    params.getOrDefault("vnp_ResponseCode", ""),
                    params.getOrDefault("vnp_TransactionStatus", ""),
                    params.getOrDefault("vnp_TransactionNo", ""),
                    params.getOrDefault("vnp_PayDate", ""));
        }
    }

    public enum Outcome {
        PROCESSED,
        ALREADY_CONFIRMED,
        NOT_FOUND,
        INVALID_AMOUNT
    }

    /** Lấy payment id từ vnp_TxnRef dạng {@code <id>T<epoch>}; null nếu sai định dạng. */
    public static Long paymentId(String txnRef) {
        int t = txnRef == null ? -1 : txnRef.indexOf('T');
        if (t <= 0 || !txnRef.substring(0, t).matches("[0-9]{1,18}")) return null;
        return Long.valueOf(txnRef.substring(0, t));
    }

    @Transactional
    public Outcome process(VnpayResult result) {
        Long id = paymentId(result.txnRef());
        if (id == null) return Outcome.NOT_FOUND;
        var optional = payments.lock(id);
        if (optional.isEmpty()) return Outcome.NOT_FOUND;
        var p = optional.get();
        if (!"VNPAY".equals(p.getMethod())) return Outcome.NOT_FOUND;
        String expected = p.getPaymentLinkId() != null ? p.getPaymentLinkId() : txnRef(p);
        if (!expected.equals(result.txnRef())) return Outcome.NOT_FOUND;
        long amount;
        try {
            amount = Long.parseLong(result.amount());
        } catch (NumberFormatException ex) {
            return Outcome.INVALID_AMOUNT;
        }
        if (amount % 100 != 0 || amount / 100 != p.getAmount()) return Outcome.INVALID_AMOUNT;
        // A verified callback can recover a link created before a local crash/timeout.
        if (p.getPaymentLinkId() == null) p.setPaymentLinkId(result.txnRef());
        if (p.getPaidAt() != null) return Outcome.ALREADY_CONFIRMED;
        boolean success =
                "00".equals(result.responseCode()) && "00".equals(result.transactionStatus());
        if (!success) {
            if (Set.of("CREATED", "CREATING", "PENDING", "AWAITING_WEBHOOK")
                    .contains(p.getStatus())) {
                p.setStatus("FAILED");
                outbox.add(
                        EventType.PAYMENT_FAILED, new PaymentFailed(p.getOrderId(), "RETRYABLE"));
            }
            return Outcome.PROCESSED;
        }
        String reference = result.transactionNo();
        if (reference == null
                || reference.isBlank()
                || "0".equals(reference)
                || reference.length() > 128)
            throw ApiException.badRequest("PAYMENT_MISMATCH", "Mã giao dịch không khớp");
        Instant paidAt = transactionTime(result.payDate());
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
        processPendingReturnRefunds(p);
        return Outcome.PROCESSED;
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
            if ("VNPAY".equals(p.getMethod())) scheduleCancellation(p);
        }
    }

    private void scheduleCancellation(PaymentTransaction p) {
        p.setCancelPending(true);
        p.setCancelRequestToken(null);
        p.setNextCancelAttemptAt(clock.instant());
    }

    @Transactional(propagation = Propagation.NEVER)
    public void cancelRemote(Long id, PaymentGatewayClient client) {
        // VNPay has no cancel API: the client call is a no-op and the URL expires by itself.
        // A payment that still arrives later is handled by process() (late path -> refund).
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
            client.cancel(txnRefOf(id));
        } catch (ApiException failure) {
            error = failure.getCode();
        } catch (RuntimeException failure) {
            error = "VNPAY_UNAVAILABLE";
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
                            if (result == null) {
                                p.setCancelPending(false);
                            } else if (attempts >= timing.getMaxCancelAttempts()) {
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
        processPendingReturnRefunds(p);
        // Delivery owns DELIVERED; an online PaymentSucceeded must not revive a COD order.
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

    @Transactional
    public RefundRequest processReturnRefund(String orderId, String returnId, long refundAmount, String reason) {
        if (returnId != null) {
            var existing = refunds.findByReturnId(returnId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        var p = payments.lockByOrderId(orderId).orElse(null);
        if (p == null || p.getPaidAt() == null) {
            if (returnId != null) {
                var pending = pendingRefunds.findByReturnId(returnId).orElseGet(() ->
                        new PendingReturnRefund(
                                orderId,
                                returnId,
                                refundAmount,
                                reason != null ? reason : "Hoàn tiền trả hàng " + returnId,
                                clock.instant()));
                pending.setAmount(refundAmount);
                pending.setProcessed(false);
                pendingRefunds.save(pending);
                log.info("Lưu pending refund intent cho returnId={} trên đơn {} (chờ thanh toán hoàn tất)",
                        returnId, orderId);
            }
            return null;
        }

        return createRefundForReturn(p, returnId, refundAmount, reason);
    }

    private RefundRequest createRefundForReturn(PaymentTransaction p, String returnId, long refundAmount, String reason) {
        if (returnId != null) {
            var existing = refunds.findByReturnId(returnId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        long totalRefunded = refunds.findAllByPaymentId(p.getId()).stream()
                .mapToLong(RefundRequest::getAmount)
                .sum();
        long remaining = p.getAmount() - totalRefunded;
        if (remaining <= 0) {
            log.warn("Tổng số tiền đã hoàn ({}) đạt mức tối đa thanh toán ({}) của đơn {}", totalRefunded, p.getAmount(), p.getOrderId());
            return null;
        }
        long amountToRefund = Math.min(refundAmount, remaining);
        var r = new RefundRequest();
        r.setPaymentId(p.getId());
        r.setReturnId(returnId);
        r.setAmount(amountToRefund);
        r.setReason(reason != null ? reason : "Hoàn tiền trả hàng " + returnId);
        r.setRequestedAt(clock.instant());
        RefundRequest saved = refunds.save(r);

        if (returnId != null) {
            pendingRefunds.findByReturnId(returnId).ifPresent(pending -> {
                pending.setProcessed(true);
                pendingRefunds.save(pending);
            });
        }
        log.info("Đã tạo yêu cầu hoàn tiền {} cho returnId={}, paymentId={}, đơn {}",
                saved.getId(), returnId, p.getId(), p.getOrderId());
        return saved;
    }

    private void processPendingReturnRefunds(PaymentTransaction p) {
        List<PendingReturnRefund> pendings =
                pendingRefunds.findByOrderIdAndProcessedFalseOrderByCreatedAtAsc(p.getOrderId());
        for (PendingReturnRefund pending : pendings) {
            try {
                createRefundForReturn(p, pending.getReturnId(), pending.getAmount(), pending.getReason());
            } catch (Exception ex) {
                log.error("Lỗi khi xử lý pending return refund {} cho đơn {}: {}",
                        pending.getReturnId(), p.getOrderId(), ex.getMessage(), ex);
            }
        }
    }

    private RefundRequest refund(PaymentTransaction p, String reason) {
        String cancelReturnId = "CANCELLATION_" + p.getId();
        var existing = refunds.findByReturnId(cancelReturnId);
        if (existing.isPresent()) {
            return existing.get();
        }
        var legacy = refunds.findFirstByPaymentIdAndReturnIdIsNull(p.getId());
        if (legacy.isPresent()) {
            return legacy.get();
        }

        long totalRefunded = refunds.findAllByPaymentId(p.getId()).stream()
                .mapToLong(RefundRequest::getAmount)
                .sum();
        long remaining = p.getAmount() - totalRefunded;
        if (remaining <= 0) {
            log.info("Payment {} đã được hoàn tiền đủ (tổng hoàn {} / giá trị {})", p.getId(), totalRefunded, p.getAmount());
            return refunds.findFirstByPaymentIdOrderByRequestedAtDesc(p.getId()).orElse(null);
        }

        var r = new RefundRequest();
        r.setPaymentId(p.getId());
        r.setReturnId(cancelReturnId);
        r.setAmount(remaining);
        r.setReason(reason);
        r.setRequestedAt(clock.instant());
        return refunds.save(r);
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

    private String txnRefOf(Long id) {
        return tx().execute(ignored -> txnRef(lock(id)));
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

    private Instant transactionTime(String text) {
        try {
            return LocalDateTime.parse(
                            text,
                            DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
                                    .withResolverStyle(ResolverStyle.STRICT))
                    .atZone(timing.getTransactionZone())
                    .toInstant();
        } catch (java.time.format.DateTimeParseException invalid) {
            throw ApiException.badRequest(
                    "INVALID_TRANSACTION_TIME", "Thiếu hoặc sai thời gian giao dịch ngân hàng");
        }
    }
}
