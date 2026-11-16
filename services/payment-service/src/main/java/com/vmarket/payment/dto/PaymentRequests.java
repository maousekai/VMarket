package com.vmarket.payment.dto;

import jakarta.validation.constraints.*;

public final class PaymentRequests {
    private PaymentRequests() {}

    public record Create(@NotBlank @Pattern(regexp = "[0-9A-HJKMNP-TV-Z]{26}") String orderId) {}

    public record Refund(@NotBlank @Size(max = 500) String reason) {}

    public record Confirm(@NotBlank @Size(max = 128) String transferReference) {}
}
