package com.vmarket.order.event;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class Outbox {
    private final OutboxRepository repository;
    private final ObjectMapper mapper;

    @Transactional
    public void add(String type, Object payload) {
        var event = new OutboxEvent();
        event.setEventType(type);
        event.setPayload(mapper.writeValueAsString(payload));
        String orderId = mapper.valueToTree(payload).path("orderId").asString();
        event.setAggregateId(orderId.isBlank() ? null : orderId);
        repository.save(event);
    }
}
