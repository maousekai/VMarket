package com.vmarket.delivery.dto;

import com.vmarket.delivery.domain.DeliveryStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class DeliveryRequests {
    private DeliveryRequests() {}
    public record Account(@NotBlank @Email @Size(max=320) String email,
            @NotBlank @Pattern(regexp="[A-Za-z0-9_.-]{3,50}") String username,
            @NotBlank @Size(min=8,max=32) String password) {
        @Override public String toString() { return "Account[REDACTED]"; }
    }
    public record Shipper(@NotNull @Valid Account account, @NotBlank @Size(max=100) String name,
            @NotBlank @Size(max=20) String phone, @NotBlank @Size(max=100) String province,
            @NotBlank @Size(max=100) String district) {}
    public record Assign(@NotBlank @Size(max=26) String shipperId, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record Revision(@NotNull @PositiveOrZero Long expectedVersion) {}
    public record Lock(@NotNull Boolean locked) {}
    public record Status(@NotNull DeliveryStatus status, @Size(max=255) String reason,
            @PositiveOrZero Long codCollected, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record Location(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}
}
