package com.vmarket.delivery;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.vmarket.delivery.domain.Assignment;
import com.vmarket.delivery.domain.DeliveryStatus;
import com.vmarket.delivery.repository.DeliveryRepository;
import com.vmarket.delivery.service.DeliveryService;
import com.vmarket.delivery.tracking.TrackingRevoked;
import com.vmarket.delivery.tracking.TrackingSocket;
import com.vmarket.delivery.tracking.TrackingTickets;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

class TrackingSocketTest {
    @Test
    void cachedGrantsAvoidSqlAndHttpOnEveryTickAndRedisMessageButRefreshAndRevoke()
            throws Exception {
        var repo = mock(DeliveryRepository.class);
        var deliveries = mock(DeliveryService.class);
        var clock = mock(Clock.class);
        Instant start = Instant.parse("2026-10-17T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        var assignment = new Assignment();
        assignment.setId("assignment");
        assignment.setStatus(DeliveryStatus.IN_TRANSIT);
        when(repo.find("assignment", false)).thenReturn(assignment);
        var socket = new TrackingSocket(repo, deliveries, new ObjectMapper(), clock);
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes())
                .thenReturn(
                        Map.of(
                                "access",
                                new TrackingTickets.Access(
                                        "assignment", "shipper", false, start.plusSeconds(120))));
        socket.afterConnectionEstablished(session);
        for (int i = 1; i <= 20; i++) {
            socket.expire();
            socket.accept("{\"assignmentId\":\"assignment\",\"sequence\":" + i + ",\"version\":1}");
        }
        verify(deliveries, times(1)).scope(assignment, "shipper", false);
        verify(repo, times(2)).find("assignment", false);

        when(clock.instant()).thenReturn(start.plusSeconds(31));
        socket.accept("{\"assignmentId\":\"assignment\",\"sequence\":21,\"version\":1}");
        verify(deliveries, times(1)).scope(assignment, "shipper", false);
        socket.expire();
        verify(deliveries, times(2)).scope(assignment, "shipper", false);
        socket.revoke(new TrackingRevoked("assignment", "shipper"));
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void jwtExpiryClosesSessionWithoutWaitingForTheAuthorizationCache() throws Exception {
        var repo = mock(DeliveryRepository.class);
        var clock = mock(Clock.class);
        Instant start = Instant.parse("2026-10-17T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        var assignment = new Assignment();
        assignment.setId("assignment");
        assignment.setStatus(DeliveryStatus.ASSIGNED);
        when(repo.find("assignment", false)).thenReturn(assignment);
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes())
                .thenReturn(
                        Map.of(
                                "access",
                                new TrackingTickets.Access(
                                        "assignment", "buyer", false, start.plusSeconds(2))));
        var socket =
                new TrackingSocket(repo, mock(DeliveryService.class), new ObjectMapper(), clock);
        socket.afterConnectionEstablished(session);
        when(clock.instant()).thenReturn(start.plusSeconds(2));
        socket.expire();
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(repo, times(2)).find("assignment", false);
    }
}
