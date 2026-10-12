package com.vmarket.order.delivery;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/delivery/orders")
@RequiredArgsConstructor
public class InternalDeliveryController {
    private final OrderDeliveryBridge bridge;
    @GetMapping("/{id}")
    public JsonNode get(@PathVariable String id, @RequestHeader(value="X-Internal-Api-Key", required=false) String key) {
        bridge.authorize(key);
        return bridge.snapshot(id, false);
    }
    @PostMapping("/{id}/claim")
    public JsonNode claim(@PathVariable String id, @RequestHeader(value="X-Internal-Api-Key", required=false) String key) {
        bridge.authorize(key);
        return bridge.snapshot(id, true);
    }
}
