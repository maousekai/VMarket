package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vmarket.product.dto.InventoryRequest;
import com.vmarket.product.event.ProductEventPublisher;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.model.InventoryReservation;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.model.ProductVariant;
import com.vmarket.product.model.ReservationStatus;
import com.vmarket.product.repository.InventoryReservationRepository;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.service.InventoryService;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {
	@Mock ProductRepository productRepository;
	@Mock InventoryReservationRepository reservationRepository;
	@Mock ProductEventPublisher publisher;
	private InventoryService service;

	@BeforeEach
	void setUp() {
		service = new InventoryService(productRepository, reservationRepository, publisher);
	}

	@Test
	void reserveHoldsStockAndPublishesEvent() {
		Product product = product(10, 2);
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.empty());
		when(productRepository.findById("product-1")).thenReturn(Optional.of(product));
		when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		var response = service.reserve(request(3));

		assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
		assertThat(product.getVariants().get(0).getReservedStock()).isEqualTo(5);
		verify(productRepository).saveAll(any());
		verify(publisher).publishStockReserved(any());
	}

	@Test
	void reserveRejectsInsufficientAvailableStockWithoutWriting() {
		Product product = product(10, 8);
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.empty());
		when(productRepository.findById("product-1")).thenReturn(Optional.of(product));

		assertThatThrownBy(() -> service.reserve(request(3)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("Không đủ tồn kho");
		verify(productRepository, never()).saveAll(any());
	}

	@Test
	void reserveIsIdempotentForSameOrderAndItems() {
		InventoryReservation reservation = reservation(ReservationStatus.RESERVED, 3);
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.of(reservation));

		var response = service.reserve(request(3));

		assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
		verify(productRepository, never()).findById(any());
		verify(publisher, never()).publishStockReserved(any());
	}

	@Test
	void confirmDeductsPhysicalAndReservedStockOnce() {
		Product product = product(10, 3);
		InventoryReservation reservation = reservation(ReservationStatus.RESERVED, 3);
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.of(reservation));
		when(productRepository.findById("product-1")).thenReturn(Optional.of(product));
		when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		var response = service.confirm("order-1");

		ProductVariant variant = product.getVariants().get(0);
		assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(variant.getStock()).isEqualTo(7);
		assertThat(variant.getReservedStock()).isZero();
		assertThat(product.getSoldCount()).isEqualTo(3);
	}

	@Test
	void releaseReturnsReservedStockAndPublishesEvent() {
		Product product = product(10, 3);
		InventoryReservation reservation = reservation(ReservationStatus.RESERVED, 3);
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.of(reservation));
		when(productRepository.findById("product-1")).thenReturn(Optional.of(product));
		when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		var response = service.release("order-1");

		assertThat(response.status()).isEqualTo(ReservationStatus.RELEASED);
		assertThat(product.getVariants().get(0).getReservedStock()).isZero();
		assertThat(product.getVariants().get(0).getStock()).isEqualTo(10);
		verify(publisher).publishStockReleased(any());
	}

	private InventoryRequest request(int quantity) {
		return new InventoryRequest("order-1", List.of(new InventoryRequest.InventoryItem("product-1", "variant-1", quantity)));
	}

	private InventoryReservation reservation(ReservationStatus status, int quantity) {
		return new InventoryReservation("reservation-1", "order-1",
				List.of(new InventoryReservation.ReservationItem("product-1", "variant-1", quantity)),
				status, Instant.now(), Instant.now());
	}

	private Product product(long stock, long reserved) {
		Product product = new Product();
		product.setId("product-1");
		product.setStatus(ProductStatus.ACTIVE);
		product.setVariants(new ArrayList<>(List.of(new ProductVariant("variant-1", "SKU", new HashMap<>(),
				BigDecimal.TEN, stock, reserved, 0))));
		return product;
	}
}
