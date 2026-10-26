package com.vmarket.order.controller;

import com.vmarket.order.event.OutboxRecovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders/admin/outbox")
@RequiredArgsConstructor
@Validated
public class OutboxAdminController {
    private final OutboxRecovery recovery;

    public record Replay(@NotBlank @Size(max = 500) String reason) {}

    @GetMapping("/dead")
    public List<OutboxRecovery.DeadEvent> dead(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return recovery.dead(page, size);
    }

    @GetMapping("/status")
    public OutboxRecovery.Status status() {
        return recovery.status();
    }

    @PostMapping("/{id}/replay")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replay(
            @PathVariable String id,
            @AuthenticationPrincipal String admin,
            @Valid @RequestBody Replay request) {
        recovery.replay(id, admin, request.reason());
    }
}
