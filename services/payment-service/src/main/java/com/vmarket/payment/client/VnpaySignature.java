package com.vmarket.payment.client;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Chữ ký HMAC-SHA512 theo tài liệu VNPay 2.1.0 (cùng thuật toán với bản Java mẫu của VNPay). */
@Component
public class VnpaySignature {
    private static final String HASH = "vnp_SecureHash";
    private static final String HASH_TYPE = "vnp_SecureHashType";

    /** Chuỗi dữ liệu để ký: tham số sắp theo tên, bỏ giá trị rỗng, bỏ chính các trường hash. */
    public String hashData(Map<String, String> params) {
        var sorted = new TreeMap<String, String>();
        params.forEach(
                (key, value) -> {
                    if (key != null
                            && value != null
                            && !value.isEmpty()
                            && !HASH.equals(key)
                            && !HASH_TYPE.equals(key)) sorted.put(key, value);
                });
        var text = new StringBuilder();
        sorted.forEach(
                (key, value) -> {
                    if (!text.isEmpty()) text.append('&');
                    text.append(URLEncoder.encode(key, StandardCharsets.US_ASCII))
                            .append('=')
                            .append(URLEncoder.encode(value, StandardCharsets.US_ASCII));
                });
        return text.toString();
    }

    public String sign(Map<String, String> params, String secret) {
        if (secret == null || secret.isBlank())
            throw new IllegalArgumentException("Hash secret is required");
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(hashData(params).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC unavailable", ex);
        }
    }

    /** So sánh hằng thời gian, không phân biệt hoa thường của chuỗi hex. */
    public boolean verify(Map<String, String> params, String signature, String secret) {
        if (signature == null
                || !signature.matches("(?i)[0-9a-f]{128}")
                || secret == null
                || secret.isBlank()) return false;
        return MessageDigest.isEqual(
                signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                sign(params, secret).getBytes(StandardCharsets.US_ASCII));
    }
}
