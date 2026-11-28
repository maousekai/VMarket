package com.vmarket.delivery.controller;

import com.vmarket.delivery.config.DeliveryProperties;
import com.vmarket.delivery.domain.Assignment;
import com.vmarket.delivery.domain.DeliveryStatus;
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
            @RequestParam(defaultValue = "") @Pattern(regexp = "|on|off") String shift,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.shippers(auth.getName(), province, district, shift, page, size);
    }

    @PutMapping("/admin/shippers/{id}/lock")
    public void lock(Authentication auth, @PathVariable String id, @Valid @RequestBody Lock body) {
        service.lock(auth.getName(), id, body.locked());
    }

    /** Gui lai email kich hoat cho shipper chua xac thuc email. */
    @PostMapping("/admin/shippers/{id}/activation-email")
    public Map<String, Object> resendActivation(Authentication auth, @PathVariable String id) {
        service.resendActivation(auth.getName(), id);
        return Map.of("sent", true);
    }

    @PostMapping("/seller/orders/{order}/handoff")
    public Map<String, Object> handoff(
            Authentication auth,
            @RequestHeader("Authorization") String bearer,
            @PathVariable String order) {
        return service.sellerAssignment(service.handoff(auth.getName(), bearer, order));
    }

    /** Seller xac nhan da nhan lai hang hoan (RETURN_DROPPED_OFF -> RETURNED khi du moi shop). */
    @PostMapping("/seller/orders/{order}/return-received")
    public Map<String, Object> returnReceived(
            Authentication auth,
            @RequestHeader("Authorization") String bearer,
            @PathVariable String order) {
        return service.sellerReturnReceived(auth.getName(), bearer, order);
    }

    @GetMapping("/admin/assignments")
    public List<Map<String, Object>> assignments(
            Authentication auth,
            @RequestParam(required = false) DeliveryStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.assignments(
                auth.getName(), status == null ? "" : status.name(), page, size);
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

    /** Chuyen dang hoan bi ket (shipper bi khoa): admin xac nhan hang da ve shop. */
    @PostMapping("/admin/assignments/{id}/return-dropped-off")
    public Assignment returnDroppedOff(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Return body) {
        return service.adminReturnDroppedOff(auth.getName(), id, body);
    }

    /** Ca lam viec (review PR 68 lan 3, phan 2). */
    @GetMapping("/shipper/shift")
    public Map<String, Object> shift(Authentication auth) {
        return service.shift(auth.getName());
    }

    @PostMapping("/shipper/shift/start")
    public Map<String, Object> startShift(Authentication auth) {
        return service.startShift(auth.getName());
    }

    @PostMapping("/shipper/shift/end")
    public Map<String, Object> endShift(
            Authentication auth, @RequestBody(required = false) ShiftEnd body) {
        return service.endShift(auth.getName(), body != null && body.confirmed());
    }

    /**
     * scope=active: moi chuyen con viec (khong phan trang) - khong chuyen dang chay nao roi sang
     * trang sau; scope=finished: chuyen da xong, phan trang.
     */
    @GetMapping("/shipper/assignments")
    public List<Map<String, Object>> assigned(
            Authentication auth,
            @RequestParam(defaultValue = "active") @Pattern(regexp = "active|finished")
                    String scope,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.assigned(auth.getName(), scope, page, size);
    }

    @PostMapping("/shipper/assignments/{id}/pickups/{shop}")
    public Map<String, Object> pickup(
            Authentication auth,
            @PathVariable String id,
            @PathVariable String shop,
            @Valid @RequestBody Revision body) {
        return service.shipperView(
                service.confirmPickup(auth.getName(), id, shop, body.expectedVersion()));
    }

    @PostMapping("/shipper/assignments/{id}/returns/{shop}")
    public Map<String, Object> returned(
            Authentication auth,
            @PathVariable String id,
            @PathVariable String shop,
            @Valid @RequestBody Revision body) {
        return service.shipperView(
                service.confirmReturn(auth.getName(), id, shop, body.expectedVersion()));
    }

    /** Mot diem GPS cho moi chuyen dang chay (thay cho N request rieng le). */
    @PostMapping("/shipper/locations")
    public Map<String, Object> locations(
            Authentication auth, @Valid @RequestBody Location body) {
        return service.batchLocations(auth.getName(), body);
    }

    @PutMapping("/shipper/assignments/{id}/status")
    public Map<String, Object> status(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Status body) {
        return service.shipperView(service.status(auth.getName(), id, body));
    }

    @PostMapping("/shipper/assignments/{id}/location")
    public Map<String, Object> location(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Location body) {
        return service.shipperView(service.location(auth.getName(), id, body));
    }

    @PostMapping({"/shipper/assignments/{id}/retry", "/admin/assignments/{id}/retry"})
    public Map<String, Object> retry(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Revision body) {
        return service.shipperView(
                service.retry(
                        auth.getName(), id, DeliveryService.admin(auth), body.expectedVersion()));
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
