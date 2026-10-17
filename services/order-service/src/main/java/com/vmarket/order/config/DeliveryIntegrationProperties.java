package com.vmarket.order.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.delivery")
@Validated
@Getter
@Setter
public class DeliveryIntegrationProperties {
    @NotBlank
    @Size(min = 32)
    private String internalApiKey;
}
