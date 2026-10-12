package com.vmarket.payment.event;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;
import com.vmarket.events.EventPublisher;
import lombok.RequiredArgsConstructor;
@Service @RequiredArgsConstructor
public class OutboxDispatcher {
    private final OutboxRepository repository;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;
    @Transactional
    public void dispatch() {
        for (var event : repository.next(PageRequest.of(0, 50))) {
            publisher.publish(event.getEventType(), mapper.readTree(event.getPayload()));
            repository.delete(event);
        }
    }
}
