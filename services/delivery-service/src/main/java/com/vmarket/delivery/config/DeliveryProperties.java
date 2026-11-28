package com.vmarket.delivery.config;

import jakarta.validation.constraints.*;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app.delivery")
@Validated
@Getter
@Setter
public class DeliveryProperties {
    public static final String WEB_SOCKET_URL_PATTERN = "(wss?://[^?#/]+[^?#]*)?/api/delivery/ws";

    @NotBlank
    @Size(min = 32)
    private String internalApiKey;

    @NotBlank private String authUrl = "http://localhost:8081";
    @NotBlank private String orderUrl = "http://localhost:8086";
    @NotBlank private String shopUrl = "http://localhost:8083";

    /** URL tuyet doi (ws/wss) hoac duong dan tuong doi cung origin voi trang web (mac dinh). */
    @NotBlank
    @Pattern(regexp = WEB_SOCKET_URL_PATTERN)
    private String webSocketUrl = "ws://localhost:8088/api/delivery/ws";

    /**
     * Ban giao ngay sau khi seller xac nhan: ton kho co the chua giu xong (giu kho chay bat dong bo
     * qua RabbitMQ). Delivery cho toi da so lan x khoang nay truoc khi bao ORDER_NOT_READY (review PR
     * 68 lan 3, muc 1.3).
     */
    @Min(1)
    private int stockWaitAttempts = 4;

    @Min(0)
    private long stockWaitMillis = 1000;
}
