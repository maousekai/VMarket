package com.vmarket.notification.dto;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class HealthResponse {
	private final String status;
	private final String service;
	private final Instant timestamp;
}