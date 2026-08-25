package com.vmarket.shop.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.shop.dto.HealthResponse;
import com.vmarket.shop.service.HealthService;

@RestController
@RequestMapping("/api/shops")
public class HealthController {

	private final HealthService healthService;

	public HealthController(HealthService healthService) {
		this.healthService = healthService;
	}

	@GetMapping("/health")
	public HealthResponse health() {
		return healthService.getHealth();
	}
}