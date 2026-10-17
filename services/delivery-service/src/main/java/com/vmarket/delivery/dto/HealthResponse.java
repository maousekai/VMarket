package com.vmarket.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

@Getter
@Builder
@AllArgsConstructor
public class HealthResponse {
    private final String status;
    private final String service;
    private final Instant timestamp;
}
