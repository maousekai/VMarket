package com.vmarket.payment.dto;
import java.util.Map;
import jakarta.validation.constraints.*;
public final class PaymentRequests {
    private PaymentRequests() {}
    public record Create(@NotBlank @Pattern(regexp = "[0-9A-HJKMNP-TV-Z]{26}") String orderId) {}
    public record Webhook(@NotNull Map<String, Object> data, @NotBlank String signature) {}
    public record Refund(@NotBlank @Size(max = 500) String reason) {}
    public record Confirm(@NotBlank @Size(max = 128) String transferReference) {}
}
