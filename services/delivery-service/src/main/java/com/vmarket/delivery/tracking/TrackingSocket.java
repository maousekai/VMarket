package com.vmarket.delivery.tracking;

import com.vmarket.delivery.repository.DeliveryRepository;
import com.vmarket.delivery.service.DeliveryService;

import lombok.RequiredArgsConstructor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class TrackingSocket extends TextWebSocketHandler {
    private static final class Client {
        final WebSocketSession session;
        final TrackingTickets.Access access;
        long sequence = -1;
        volatile Instant authorizedUntil = Instant.EPOCH;

        Client(WebSocketSession session, TrackingTickets.Access access) {
            this.session = session;
            this.access = access;
        }
    }

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final DeliveryRepository repo;
    private final DeliveryService deliveries;
    private final ObjectMapper json;
    private final Clock clock;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        var access = (TrackingTickets.Access) session.getAttributes().get("access");
        var client =
                new Client(new ConcurrentWebSocketSessionDecorator(session, 3000, 65536), access);
        clients.put(session.getId(), client);
        if (!valid(client)) {
            close(client);
            return;
        }
        var a = repo.find(access.assignmentId(), false);
        send(client, a.getTrackingSequence(), json.writeValueAsString(TrackingUpdate.from(a)));
    }

    public void accept(String body) {
        var node = json.readTree(body);
        if ("TrackingAccessRevoked".equals(node.path("type").asString())) {
            revoke(
                    new TrackingRevoked(
                            node.path("assignmentId").asString(), node.path("actor").asString()));
            return;
        }
        String assignment = node.path("assignmentId").asString();
        long sequence =
                node.has("sequence")
                        ? node.path("sequence").asLong()
                        : node.path("version").asLong();
        for (var client : clients.values()) {
            if (assignment.equals(client.access.assignmentId())) {
                // Redis listener never waits for SQL or HTTP. The scheduler refreshes expired
                // grants.
                if (expired(client)) close(client);
                else if (client.authorizedUntil.isAfter(clock.instant()))
                    send(client, sequence, body);
            }
        }
    }

    private boolean valid(Client client) {
        if (expired(client)) return false;
        if (client.authorizedUntil.isAfter(clock.instant())) return true;
        try {
            deliveries.scope(
                    repo.find(client.access.assignmentId(), false),
                    client.access.actor(),
                    client.access.admin());
            client.authorizedUntil = clock.instant().plusSeconds(30);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean expired(Client client) {
        return !client.session.isOpen() || !client.access.expiresAt().isAfter(clock.instant());
    }

    @TransactionalEventListener
    public void revoke(TrackingRevoked revoked) {
        for (Client client : clients.values()) {
            if (revoked.assignmentId().equals(client.access.assignmentId())
                    && (revoked.actor().isEmpty() || revoked.actor().equals(client.access.actor())))
                close(client);
        }
    }

    private void send(Client c, long sequence, String body) {
        synchronized (c) {
            if (sequence <= c.sequence) return;
            try {
                c.session.sendMessage(new TextMessage(body));
                c.sequence = sequence;
            } catch (Exception ex) {
                close(c);
            }
        }
    }

    private void close(Client c) {
        clients.remove(c.session.getId());
        try {
            c.session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignored) {
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void expire() {
        clients.values().stream().filter(c -> !valid(c)).forEach(this::close);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
            throws Exception {
        // GPS goes through authenticated REST validation; socket is receive-only.
        session.close(CloseStatus.NOT_ACCEPTABLE);
    }
}
