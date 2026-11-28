package com.vmarket.delivery.client;

import com.vmarket.delivery.config.DeliveryProperties;
import com.vmarket.delivery.exception.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Component
public class DeliveryClients {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Ma loi cua service nguon duoc giu nguyen de nghiep vu xu ly rieng (vd. cho giu ton kho). */
    private static final java.util.Set<String> PASSTHROUGH_CODES =
            java.util.Set.of("STOCK_NOT_RESERVED", "ORDER_NOT_READY", "ORDER_NOT_CONFIRMED");

    private final DeliveryProperties config;
    private final RestClient http;

    public DeliveryClients(DeliveryProperties config, RestClient.Builder builder) {
        this.config = config;
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.requestFactory(factory).build();
    }

    private JsonNode call(RestClient.RequestHeadersSpec<?> request) {
        try {
            var result = request.retrieve().body(JsonNode.class);
            if (result == null) throw new IllegalStateException("Empty upstream response");
            return result;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                String code = upstreamCode(ex);
                if (PASSTHROUGH_CODES.contains(code))
                    throw new ApiException(
                            code,
                            HttpStatus.valueOf(ex.getStatusCode().value()),
                            "Service nguồn từ chối thao tác");
                throw new ApiException(
                        "UPSTREAM_REJECTED",
                        HttpStatus.valueOf(ex.getStatusCode().value()),
                        "Service nguồn từ chối thao tác");
            }
            throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "Service nguồn chưa sẵn sàng");
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiException.unavailable(
                    "DEPENDENCY_UNAVAILABLE", "Không kết nối được service nguồn");
        }
    }

    static String upstreamCode(RestClientResponseException ex) {
        try {
            return JSON.readTree(ex.getResponseBodyAsString()).path("error").path("code").asString("");
        } catch (Exception ignored) {
            return "";
        }
    }

    public JsonNode order(String id, boolean claim) {
        var uri = config.getOrderUrl() + "/internal/delivery/orders/{id}";
        return claim
                ? call(
                        http.post()
                                .uri(uri + "/claim", id)
                                .header("X-Internal-Api-Key", config.getInternalApiKey()))
                : call(
                        http.get()
                                .uri(uri, id)
                                .header("X-Internal-Api-Key", config.getInternalApiKey()));
    }

    public JsonNode shop(String token) {
        return call(
                http.get()
                        .uri(config.getShopUrl() + "/api/shops/me")
                        .header("Authorization", token));
    }

    public void admin(String actor) {
        call(
                http.get()
                        .uri(config.getAuthUrl() + "/internal/users/{id}", actor)
                        .header("X-Internal-Api-Key", config.getInternalApiKey())
                        .header("X-Actor-Id", actor));
    }

    public boolean activeShipper(String id) {
        try {
            return call(http.get()
                            .uri(config.getAuthUrl() + "/internal/shippers/{id}/status", id)
                            .header("X-Internal-Api-Key", config.getInternalApiKey()))
                    .path("active")
                    .asBoolean();
        } catch (ApiException ex) {
            if (ex.getStatus() == HttpStatus.NOT_FOUND) return false;
            throw ex;
        }
    }

    /**
     * Trang thai tai khoan cua nhieu shipper trong MOT request: id -> PENDING_ACTIVATION |
     * ACTIVE | SUSPENDED | NOT_FOUND (auth-service la nguon su that).
     */
    public Map<String, String> shipperStatuses(java.util.Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        JsonNode body =
                call(
                        http.post()
                                .uri(config.getAuthUrl() + "/internal/shippers/status")
                                .header("X-Internal-Api-Key", config.getInternalApiKey())
                                .body(Map.of("ids", java.util.List.copyOf(ids))));
        Map<String, String> result = new java.util.LinkedHashMap<>();
        body.path("statuses")
                .properties()
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().asString()));
        return result;
    }

    /** Gui lai email kich hoat (xac thuc email + dat mat khau) cho shipper chua kich hoat. */
    public void resendActivation(String actor, String shipper) {
        call(
                http.post()
                        .uri(config.getAuthUrl() + "/internal/shippers/{id}/activation", shipper)
                        .header("X-Internal-Api-Key", config.getInternalApiKey())
                        .header("X-Actor-Id", actor));
    }

    public String createShipper(String actor, String key, Object account) {
        return call(http.post()
                        .uri(config.getAuthUrl() + "/internal/shippers")
                        .header("X-Internal-Api-Key", config.getInternalApiKey())
                        .header("X-Actor-Id", actor)
                        .header("Idempotency-Key", key)
                        .body(account))
                .path("userId")
                .asString();
    }

    public void lock(String actor, String shipper, boolean locked) {
        String url = config.getAuthUrl() + "/internal/users/{id}/suspension";
        if (locked)
            call(
                    http.put()
                            .uri(url, shipper)
                            .header("X-Internal-Api-Key", config.getInternalApiKey())
                            .header("X-Actor-Id", actor)
                            .body(Map.of("reason", "Khoá tài khoản shipper")));
        else
            call(
                    http.delete()
                            .uri(url, shipper)
                            .header("X-Internal-Api-Key", config.getInternalApiKey())
                            .header("X-Actor-Id", actor));
    }
}
