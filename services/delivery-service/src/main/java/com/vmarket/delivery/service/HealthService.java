package com.vmarket.delivery.service;

import com.vmarket.delivery.dto.HealthResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

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
