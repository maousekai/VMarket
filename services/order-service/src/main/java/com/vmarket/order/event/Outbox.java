package com.vmarket.order.event;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
@Service @RequiredArgsConstructor
public class Outbox {
    private final OutboxRepository repository;
    private final ObjectMapper mapper;
    @Transactional
    public void add(String type, Object payload) {
        var event = new OutboxEvent(); event.setEventType(type); event.setPayload(mapper.writeValueAsString(payload)); repository.save(event);
    }
}
