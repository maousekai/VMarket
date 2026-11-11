package com.vmarket.payment;

import static org.assertj.core.api.Assertions.*;

import com.vmarket.payment.client.PaymentGatewayClient;
import com.vmarket.payment.client.VnpayClient;
import com.vmarket.payment.client.VnpaySignature;
import com.vmarket.payment.config.VnpayProperties;
import com.vmarket.payment.exception.ApiException;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

class VnpayClientTest {
    private final Instant now = Instant.parse("2026-10-06T15:00:00Z");
    private final VnpaySignature signature = new VnpaySignature();
    private final VnpayProperties properties = new VnpayProperties();

    private VnpayClient client() {
        properties.setEnabled(true);
        properties.setTmnCode("TESTCODE");
        properties.setHashSecret("test-hash-secret");
        return new VnpayClient(properties, signature, Clock.fixed(now, ZoneOffset.UTC));
    }

    private Map<String, String> query(String url) {
        var values = new LinkedHashMap<String, String>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            values.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    @Test
    void buildsASignedUrlWithAmountTimesOneHundredAndVietnamTimes() {
        var link = client().create("1000001T1790000000", 3000L, now.plusSeconds(840));
        assertThat(link.paymentLinkId()).isEqualTo("1000001T1790000000");
        assertThat(link.status()).isEqualTo(PaymentGatewayClient.Status.PENDING);
        assertThat(link.qrCode()).isNull();
        assertThat(link.checkoutUrl())
                .startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        var q = query(link.checkoutUrl());
        assertThat(q)
                .containsEntry("vnp_Version", "2.1.0")
                .containsEntry("vnp_Command", "pay")
                .containsEntry("vnp_TmnCode", "TESTCODE")
                .containsEntry("vnp_Amount", "300000")
                .containsEntry("vnp_CurrCode", "VND")
                .containsEntry("vnp_TxnRef", "1000001T1790000000")
                .containsEntry("vnp_OrderInfo", "Thanh toan don hang 1000001T1790000000")
                .containsEntry("vnp_ReturnUrl", "http://localhost:5173/payment/vnpay-return")
                .containsEntry("vnp_CreateDate", "20261006220000")
                .containsEntry("vnp_ExpireDate", "20261006221400");
        assertThat(signature.verify(q, q.get("vnp_SecureHash"), "test-hash-secret")).isTrue();
    }

    @Test
    void anyChangeToTheUrlInvalidatesTheHash() {
        var q = query(client().create("1T1", 3000L, now.plusSeconds(840)).checkoutUrl());
        q.put("vnp_Amount", "100");
        assertThat(signature.verify(q, q.get("vnp_SecureHash"), "test-hash-secret")).isFalse();
    }

    @Test
    void disabledGatewayIsUnavailableAndEnabledNeedsCredentials() {
        var disabled = new VnpayClient(properties, signature, Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> disabled.create("1T1", 3000L, now))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("VNPAY_NOT_CONFIGURED"));
        var incomplete = new VnpayProperties();
        incomplete.setEnabled(true);
        assertThatThrownBy(
                        () -> new VnpayClient(incomplete, signature, Clock.fixed(now, ZoneOffset.UTC)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelIsANoOp() {
        assertThatCode(() -> client().cancel("1T1")).doesNotThrowAnyException();
    }
}
