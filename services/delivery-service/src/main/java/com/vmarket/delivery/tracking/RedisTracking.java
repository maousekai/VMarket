package com.vmarket.delivery.tracking;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.*;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RedisTracking {
    public static final String CHANNEL = "delivery.tracking";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private static final DefaultRedisScript<Long> UPDATE =
            new DefaultRedisScript<>(
                    """
        local old = redis.call('GET', KEYS[1])
        if old then
            local previous = cjson.decode(old)
            if tonumber(previous.sequence or previous.version) >= tonumber(ARGV[2]) then return 0 end
        end
        redis.call('SET', KEYS[1], ARGV[1], 'EX', 86400)
        redis.call('PUBLISH', ARGV[3], ARGV[1])
        return 1
        """,
                    Long.class);

    public void publish(String body) {
        var node = json.readTree(body);
        if ("TrackingAccessRevoked".equals(node.path("type").asString())) {
            redis.convertAndSend(CHANNEL, body);
            return;
        }
        redis.execute(
                UPDATE,
                List.of("delivery:location:" + node.path("assignmentId").asString()),
                body,
                Long.toString(
                        node.has("sequence")
                                ? node.path("sequence").asLong()
                                : node.path("version").asLong()),
                CHANNEL);
    }

    @Configuration
    @ConditionalOnProperty(
            name = "app.tracking-listener",
            havingValue = "true",
            matchIfMissing = true)
    public static class ListenerConfig {
        @Bean
        RedisMessageListenerContainer trackingListener(
                RedisConnectionFactory factory, TrackingSocket socket) {
            var container = new RedisMessageListenerContainer();
            container.setConnectionFactory(factory);
            container.addMessageListener(
                    (message, pattern) ->
                            socket.accept(new String(message.getBody(), StandardCharsets.UTF_8)),
                    new ChannelTopic(CHANNEL));
            return container;
        }
    }
}
