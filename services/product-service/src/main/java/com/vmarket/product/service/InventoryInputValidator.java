package com.vmarket.product.service;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.vmarket.product.dto.InventoryRequest;
import com.vmarket.product.exception.ApiException;

import jakarta.validation.Validator;

/** Dùng chung Bean Validation cho cả REST và event consumer. */
@Component
public class InventoryInputValidator {
	private final Validator validator;

	public InventoryInputValidator(Validator validator) {
		this.validator = validator;
	}

	public void validate(InventoryRequest request) {
		if (request == null) throw invalid("Payload tồn kho không được để trống");
		var violations = validator.validate(request);
		if (!violations.isEmpty()) {
			String message = violations.stream().map(item -> item.getPropertyPath() + " " + item.getMessage())
					.sorted().collect(Collectors.joining("; "));
			throw invalid(message);
		}
	}

	private ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INVENTORY_REQUEST", message);
	}
}
