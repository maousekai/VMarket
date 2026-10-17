package com.vmarket.payment.controller;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.vmarket.payment.service.*;
import com.vmarket.payment.dto.*;
import com.vmarket.payment.entity.RefundRequest;
import lombok.RequiredArgsConstructor;
@RestController @RequestMapping("/api/payments") @RequiredArgsConstructor @Validated
public class PaymentController {
    private final PaymentService service; private final PaymentStore store;
    @PostMapping public PaymentResponse create(@AuthenticationPrincipal String buyer, @RequestHeader("Authorization") String token, @Valid @RequestBody PaymentRequests.Create request) { return service.create(buyer, token, request.orderId()); }
    @GetMapping public List<PaymentResponse> list(@AuthenticationPrincipal String buyer, @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return store.list(buyer, page, size); }
    @GetMapping("/{id}") public PaymentResponse own(@AuthenticationPrincipal String buyer, @PathVariable Long id) { return store.own(buyer, id); }
    @PostMapping("/webhooks/payos") public void webhook(@Valid @RequestBody PaymentRequests.Webhook webhook) { service.webhook(webhook); }
    @GetMapping("/admin/refunds") public List<RefundRequest> refunds(@RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return store.pendingRefunds(page, size); }
    @GetMapping("/admin/reconciliations") public List<PaymentResponse> reconciliations(@RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return store.reconciliations(page, size); }
    // Admin checks accepted return/cancellation evidence, then records the manual refund workflow.
    @PostMapping("/admin/{id}/refunds") public RefundRequest refund(@PathVariable Long id, @Valid @RequestBody PaymentRequests.Refund request) { return store.requestRefund(id, request.reason()); }
    @PutMapping("/admin/refunds/{id}/confirm") public RefundRequest confirm(@AuthenticationPrincipal String admin, @PathVariable String id, @Valid @RequestBody PaymentRequests.Confirm request) { return store.confirm(id, admin, request.transferReference()); }
}
