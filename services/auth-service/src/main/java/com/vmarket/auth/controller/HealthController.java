package com.vmarket.auth.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.auth.dto.HealthResponse;
import com.vmarket.auth.service.HealthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Health", description = "Kiểm tra tình trạng service")
@RestController
@RequestMapping("/api/auth")
public class HealthController {

	private final HealthService healthService;

	public HealthController(HealthService healthService) {
		this.healthService = healthService;
	}

	@Operation(summary = "Kiểm tra tình trạng auth-service",
			description = "Endpoint public, không cần xác thực. Dùng cho health check / load balancer.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Service đang hoạt động",
					content = @Content(schema = @Schema(implementation = HealthResponse.class))),
	})
	@GetMapping("/health")
	public HealthResponse health() {
		return healthService.getHealth();
	}
}