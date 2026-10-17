package com.vmarket.payment.client;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
@Component
public class PayosSignature {
    private final ObjectMapper mapper;
    public PayosSignature(ObjectMapper mapper) { this.mapper = mapper; }
    public String sign(Map<String, ?> data, String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Checksum key is required");
        String text = new TreeMap<>(data).entrySet().stream()
            .map(e -> e.getKey() + "=" + value(e.getValue())).collect(Collectors.joining("&"));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("HMAC unavailable", ex); }
    }
    public boolean verify(Map<String, ?> data, String signature, String key) {
        if (signature == null || !signature.matches("(?i)[0-9a-f]{64}") || key == null || key.isBlank()) return false;
        return MessageDigest.isEqual(HexFormat.of().parseHex(signature), HexFormat.of().parseHex(sign(data, key)));
    }
    private String value(Object value) {
        if (value == null || "null".equals(value) || "undefined".equals(value)) return "";
        if (value instanceof List<?> items) return mapper.writeValueAsString(items.stream()
            .map(item -> item instanceof Map<?, ?> object ? new TreeMap<>(object) : item).toList());
        return String.valueOf(value);
    }
}
