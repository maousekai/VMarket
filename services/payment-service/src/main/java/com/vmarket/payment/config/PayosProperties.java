package com.vmarket.payment.config;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Getter @Setter
@ConfigurationProperties("app.payos")
public class PayosProperties {
    private boolean enabled;
    private String clientId = "";
    private String apiKey = "";
    private String checksumKey = "";
    private String baseUrl = "https://api-merchant.payos.vn";
    private String returnUrl = "http://localhost:5173/payment/return";
    private String cancelUrl = "http://localhost:5173/payment/cancel";
}
