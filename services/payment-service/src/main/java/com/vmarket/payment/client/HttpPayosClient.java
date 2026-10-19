package com.vmarket.payment.client;

import com.vmarket.payment.config.PayosProperties;
import com.vmarket.payment.exception.ApiException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

@Component
@Slf4j
public class HttpPayosClient implements PayosClient {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final PayosProperties properties;
    private final PayosSignature signature;

    public HttpPayosClient(
            HttpClient client,
            ObjectMapper mapper,
            PayosProperties properties,
            PayosSignature signature) {
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
        this.signature = signature;
        if (properties.isEnabled()
                && (properties.getClientId().isBlank()
                        || properties.getApiKey().isBlank()
                        || properties.getChecksumKey().isBlank()))
            throw new IllegalStateException(
                    "PAYOS_CLIENT_ID, PAYOS_API_KEY and PAYOS_CHECKSUM_KEY are required when"
                            + " enabled");
    }

    @Override
    public Link create(long code, long amount, Instant expiresAt) {
        if (!properties.isEnabled())
            throw ApiException.unavailable("PAYOS_NOT_CONFIGURED", "PayOS chưa được cấu hình");
        var signed = new TreeMap<String, Object>();
        signed.put("amount", amount);
        signed.put("cancelUrl", properties.getCancelUrl());
        signed.put("description", "VMARKET");
        signed.put("orderCode", code);
        signed.put("returnUrl", properties.getReturnUrl());
        var body = new TreeMap<>(signed);
        body.put("signature", signature.sign(signed, properties.getChecksumKey()));
        body.put("expiredAt", expiresAt.getEpochSecond());
        JsonNode response;
        ApiException creationFailure = null;
        try {
            response = call("POST", "/v2/payment-requests", body);
            if (!"00".equals(response.path("code").asString())) {
                String codeText = response.path("code").asString().replaceAll("[^A-Za-z0-9_-]", "");
                log.warn(
                        "PayOS create rejected code={} desc={}",
                        codeText,
                        safeDescription(response.path("desc").asString()));
                creationFailure =
                        ApiException.unavailable(
                                "PAYOS_CREATE_REJECTED",
                                "PayOS từ chối tạo link (mã " + codeText + ")");
            }
        } catch (ApiException transportFailure) {
            if ("PAYOS_NOT_CONFIGURED".equals(transportFailure.getCode())) throw transportFailure;
            creationFailure = transportFailure;
            response = mapper.createObjectNode();
        }
        // A timeout may have created the link already. The stable orderCode allows recovery on
        // retry.
        if (!"00".equals(response.path("code").asString())) {
            try {
                response = call("GET", "/v2/payment-requests/" + code, null);
            } catch (ApiException recoveryFailure) {
                throw creationFailure == null ? recoveryFailure : creationFailure;
            }
        }
        if (!"00".equals(response.path("code").asString()))
            throw creationFailure == null ? unavailable() : creationFailure;
        JsonNode data = response.path("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> values = mapper.convertValue(data, Map.class);
        if (!signature.verify(
                values, response.path("signature").asString(), properties.getChecksumKey()))
            throw unavailable();
        if (data.path("orderCode").asLong() != code || data.path("amount").asLong() != amount)
            throw unavailable();
        String linkId = data.path("paymentLinkId").asString("");
        if (linkId.isBlank()) linkId = data.path("id").asString("");
        if (linkId.isBlank() || linkId.length() > 64) throw unavailable();
        Status status;
        try {
            status = Status.valueOf(data.path("status").asString(""));
        } catch (IllegalArgumentException ex) {
            throw unavailable();
        }
        // Only a signed PENDING link can be offered for another payment attempt.
        if (status != Status.PENDING) return new Link(linkId, null, null, status);
        String checkout = data.path("checkoutUrl").asString("");
        if (checkout.isBlank() && linkId.matches("[a-fA-F0-9]{32}"))
            checkout = "https://pay.payos.vn/web/" + linkId;
        if (linkId.isBlank() || checkout.isBlank()) throw unavailable();
        String qr = data.path("qrCode").asString("");
        return new Link(linkId, checkout, qr.isBlank() ? null : qr, status);
    }

    @Override
    public void cancel(long code) {
        JsonNode response;
        try {
            response =
                    call(
                            "POST",
                            "/v2/payment-requests/" + code + "/cancel",
                            Map.of("cancellationReason", "Payment deadline expired"));
        } catch (ApiException failure) {
            if ("PAYOS_NOT_CONFIGURED".equals(failure.getCode())) throw failure;
            response = mapper.createObjectNode();
        }
        // Repeating cancellation of an already terminal link is safe only after
        // verifying the provider's signed current state, not an unsigned error code.
        response = call("GET", "/v2/payment-requests/" + code, null);
        if (!"00".equals(response.path("code").asString())) throw unavailable();
        var data = response.path("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> values = mapper.convertValue(data, Map.class);
        if (!signature.verify(
                        values, response.path("signature").asString(), properties.getChecksumKey())
                || data.path("orderCode").asLong() != code
                || !Set.of("CANCELLED", "EXPIRED", "PAID", "PROCESSING")
                        .contains(data.path("status").asString())) throw unavailable();
        if (Set.of("PAID", "PROCESSING").contains(data.path("status").asString()))
            throw ApiException.unavailable(
                    "PAYOS_PAID_RECONCILIATION_REQUIRED",
                    "PayOS đã thu tiền; cần đối soát webhook trước khi hoàn tiền");
    }

    private JsonNode call(String method, String path, Object body) {
        if (!properties.isEnabled())
            throw ApiException.unavailable("PAYOS_NOT_CONFIGURED", "PayOS chưa được cấu hình");
        var request =
                HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("x-client-id", properties.getClientId())
                        .header("x-api-key", properties.getApiKey());
        if (body == null) request.GET();
        else
            request.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        try {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 && "GET".equals(method))
                throw ApiException.unavailable(
                        "PAYOS_PAYMENT_NOT_FOUND", "PayOS không có link cho giao dịch này");
            if (response.statusCode() != 200) {
                // Preserve a structured provider rejection for create diagnostics; never log the
                // body.
                try {
                    JsonNode error = mapper.readTree(response.body());
                    if (error.hasNonNull("code") && !"00".equals(error.path("code").asString()))
                        return error;
                } catch (RuntimeException malformed) {
                    /* Use the stable dependency error below. */
                }
                throw unavailable();
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (java.io.IOException ex) {
            throw unavailable();
        }
    }

    private ApiException unavailable() {
        return ApiException.unavailable(
                "PAYOS_UNAVAILABLE", "Không thể xác minh giao dịch PayOS, vui lòng thử lại");
    }

    private String safeDescription(String description) {
        String safe = description.replaceAll("[\\r\\n\\t]", " ");
        for (String secret :
                List.of(
                        properties.getClientId(),
                        properties.getApiKey(),
                        properties.getChecksumKey())) {
            if (!secret.isBlank()) safe = safe.replace(secret, "[REDACTED]");
        }
        safe =
                safe.replaceAll(
                                "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
                                "[REDACTED_EMAIL]")
                        .replaceAll("[0-9]{8,}", "[REDACTED_NUMBER]");
        return safe.substring(0, Math.min(160, safe.length()));
    }
}
