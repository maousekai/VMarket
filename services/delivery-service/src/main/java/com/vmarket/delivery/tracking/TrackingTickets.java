package com.vmarket.delivery.tracking;

import com.vmarket.delivery.exception.ApiException;
import java.time.*;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component @RequiredArgsConstructor
public class TrackingTickets {
    public record Access(String assignmentId, String actor, boolean admin, Instant expiresAt) {}
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    public String issue(String assignment, String actor, boolean admin, Instant tokenExpiry) {
        if (tokenExpiry == null || !tokenExpiry.isAfter(Instant.now())) throw new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Token hết hạn");
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        var access = new Access(assignment, actor, admin, tokenExpiry);
        try {
            redis.opsForValue().set("delivery:ticket:" + token, json.writeValueAsString(access), Duration.ofSeconds(30));
        } catch (Exception ex) { throw ApiException.unavailable("TRACKING_UNAVAILABLE", "Chưa kết nối được hệ thống theo dõi"); }
        return token;
    }
    public Access consume(String ticket) {
        if (ticket == null || !ticket.matches("[a-f0-9-]{72}")) return null;
        try {
            String value = redis.opsForValue().getAndDelete("delivery:ticket:" + ticket);
            return value == null ? null : json.readValue(value, Access.class);
        } catch (Exception ex) { return null; }
    }
}
