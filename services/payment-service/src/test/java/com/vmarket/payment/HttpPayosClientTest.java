package com.vmarket.payment;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.vmarket.payment.client.HttpPayosClient;
import com.vmarket.payment.client.PayosSignature;
import com.vmarket.payment.config.PayosProperties;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class HttpPayosClientTest {
    HttpServer server;
    ObjectMapper mapper = JsonMapper.builder().build();
    PayosProperties properties = new PayosProperties();
    PayosSignature signature = new PayosSignature(mapper);
    AtomicReference<Map<?, ?>> sent = new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties.setEnabled(true); properties.setClientId("test-client"); properties.setApiKey("test-api");
        properties.setChecksumKey("test-checksum"); properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void cleanup() { server.stop(0); }
    HttpPayosClient client() { return new HttpPayosClient(HttpClient.newHttpClient(), mapper, properties, signature); }
    void respond(HttpExchange exchange, Object value) throws java.io.IOException {
        byte[] bytes = mapper.writeValueAsBytes(value); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    @Test void requestIncludesFixedCallbacksDeadlineAndVerifiableSignature() throws Exception {
        server.createContext("/v2/payment-requests", exchange -> {
            sent.set(mapper.readValue(exchange.getRequestBody(), Map.class));
            var data = Map.<String,Object>of("orderCode", 1000000, "amount", 3000, "paymentLinkId", "a".repeat(32), "checkoutUrl", "https://pay.payos.vn/test", "qrCode", "QR");
            respond(exchange, Map.of("code", "00", "data", data, "signature", signature.sign(data, properties.getChecksumKey())));
        }); server.start();
        var expires = Instant.parse("2026-10-06T15:15:00Z");
        var link = client().create(1000000, 3000, expires);
        assertThat(link.qrCode()).isEqualTo("QR");
        assertThat(sent.get().get("expiredAt").toString()).isEqualTo(String.valueOf(expires.getEpochSecond()));
        var signed = Map.of("amount", sent.get().get("amount"), "cancelUrl", sent.get().get("cancelUrl"),
            "description", sent.get().get("description"), "orderCode", sent.get().get("orderCode"), "returnUrl", sent.get().get("returnUrl"));
        assertThat(signature.verify(signed, sent.get().get("signature").toString(), properties.getChecksumKey())).isTrue();
    }
    @Test void duplicateCodeRecoversTheExistingSignedProviderLink() throws Exception {
        server.createContext("/v2/payment-requests", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) respond(exchange, Map.of("code", "231", "desc", "duplicate"));
            else {
                var data = Map.<String,Object>of("orderCode", 1000000, "amount", 3000, "id", "a".repeat(32), "status", "PENDING");
                respond(exchange, Map.of("code", "00", "data", data, "signature", signature.sign(data, properties.getChecksumKey())));
            }
        }); server.start();
        var recovered = client().create(1000000, 3000, Instant.now().plusSeconds(900));
        assertThat(recovered.checkoutUrl()).isEqualTo("https://pay.payos.vn/web/" + "a".repeat(32));
        assertThat(recovered.qrCode()).isNull();
    }
    @Test void tamperedProviderResponseCannotBecomePaymentLink() throws Exception {
        server.createContext("/v2/payment-requests", exchange -> respond(exchange, Map.of("code", "00", "data", Map.of("orderCode", 1000000, "amount", 1), "signature", "0".repeat(64)))); server.start();
        assertThatThrownBy(() -> client().create(1000000, 3000, Instant.now().plusSeconds(900)))
            .isInstanceOf(com.vmarket.payment.exception.ApiException.class);
    }
    @Test void disabledProviderReturnsConfigurationErrorBeforeHttp() {
        properties.setEnabled(false); properties.setChecksumKey("");
        assertThatThrownBy(() -> client().create(1000000, 3000, Instant.now().plusSeconds(900)))
            .isInstanceOf(com.vmarket.payment.exception.ApiException.class)
            .extracting(ex -> ((com.vmarket.payment.exception.ApiException) ex).getCode()).isEqualTo("PAYOS_NOT_CONFIGURED");
    }
    @Test void repeatCancellationAcceptsOnlySignedTerminalStatus() throws Exception {
        server.createContext("/v2/payment-requests", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) respond(exchange, Map.of("code", "99"));
            else {
                var data = Map.<String,Object>of("orderCode", 1000000, "status", "CANCELLED");
                respond(exchange, Map.of("code", "00", "data", data, "signature", signature.sign(data, properties.getChecksumKey())));
            }
        }); server.start(); client().cancel(1000000);
    }
    @Test void alreadyPaidIsReconciliationRatherThanSuccessfulCancellation() throws Exception {
        server.createContext("/v2/payment-requests", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) respond(exchange, Map.of("code", "99"));
            else {
                var data = Map.<String,Object>of("orderCode", 1000000, "status", "PAID");
                respond(exchange, Map.of("code", "00", "data", data, "signature", signature.sign(data, properties.getChecksumKey())));
            }
        }); server.start();
        assertThatThrownBy(() -> client().cancel(1000000)).isInstanceOf(com.vmarket.payment.exception.ApiException.class)
            .extracting(ex -> ((com.vmarket.payment.exception.ApiException) ex).getCode()).isEqualTo("PAYOS_PAID_RECONCILIATION_REQUIRED");
    }
}
