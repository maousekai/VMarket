package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.vmarket.product.dto.InventoryRequest;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.service.InventoryInputValidator;

import jakarta.validation.Validation;

class InventoryInputValidatorTest {
	private final InventoryInputValidator validator = new InventoryInputValidator(
			Validation.buildDefaultValidatorFactory().getValidator());

	@Test
	void rejectsNullItemsAndNonPositiveQuantityFromAnyCaller() {
		assertThatThrownBy(() -> validator.validate(new InventoryRequest("order-1", null)))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> validator.validate(new InventoryRequest("order-1",
				List.of(new InventoryRequest.InventoryItem("p1", "v1", 0)))))
				.isInstanceOf(ApiException.class);
	}
}
