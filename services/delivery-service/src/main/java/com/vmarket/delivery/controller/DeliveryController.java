package com.vmarket.delivery.controller;

import com.vmarket.delivery.config.DeliveryProperties;
import com.vmarket.delivery.domain.Assignment;
import com.vmarket.delivery.dto.DeliveryRequests.*;
import com.vmarket.delivery.repository.DeliveryRepository;
import com.vmarket.delivery.service.DeliveryService;
import com.vmarket.delivery.tracking.TrackingTickets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/delivery")
@RequiredArgsConstructor
public class DeliveryController {
    private final DeliveryService service;
    private final DeliveryRepository repo;
    private final TrackingTickets tickets;
    private final DeliveryProperties properties;

    @PostMapping("/admin/shippers")
    public Map<String, Object> create(
            Authentication auth,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String key,
            @Valid @RequestBody Shipper body) {
        return service.createShipper(auth.getName(), key, body);
    }

    @GetMapping("/admin/shippers")
    public List<Map<String, Object>> shippers(
            Authentication auth,
            @RequestParam(defaultValue = "") String province,
            @RequestParam(defaultValue = "") String district,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.shippers(auth.getName(), province, district, page, size);
    }

    @PutMapping("/admin/shippers/{id}/lock")
    public void lock(Authentication auth, @PathVariable String id, @Valid @RequestBody Lock body) {
        service.lock(auth.getName(), id, body.locked());
    }

    @PostMapping("/seller/orders/{order}/handoff")
    public Map<String, Object> handoff(
            Authentication auth,
            @RequestHeader("Authorization") String bearer,
            @PathVariable String order) {
        return service.sellerAssignment(service.handoff(auth.getName(), bearer, order));
    }

    @GetMapping("/admin/assignments")
    public List<Map<String, Object>> assignments(
            Authentication auth,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.assignments(auth.getName(), page, size);
    }

    @PostMapping("/admin/assignments/{id}/assign")
    public Assignment assign(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Assign body) {
        return service.assign(auth.getName(), id, body);
    }

    @PostMapping("/admin/assignments/{id}/return")
    public Assignment returnFailed(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Return body) {
        return service.returnFailed(auth.getName(), id, body);
    }

    @GetMapping("/shipper/assignments")
    public List<Assignment> assigned(
            Authentication auth,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.assigned(auth.getName(), page, size);
    }

    @PutMapping("/shipper/assignments/{id}/status")
    public Assignment status(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Status body) {
        return service.status(auth.getName(), id, body);
    }

    @PostMapping("/shipper/assignments/{id}/location")
    public Assignment location(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Location body) {
        return service.location(auth.getName(), id, body);
    }

    @PostMapping({"/shipper/assignments/{id}/retry", "/admin/assignments/{id}/retry"})
    public Assignment retry(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Revision body) {
        return service.retry(
                auth.getName(), id, DeliveryService.admin(auth), body.expectedVersion());
    }

    @GetMapping("/orders/{order}")
    public Map<String, Object> detail(Authentication auth, @PathVariable String order) {
        return service.detail(order, auth.getName(), DeliveryService.admin(auth));
    }

    @GetMapping("/orders/{order}/route")
    public List<Map<String, Object>> route(
            Authentication auth,
            @PathVariable String order,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
        var a = service.trackingOrder(order, auth.getName(), DeliveryService.admin(auth));
        return repo.route(a.getId(), page, size);
    }

    @PostMapping("/orders/{order}/tracking-ticket")
    public Map<String, String> ticket(Authentication auth, @PathVariable String order) {
        var a = service.trackingOrder(order, auth.getName(), DeliveryService.admin(auth));
        String ticket =
                tickets.issue(
                        a.getId(),
                        auth.getName(),
                        DeliveryService.admin(auth),
                        (Instant) auth.getDetails());
        return Map.of(
                "ticket",
                ticket,
                "webSocketUrl",
                properties.getWebSocketUrl() + "?ticket=" + ticket);
    }
}
