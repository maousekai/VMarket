package com.vmarket.payment.config;

import jakarta.validation.constraints.AssertTrue;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.ZoneId;

@ConfigurationProperties("app.payment")
@Validated
@Getter
@Setter
public class PaymentTimingProperties {
    private Duration linkExpiryBuffer = Duration.ofMinutes(1);
    private Duration settlementGrace = Duration.ofMinutes(2);
    private Duration operationLease = Duration.ofSeconds(30);
    private ZoneId transactionZone = ZoneId.of("Asia/Ho_Chi_Minh");
    private int maxCancelAttempts = 8;

    @AssertTrue(
            message =
                    "Payment durations must be nonnegative, operation lease >= 15s, and retry limit"
                            + " positive")
    public boolean isValid() {
        return linkExpiryBuffer != null
                && !linkExpiryBuffer.isNegative()
                && settlementGrace != null
                && !settlementGrace.isNegative()
                && operationLease != null
                && operationLease.compareTo(Duration.ofSeconds(15)) >= 0
                && transactionZone != null
                && maxCancelAttempts > 0;
    }
}
