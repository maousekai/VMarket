package com.vmarket.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.vmarket.events.ProductUpdated;
import com.vmarket.events.StockItem;
import com.vmarket.events.StockReleased;
import com.vmarket.events.StockReserved;
import com.vmarket.product.dto.InventoryRequest;
import com.vmarket.product.dto.InventoryResponse;
import com.vmarket.product.event.ProductEventFactory;
import com.vmarket.product.event.ProductEventPublisher;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.model.InventoryReservation;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.model.ProductVariant;
import com.vmarket.product.model.ReservationStatus;
import com.vmarket.product.repository.InventoryReservationRepository;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.repository.ReturnRestockRepository;
import com.vmarket.product.service.InventoryInputValidator;
import com.vmarket.product.service.InventoryService;
import com.vmarket.product.service.ProductDerivedFields;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class TV01InventorySheetTest {

	private static final String ORDER = "order-1";
	private static final String PRODUCT = "product-1";
	private static final String VARIANT = "variant-1";
	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
	private ProductRepository products;
	private InventoryReservationRepository reservations;
	private ReturnRestockRepository returns;
	private ProductEventPublisher publisher;
	private InventoryInputValidator validator;
	private SimpleMeterRegistry metrics;
	private InventoryService service;
	private Product product;
	private InventoryReservation reservation;

	@BeforeEach
	void setUp() {
		products = mock(ProductRepository.class);
		reservations = mock(InventoryReservationRepository.class);
		returns = mock(ReturnRestockRepository.class);
		publisher = mock(ProductEventPublisher.class);
		validator = mock(InventoryInputValidator.class);
		metrics = new SimpleMeterRegistry();
		service = new InventoryService(products, reservations, publisher, new ProductEventFactory(),
				new ProductDerivedFields(), returns, validator, metrics);
		when(reservations.findByOrderId(ORDER)).thenAnswer(inv -> Optional.ofNullable(reservation));
	}

	@AfterEach
	void recordStateAndCloseRegistry() {
		System.out.println("inventory.fixture.after=" + product + "; reservation.after=" + reservation);
		metrics.close();
	}

	@Test
	@DisplayName("UT-STK-01 — Reserve hợp lệ")
	void ut_STK_01() {
		givenProduct(10, 2, 0);
		givenProductLookup();
		givenWrites();
		var response = service.reserve(request(3));
		assertResponse(response, ReservationStatus.RESERVED, 3);
		assertState(10, 5, 0);
		assertSaved(ReservationStatus.RESERVED, 3, 10, 5, 0);
		assertReservedEvent(3);
		assertUpdatedEvent(5, 0);
		verifyNoMoreInteractions(publisher);
		verify(validator).validate(request(3));
	}

	@ParameterizedTest(name = "UT-STK-02 requested={0} available=2")
	@ValueSource(ints = {2, 3})
	@DisplayName("UT-STK-02 — Biên đủ hàng và thiếu 1")
	void ut_STK_02(int quantity) {
		givenProduct(10, 8, 0);
		givenProductLookup();
		if (quantity == 2) {
			givenWrites();
			assertResponse(service.reserve(request(quantity)), ReservationStatus.RESERVED, quantity);
			assertState(10, 10, 0);
			assertSaved(ReservationStatus.RESERVED, 2, 10, 10, 0);
			assertReservedEvent(2);
			assertUpdatedEvent(0, 0);
			verifyNoMoreInteractions(publisher);
		} else {
			String before = product.toString();
			assertError(() -> service.reserve(request(quantity)), "INSUFFICIENT_STOCK", 409);
			assertThat(product.toString()).isEqualTo(before);
			assertState(10, 8, 0);
			assertNoWritesOrEvents();
		}
	}

	@Test
	@DisplayName("UT-STK-03 — Reserve lặp cùng order")
	void ut_STK_03() {
		givenProduct(10, 3, 0);
		givenReservation(ReservationStatus.RESERVED);
		String before = reservation.toString();
		var response = service.reserve(request(3));
		assertResponse(response, ReservationStatus.RESERVED, 3);
		assertThat(reservation.toString()).isEqualTo(before);
		assertState(10, 3, 0);
		assertNoWritesOrEvents();
		verifyNoInteractions(products);
	}

	@ParameterizedTest(name = "UT-STK-04 hiddenCategory={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("UT-STK-04 — Sản phẩm không được bán")
	void ut_STK_04(boolean hiddenCategory) {
		givenProduct(10, 0, 0);
		product.setCategoryVisible(!hiddenCategory);
		variant().setDeleted(!hiddenCategory);
		givenProductLookup();
		String before = product.toString();
		assertError(() -> service.reserve(request(1)),
				hiddenCategory ? "INVENTORY_CONFLICT" : "VARIANT_NOT_FOUND", hiddenCategory ? 409 : 404);
		assertThat(product.toString()).isEqualTo(before);
		assertState(10, 0, 0);
		assertNoWritesOrEvents();
	}

	@Test
	@DisplayName("UT-STK-05 — Confirm một lần")
	void ut_STK_05() {
		givenProduct(10, 3, 0);
		givenReservation(ReservationStatus.RESERVED);
		givenProductLookup();
		givenWrites();
		when(returns.findAllByOrderIdAndPendingTrue(ORDER)).thenReturn(List.of());
		var response = service.confirm(ORDER);
		assertResponse(response, ReservationStatus.CONFIRMED, 3);
		assertState(7, 0, 3);
		assertSaved(ReservationStatus.CONFIRMED, 3, 7, 0, 3);
		assertUpdatedEvent(7, 3);
		verifyNoMoreInteractions(publisher);
		verify(returns).findAllByOrderIdAndPendingTrue(ORDER);
		verifyNoMoreInteractions(returns);
	}

	@Test
	@DisplayName("UT-STK-06 — Confirm lặp")
	void ut_STK_06() {
		givenProduct(7, 0, 3);
		givenReservation(ReservationStatus.CONFIRMED);
		when(returns.findAllByOrderIdAndPendingTrue(ORDER)).thenReturn(List.of());
		String before = reservation.toString();
		assertResponse(service.confirm(ORDER), ReservationStatus.CONFIRMED, 3);
		assertState(7, 0, 3);
		assertThat(reservation.toString()).isEqualTo(before);
		assertNoWritesOrEvents();
		verifyNoInteractions(products);
		verify(returns).findAllByOrderIdAndPendingTrue(ORDER);
		verifyNoMoreInteractions(returns);
	}

	@Test
	@DisplayName("UT-STK-07 — Release reservation")
	void ut_STK_07() {
		givenProduct(10, 3, 0);
		givenReservation(ReservationStatus.RESERVED);
		givenProductLookup();
		givenWrites();
		assertResponse(service.release(ORDER), ReservationStatus.RELEASED, 3);
		assertState(10, 0, 0);
		assertSaved(ReservationStatus.RELEASED, 3, 10, 0, 0);
		assertReleasedEvent();
		assertUpdatedEvent(10, 0);
		verifyNoMoreInteractions(publisher);
		String before = reservation.toString();
		clearInvocations(products, reservations, publisher);
		assertResponse(service.release(ORDER), ReservationStatus.RELEASED, 3);
		assertState(10, 0, 0);
		assertThat(reservation.toString()).isEqualTo(before);
		assertNoWritesOrEvents();
		verifyNoInteractions(products);
	}

	@Test
	@DisplayName("UT-STK-08 — Hủy đơn đã chốt")
	void ut_STK_08() {
		givenProduct(7, 0, 3);
		givenReservation(ReservationStatus.CONFIRMED);
		givenProductLookup();
		givenWrites();
		assertResponse(service.release(ORDER), ReservationStatus.RELEASED, 3);
		assertState(10, 0, 0);
		assertSaved(ReservationStatus.RELEASED, 3, 10, 0, 0);
		assertReleasedEvent();
		assertUpdatedEvent(10, 0);
		verifyNoMoreInteractions(publisher);
	}

	private void givenProduct(long stock, long reserved, long sold) {
		product = new Product();
		product.setId(PRODUCT);
		product.setShopId("shop-1");
		product.setCategoryId("category-unit-1");
		product.setStatus(ProductStatus.ACTIVE);
		product.setCategoryVisible(true);
		product.setSoldCount(sold);
		product.setVariants(new ArrayList<>(List.of(new ProductVariant(VARIANT, "SKU-V1",
				Map.of("color", "red", "size", "M"), 100000L, stock, reserved, sold))));
		product.setCreatedAt(T0);
		product.setUpdatedAt(T0);
		new ProductDerivedFields().refresh(product);
		System.out.println("inventory.fixture.before=" + product);
	}

	private void givenReservation(ReservationStatus status) {
		reservation = new InventoryReservation("reservation-1", ORDER,
				List.of(new InventoryReservation.ReservationItem(PRODUCT, VARIANT, 3)), status, T0, T0);
		System.out.println("reservation.before=" + reservation);
	}

	private void givenProductLookup() {
		when(products.findAllById(List.of(PRODUCT))).thenReturn(List.of(product));
	}

	private void givenWrites() {
		when(reservations.save(any())).thenAnswer(inv -> {
			reservation = inv.getArgument(0);
			return reservation;
		});
		when(products.saveAll(any())).thenAnswer(inv -> {
			List<Product> saved = new ArrayList<>();
			((Iterable<Product>) inv.getArgument(0)).forEach(saved::add);
			return saved;
		});
	}

	private void assertState(long stock, long reserved, long sold) {
		assertThat(variant().getStock()).isEqualTo(stock);
		assertThat(variant().getReservedStock()).isEqualTo(reserved);
		assertThat(variant().getSoldCount()).isEqualTo(sold);
		assertThat(product.getSoldCount()).isEqualTo(sold);
	}

	@SuppressWarnings("unchecked")
	private void assertSaved(ReservationStatus status, int quantity, long stock, long reserved, long sold) {
		ArgumentCaptor<Iterable<Product>> savedProducts = ArgumentCaptor.forClass(Iterable.class);
		verify(products).saveAll(savedProducts.capture());
		verify(products, never()).save(any());
		assertThat(savedProducts.getValue()).singleElement().isSameAs(product);
		assertState(stock, reserved, sold);
		var savedReservation = ArgumentCaptor.forClass(InventoryReservation.class);
		verify(reservations).save(savedReservation.capture());
		var saved = savedReservation.getValue();
		assertThat(saved.getOrderId()).isEqualTo(ORDER);
		assertThat(saved.getStatus()).isEqualTo(status);
		assertThat(saved.getItems()).singleElement().satisfies(item -> {
			assertThat(item.getProductId()).isEqualTo(PRODUCT);
			assertThat(item.getVariantId()).isEqualTo(VARIANT);
			assertThat(item.getQuantity()).isEqualTo(quantity);
		});
		System.out.println("saved.product=" + savedProducts.getValue() + "; saved.reservation=" + saved);
	}

	private void assertResponse(InventoryResponse response, ReservationStatus status, int quantity) {
		assertThat(response.orderId()).isEqualTo(ORDER);
		assertThat(response.status()).isEqualTo(status);
		assertThat(response.items()).containsExactly(new InventoryRequest.InventoryItem(PRODUCT, VARIANT, quantity));
		System.out.println("inventory.response=" + response);
	}

	private void assertNoWritesOrEvents() {
		verify(products, never()).save(any());
		verify(products, never()).saveAll(any());
		verify(reservations, never()).save(any());
		verifyNoInteractions(publisher);
	}

	private void assertError(Runnable action, String code, int status) {
		var error = catchThrowableOfType(ApiException.class, action::run);
		assertThat(error).isNotNull();
		assertThat(error.getCode()).isEqualTo(code);
		assertThat(error.getStatus().value()).isEqualTo(status);
		System.out.println("inventory.error=" + error.getCode() + "; status=" + error.getStatus().value());
	}

	private void assertReservedEvent(int quantity) {
		var capture = ArgumentCaptor.forClass(StockReserved.class);
		verify(publisher).publishStockReserved(capture.capture());
		assertThat(capture.getValue()).isEqualTo(new StockReserved(ORDER, List.of(new StockItem(PRODUCT, VARIANT, quantity))));
		System.out.println("event.actual=" + capture.getValue());
	}

	private void assertReleasedEvent() {
		var capture = ArgumentCaptor.forClass(StockReleased.class);
		verify(publisher).publishStockReleased(capture.capture());
		assertThat(capture.getValue()).isEqualTo(new StockReleased(ORDER, List.of(new StockItem(PRODUCT, VARIANT, 3))));
		System.out.println("event.actual=" + capture.getValue());
	}

	private void assertUpdatedEvent(long available, long sold) {
		var capture = ArgumentCaptor.forClass(ProductUpdated.class);
		verify(publisher).publishUpdated(capture.capture());
		var event = capture.getValue();
		assertThat(event.productId()).isEqualTo(PRODUCT);
		assertThat(event.shopId()).isEqualTo("shop-1");
		assertThat(event.availableStock()).isEqualTo(available);
		assertThat(event.soldCount()).isEqualTo(sold);
		assertThat(event.variants()).singleElement().satisfies(item -> {
			assertThat(item.variantId()).isEqualTo(VARIANT);
			assertThat(item.availableStock()).isEqualTo(available);
			assertThat(item.soldCount()).isEqualTo(sold);
		});
		System.out.println("event.actual=" + event);
	}

	private ProductVariant variant() { return product.getVariants().get(0); }
	private static InventoryRequest request(int quantity) {
		return new InventoryRequest(ORDER, List.of(new InventoryRequest.InventoryItem(PRODUCT, VARIANT, quantity)));
	}
}
