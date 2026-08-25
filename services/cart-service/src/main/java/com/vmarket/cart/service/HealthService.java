package com.vmarket.cart.service;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.vmarket.cart.dto.HealthResponse;

@Service
public class HealthService {

	private final String serviceName;

	public HealthService(@Value("${spring.application.name}") String serviceName) {
		this.serviceName = serviceName;
	}

	public HealthResponse getHealth() {
		return HealthResponse.builder()
				.status("UP")
				.service(serviceName)
				.timestamp(Instant.now())
				.build();
	}
}