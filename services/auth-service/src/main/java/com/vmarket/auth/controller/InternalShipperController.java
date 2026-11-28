package com.vmarket.auth.controller;

import java.util.Map;
import com.vmarket.auth.dto.RegisterRequest;
import com.vmarket.auth.service.ShipperAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

/** Protected by the existing internal API key filter; never routed by Gateway. */
@RestController
@RequestMapping("/internal/shippers")
@RequiredArgsConstructor
public class InternalShipperController {
    private final ShipperAccountService accounts;

    @PostMapping
    public Map<String, String> create(@RequestHeader("X-Actor-Id") String actor,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String key,
            @Valid @RequestBody RegisterRequest request) {
        return accounts.create(actor, key, request);
    }

    @GetMapping("/{id}/status")
    public Map<String, Boolean> status(@PathVariable String id) {
        return accounts.status(id);
    }

    public record StatusRequest(@jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.Size(max = 200) java.util.List<String> ids) {}

    /** Trang thai nhieu shipper trong mot request (danh sach shipper cua admin). */
    @PostMapping("/status")
    public Map<String, Object> statuses(@Valid @RequestBody StatusRequest request) {
        return Map.of("statuses", accounts.statuses(request.ids()));
    }

    /** Gui lai email kich hoat cho shipper chua xac thuc email. */
    @PostMapping("/{id}/activation")
    public Map<String, Object> resendActivation(@RequestHeader("X-Actor-Id") String actor,
            @PathVariable String id) {
        return accounts.resendActivation(actor, id);
    }
}
