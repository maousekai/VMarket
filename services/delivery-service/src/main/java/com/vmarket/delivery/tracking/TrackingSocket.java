package com.vmarket.delivery.tracking;

import com.vmarket.delivery.repository.DeliveryRepository;
import com.vmarket.delivery.service.DeliveryService;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.*;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

@Component @RequiredArgsConstructor
public class TrackingSocket extends TextWebSocketHandler {
    private record Client(WebSocketSession session, TrackingTickets.Access access, long[] version) {}
    private final Map<String,Client> clients = new ConcurrentHashMap<>();
    private final DeliveryRepository repo;
    private final DeliveryService deliveries;
    private final ObjectMapper json;

    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        var access = (TrackingTickets.Access) session.getAttributes().get("access");
        var client = new Client(new ConcurrentWebSocketSessionDecorator(session, 3000, 65536), access, new long[]{-1});
        clients.put(session.getId(), client);
        if (!valid(client)) { close(client); return; }
        var a = repo.find(access.assignmentId(), false);
        send(client, a.getVersion(), json.writeValueAsString(Map.of("assignmentId", a.getId(), "version", a.getVersion(),
                "status", a.getStatus().name(), "active", a.getStatus().tracking(), "location", a.getLatitude() == null ? Map.of() :
                Map.of("latitude", a.getLatitude(), "longitude", a.getLongitude(), "recordedAt", a.getLocationAt().toString()))));
    }
    public void accept(String body) {
        var node = json.readTree(body);
        String assignment = node.path("assignmentId").asString();
        long version = node.path("version").asLong();
        for (var client : clients.values()) {
            if (assignment.equals(client.access.assignmentId())) {
                if (valid(client)) send(client, version, body); else close(client);
            }
        }
    }
    private boolean valid(Client client) {
        if (!client.session.isOpen() || !client.access.expiresAt().isAfter(Instant.now())) return false;
        try {
            deliveries.scope(repo.find(client.access.assignmentId(), false), client.access.actor(), client.access.admin());
            return true;
        } catch (Exception ex) { return false; }
    }
    private void send(Client c, long version, String body) {
        synchronized (c) {
            if (version <= c.version[0]) return;
            try { c.session.sendMessage(new TextMessage(body)); c.version[0] = version;
            } catch (Exception ex) { close(c); }
        }
    }
    private void close(Client c) {
        clients.remove(c.session.getId());
        try { c.session.close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) { }
    }
    @Scheduled(fixedDelay=1000)
    public void expire() { clients.values().stream().filter(c -> !valid(c)).forEach(this::close); }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { clients.remove(session.getId()); }
    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // GPS goes through authenticated REST validation; socket is receive-only.
        session.close(CloseStatus.NOT_ACCEPTABLE);
    }
}
