package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.vmarket.product.event.ProductEventFactory;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.model.InventoryReservation;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.model.ProductVariant;
import com.vmarket.product.model.ReservationStatus;
import com.vmarket.events.StockItem;
import com.vmarket.product.repository.InventoryReservationRepository;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.repository.ReturnRestockRepository;
import com.vmarket.product.service.InventoryService;
import com.vmarket.product.service.InventoryInputValidator;
import com.vmarket.product.service.ProductDerivedFields;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {
	@Mock ProductRepository productRepository;
	@Mock InventoryReservationRepository reservationRepository;
	@Mock ProductEventPublisher publisher;
	@Mock ReturnRestockRepository returnRestockRepository;
	@Mock InventoryInputValidator inputValidator;
	private InventoryService service;

	@BeforeEach
	void setUp() {
		service = new InventoryService(productRepository, reservationRepository, publisher,
				new ProductEventFactory(), new ProductDerivedFields(), returnRestockRepository, inputValidator,
				new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
	}

	@Test
	void reserveHoldsStockAndPublishesEvent() {
		Product product = product(10, 2);
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.empty());
		when(productRepository.findAllById(any())).thenReturn(List.of(product));
		when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(productRepository.saveAll(any())).thenAnswer(invocation -> new ArrayList<>(invocation.getArgument(0)));

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
		when(productRepository.findAllById(any())).thenReturn(List.of(product));

		assertThatThrownBy(() -> service.reserve(request(3)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("Không đủ tồn kho");
		verify(productRepository, never()).saveAll(any());
		verify(reservationRepository, never()).save(any());
	}

	@Test
	void reserveRejectsProductInHiddenCategory() {
		Product product = product(10, 0);
		product.setCategoryVisible(false);
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.empty());
		when(productRepository.findAllById(any())).thenReturn(List.of(product));

		assertThatThrownBy(() -> service.reserve(request(1)))
				.isInstanceOf(ApiException.class).hasMessageContaining("không ở trạng thái đang bán");
	}

	@Test
	void reserveIsIdempotentForSameOrderAndItems() {
		InventoryReservation reservation = reservation(ReservationStatus.RESERVED, 3);
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.of(reservation));

		var response = service.reserve(request(3));

		assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
		verify(productRepository, never()).findAllById(any());
		verify(publisher, never()).publishStockReserved(any());
	}

	@Test
	void confirmDeductsPhysicalAndReservedStockOnce() {
		Product product = product(10, 3);
		InventoryReservation reservation = reservation(ReservationStatus.RESERVED, 3);
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.of(reservation));
		when(productRepository.findAllById(any())).thenReturn(List.of(product));
		when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(productRepository.saveAll(any())).thenAnswer(invocation -> new ArrayList<>(invocation.getArgument(0)));

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
		when(productRepository.findAllById(any())).thenReturn(List.of(product));
		when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(productRepository.saveAll(any())).thenAnswer(invocation -> new ArrayList<>(invocation.getArgument(0)));

		var response = service.release("order-1");

		assertThat(response.status()).isEqualTo(ReservationStatus.RELEASED);
		assertThat(product.getVariants().get(0).getReservedStock()).isZero();
		assertThat(product.getVariants().get(0).getStock()).isEqualTo(10);
		verify(publisher).publishStockReleased(any());
	}

	@Test
	void cancellingConfirmedOrderRestoresPhysicalStockAndSoldCount() {
		Product product = product(7, 0);
		product.setSoldCount(3);
		product.getVariants().get(0).setSoldCount(3);
		InventoryReservation reservation = reservation(ReservationStatus.CONFIRMED, 3);
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.of(reservation));
		when(productRepository.findAllById(any())).thenReturn(List.of(product));
		when(productRepository.saveAll(any())).thenAnswer(invocation -> new ArrayList<>(invocation.getArgument(0)));
		when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		var response = service.release("order-1");

		assertThat(response.status()).isEqualTo(ReservationStatus.RELEASED);
		assertThat(product.getVariants().get(0).getStock()).isEqualTo(10);
		assertThat(product.getVariants().get(0).getSoldCount()).isZero();
		assertThat(product.getSoldCount()).isZero();
	}

	@Test
	void resolvedReturnRestocksOnlyPurchasedQuantityAndIsIdempotent() {
		Product product = product(7, 0);
		product.setSoldCount(3);
		product.getVariants().get(0).setSoldCount(3);
		InventoryReservation reservation = reservation(ReservationStatus.CONFIRMED, 3);
		when(returnRestockRepository.existsByReturnId("return-1")).thenReturn(false);
		when(returnRestockRepository.findAllByOrderId("order-1")).thenReturn(List.of());
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.of(reservation));
		when(productRepository.findAllById(any())).thenReturn(List.of(product));
		when(productRepository.saveAll(any())).thenAnswer(invocation -> new ArrayList<>(invocation.getArgument(0)));

		service.restockReturn("return-1", "order-1", List.of(new StockItem("product-1", "variant-1", 2)));

		assertThat(product.getVariants().get(0).getStock()).isEqualTo(9);
		assertThat(product.getVariants().get(0).getSoldCount()).isEqualTo(1);
		verify(returnRestockRepository).save(any());
		verify(publisher).publishStockReleased(any());
	}

	@Test
	void resolvedReturnCannotRestockMoreThanWasPurchased() {
		InventoryReservation reservation = reservation(ReservationStatus.CONFIRMED, 3);
		when(returnRestockRepository.existsByReturnId("return-1")).thenReturn(false);
		when(returnRestockRepository.findAllByOrderId("order-1")).thenReturn(List.of());
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.of(reservation));

		assertThatThrownBy(() -> service.restockReturn("return-1", "order-1",
				List.of(new StockItem("product-1", "variant-1", 4))))
				.isInstanceOf(ApiException.class).hasMessageContaining("vượt số lượng");
	}

	@Test
	void directReleaseOfUnknownReservationReturnsNotFound() {
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.release("order-1"))
				.isInstanceOf(ApiException.class).hasMessageContaining("Không tìm thấy giao dịch");
		verify(publisher, never()).publishStockReleased(any());
	}

	@Test
	void earlyPaymentIsStoredUntilOrderReservationArrives() {
		InventoryReservation pending = reservation(ReservationStatus.PENDING_CONFIRM, 0);
		pending.setItems(List.of());
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.empty(), Optional.of(pending));
		when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		service.confirmOrDefer("order-1");
		Product product = product(10, 0);
		when(productRepository.findAllById(any())).thenReturn(List.of(product));
		when(productRepository.saveAll(any())).thenAnswer(invocation -> new ArrayList<>(invocation.getArgument(0)));
		assertThat(service.reserve(request(3)).status()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(product.getVariants().get(0).getStock()).isEqualTo(7);
	}

	@Test
	void earlyCancellationPreventsLaterReservation() {
		InventoryReservation pending = reservation(ReservationStatus.PENDING_RELEASE, 0);
		pending.setItems(List.of());
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.empty(), Optional.of(pending));
		when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		service.releaseOrDefer("order-1");
		assertThat(service.reserve(request(3)).status()).isEqualTo(ReservationStatus.RELEASED);
		verify(productRepository, never()).findAllById(any());
	}

	@Test
	void restockReturnBeforeReservationArrivesIsSkippedAndCountedAsOutOfOrder() {
		when(returnRestockRepository.existsByReturnId("return-1")).thenReturn(false);
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.empty());

		service.restockReturn("return-1", "order-1", List.of(new StockItem("product-1", "variant-1", 2)));

		verify(productRepository, never()).findAllById(any());
		verify(returnRestockRepository, never()).save(any());
		verify(publisher, never()).publishStockReleased(any());
	}

	@Test
	void confirmAfterReservationWasReleasedConflicts() {
		InventoryReservation reservation = reservation(ReservationStatus.RELEASED, 3);
		when(reservationRepository.findByOrderId("order-1")).thenReturn(Optional.of(reservation));

		assertThatThrownBy(() -> service.confirm("order-1"))
				.isInstanceOf(ApiException.class).hasMessageContaining("Chỉ có thể xác nhận");
		verify(productRepository, never()).findAllById(any());
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
		product.setCategoryVisible(true);
		product.setVariants(new ArrayList<>(List.of(new ProductVariant("variant-1", "SKU", new HashMap<>(),
				10L, stock, reserved, 0))));
		return product;
	}
}
