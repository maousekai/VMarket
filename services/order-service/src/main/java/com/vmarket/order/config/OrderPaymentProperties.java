package com.vmarket.order.config;

import jakarta.validation.constraints.AssertTrue;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties("app.payment")
@Validated
@Getter
@Setter
public class OrderPaymentProperties {
    private Duration ttl = Duration.ofMinutes(15);
    private Duration fallbackDelay = Duration.ofMinutes(15);

    @AssertTrue(message = "Payment TTL and fallback delay must be positive")
    public boolean isValid() {
        return ttl != null
                && !ttl.isNegative()
                && !ttl.isZero()
                && fallbackDelay != null
                && fallbackDelay.compareTo(Duration.ofMinutes(5)) >= 0;
    }
}
