package com.vmarket.delivery.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
@ConfigurationProperties("app.delivery")
@Validated @Getter @Setter
public class DeliveryProperties {
    @NotBlank @Size(min=32) private String internalApiKey;
    @NotBlank private String authUrl = "http://localhost:8081";
    @NotBlank private String orderUrl = "http://localhost:8086";
    @NotBlank private String shopUrl = "http://localhost:8083";
    @NotBlank @Pattern(regexp="wss?://[^?#]+/api/delivery/ws")
    private String webSocketUrl = "ws://localhost:8088/api/delivery/ws";
}
