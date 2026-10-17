package com.vmarket.delivery.tracking;

import com.vmarket.delivery.repository.DeliveryRepository;
import com.vmarket.delivery.service.DeliveryService;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.*;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
    private final TrackingSocket socket;
    private final TrackingTickets tickets;
    private final DeliveryRepository repo;
    private final DeliveryService deliveries;

    @Value("${app.cors.allowed-origins}")
    private String[] origins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/api/delivery/ws")
                .setAllowedOrigins(origins)
                .addInterceptors(
                        new HandshakeInterceptor() {
                            @Override
                            public boolean beforeHandshake(
                                    ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler handler,
                                    Map<String, Object> attrs) {
                                var query =
                                        UriComponentsBuilder.fromUri(request.getURI())
                                                .build()
                                                .getQueryParams();
                                var access = tickets.consume(query.getFirst("ticket"));
                                if (access != null && access.expiresAt().isAfter(Instant.now())) {
                                    try {
                                        deliveries.scope(
                                                repo.find(access.assignmentId(), false),
                                                access.actor(),
                                                access.admin());
                                        attrs.put("access", access);
                                        return true;
                                    } catch (Exception denied) {
                                    }
                                }
                                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                                return false;
                            }

                            @Override
                            public void afterHandshake(
                                    ServerHttpRequest req,
                                    ServerHttpResponse res,
                                    WebSocketHandler h,
                                    Exception ex) {}
                        });
    }
}
