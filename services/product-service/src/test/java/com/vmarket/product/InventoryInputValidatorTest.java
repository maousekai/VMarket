package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.vmarket.product.dto.InventoryRequest;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.service.InventoryInputValidator;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

class InventoryInputValidatorTest {
	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private final InventoryInputValidator validator = new InventoryInputValidator(
			FACTORY.getValidator());

	@AfterAll
	static void closeValidatorFactory() {
		FACTORY.close();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRequests")
	void rejectsInvalidRequests(String dataset, InventoryRequest request) {
		assertThatThrownBy(() -> validator.validate(request))
				.isInstanceOfSatisfying(ApiException.class, error -> {
					assertThat(error.getCode()).isEqualTo("INVALID_INVENTORY_REQUEST");
					assertThat(error.getStatus().value()).isEqualTo(400);
				});
	}

	static Stream<Arguments> invalidRequests() {
		var validItems = List.of(new InventoryRequest.InventoryItem("p1", "v1", 1));
		return Stream.of(
				Arguments.of("null payload", null),
				Arguments.of("null order", new InventoryRequest(null, validItems)),
				Arguments.of("empty order", new InventoryRequest("", validItems)),
				Arguments.of("blank order", new InventoryRequest(" ", validItems)),
				Arguments.of("null items", new InventoryRequest("order-1", null)),
				Arguments.of("empty items", new InventoryRequest("order-1", List.of())),
				Arguments.of("null product", request(null, "v1", 1)),
				Arguments.of("empty product", request("", "v1", 1)),
				Arguments.of("blank product", request(" ", "v1", 1)),
				Arguments.of("null variant", request("p1", null, 1)),
				Arguments.of("empty variant", request("p1", "", 1)),
				Arguments.of("blank variant", request("p1", " ", 1)),
				Arguments.of("zero quantity", request("p1", "v1", 0)),
				Arguments.of("negative quantity", request("p1", "v1", -1)));
	}

	@ParameterizedTest
	@ValueSource(ints = {1, Integer.MAX_VALUE})
	void acceptsPositiveQuantityBoundaries(int quantity) {
		assertThatCode(() -> validator.validate(request("p1", "v1", quantity))).doesNotThrowAnyException();
	}

	@Test
	void duplicateItemsAreValidInputsForServiceNormalization() {
		var item = new InventoryRequest.InventoryItem("p1", "v1", 2);
		assertThatCode(() -> validator.validate(new InventoryRequest("order-1", List.of(item, item))))
				.doesNotThrowAnyException();
	}

	private static InventoryRequest request(String product, String variant, int quantity) {
		return new InventoryRequest("order-1",
				List.of(new InventoryRequest.InventoryItem(product, variant, quantity)));
	}
}
