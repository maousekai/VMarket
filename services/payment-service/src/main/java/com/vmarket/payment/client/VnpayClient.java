package com.vmarket.payment.client;

import com.vmarket.payment.config.VnpayProperties;
import com.vmarket.payment.exception.ApiException;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;

/**
 * VNPay không có API tạo link: URL thanh toán được ký và dựng ngay tại service, không gọi HTTP ra
 * ngoài.
 */
@Component
public class VnpayClient implements PaymentGatewayClient {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final VnpayProperties properties;
    private final VnpaySignature signature;
    private final Clock clock;

    public VnpayClient(VnpayProperties properties, VnpaySignature signature, Clock clock) {
        this.properties = properties;
        this.signature = signature;
        this.clock = clock;
        if (properties.isEnabled()
                && (properties.getTmnCode().isBlank() || properties.getHashSecret().isBlank()))
            throw new IllegalStateException(
                    "VNPAY_TMN_CODE and VNPAY_HASH_SECRET are required when enabled");
    }

    @Override
    public Link create(String txnRef, long amount, Instant expiresAt) {
        if (!properties.isEnabled())
            throw ApiException.unavailable("VNPAY_NOT_CONFIGURED", "VNPay chưa được cấu hình");
        var params = new TreeMap<String, String>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", properties.getTmnCode());
        params.put("vnp_Amount", String.valueOf(amount * 100));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_OrderInfo", "Thanh toan don hang " + txnRef);
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", properties.getReturnUrl());
        params.put("vnp_IpAddr", "127.0.0.1");
        params.put("vnp_CreateDate", DATE.format(clock.instant().atZone(ZONE)));
        params.put("vnp_ExpireDate", DATE.format(expiresAt.atZone(ZONE)));
        var query = new StringBuilder();
        params.forEach(
                (key, value) -> {
                    if (!query.isEmpty()) query.append('&');
                    query.append(URLEncoder.encode(key, StandardCharsets.US_ASCII))
                            .append('=')
                            .append(URLEncoder.encode(value, StandardCharsets.US_ASCII));
                });
        String url =
                properties.getPayUrl()
                        + "?"
                        + query
                        + "&vnp_SecureHash="
                        + signature.sign(params, properties.getHashSecret());
        return new Link(txnRef, url, null, Status.PENDING);
    }

    /** VNPay không có API hủy link; URL tự hết hạn tại vnp_ExpireDate. */
    @Override
    public void cancel(String txnRef) {}
}
