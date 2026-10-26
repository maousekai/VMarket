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
}
