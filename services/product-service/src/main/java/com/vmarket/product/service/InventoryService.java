package com.vmarket.product.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.events.StockItem;
import com.vmarket.events.StockReleased;
import com.vmarket.events.StockReserved;
import com.vmarket.product.dto.InventoryRequest;
import com.vmarket.product.dto.InventoryResponse;
import com.vmarket.product.exception.ApiException;
import com.vmarket.product.event.ProductEventPublisher;
import com.vmarket.product.event.ProductEventFactory;
import com.vmarket.product.model.InventoryReservation;
import com.vmarket.product.model.InventoryReservation.ReservationItem;
import com.vmarket.product.model.Product;
import com.vmarket.product.model.ProductStatus;
import com.vmarket.product.model.ProductVariant;
import com.vmarket.product.model.ReservationStatus;
import com.vmarket.product.model.ReturnRestock;
import com.vmarket.product.repository.InventoryReservationRepository;
import com.vmarket.product.repository.ProductRepository;
import com.vmarket.product.repository.ReturnRestockRepository;

@Service
public class InventoryService {
	private final ProductRepository productRepository;
	private final InventoryReservationRepository reservationRepository;
	private final ProductEventPublisher publisher;
	private final ProductEventFactory eventFactory;
	private final ProductDerivedFields derivedFields;
	private final ReturnRestockRepository returnRestockRepository;
	private final InventoryInputValidator inputValidator;

	public InventoryService(ProductRepository productRepository,
			InventoryReservationRepository reservationRepository, ProductEventPublisher publisher,
			ProductEventFactory eventFactory, ProductDerivedFields derivedFields,
			ReturnRestockRepository returnRestockRepository, InventoryInputValidator inputValidator) {
		this.productRepository = productRepository;
		this.reservationRepository = reservationRepository;
		this.publisher = publisher;
		this.eventFactory = eventFactory;
		this.derivedFields = derivedFields;
		this.returnRestockRepository = returnRestockRepository;
		this.inputValidator = inputValidator;
	}

	@Transactional
	public InventoryResponse reserve(InventoryRequest request) {
		inputValidator.validate(request);
		List<InventoryRequest.InventoryItem> items = normalize(request.items());
		var existing = reservationRepository.findByOrderId(request.orderId());
		if (existing.isPresent()) {
			InventoryReservation reservation = existing.get();
			if (reservation.getStatus() == ReservationStatus.RESERVED && sameItems(reservation, items)) {
				return toResponse(reservation);
			}
			throw conflict("Đơn hàng đã có giao dịch tồn kho ở trạng thái " + reservation.getStatus());
		}

		Map<String, Product> products = loadAndValidate(items, true);
		Instant now = Instant.now();
		InventoryReservation reservation = new InventoryReservation(null, request.orderId(), items.stream()
				.map(item -> new ReservationItem(item.productId(), item.variantId(), item.quantity())).toList(),
				ReservationStatus.RESERVED, now, now);
		InventoryReservation saved = reservationRepository.save(reservation);

		for (InventoryRequest.InventoryItem item : items) {
			Product product = products.get(item.productId());
			ProductVariant variant = findVariant(product, item.variantId());
			variant.setReservedStock(variant.getReservedStock() + item.quantity());
			derivedFields.refresh(product);
			product.setUpdatedAt(Instant.now());
		}
		List<Product> changedProducts = productRepository.saveAll(products.values());
		changedProducts.forEach(product -> publisher.publishUpdated(eventFactory.updated(product)));
		publisher.publishStockReserved(new StockReserved(saved.getOrderId(), toEventItems(saved)));
		return toResponse(saved);
	}

	@Transactional
	public InventoryResponse confirm(String orderId) {
		InventoryReservation reservation = getReservation(orderId);
		if (reservation.getStatus() == ReservationStatus.CONFIRMED) return toResponse(reservation);
		if (reservation.getStatus() != ReservationStatus.RESERVED) {
			throw conflict("Chỉ có thể xác nhận tồn kho đang được tạm giữ");
		}
		List<InventoryRequest.InventoryItem> items = fromReservation(reservation);
		Map<String, Product> products = loadAndValidate(items, false);
		for (InventoryRequest.InventoryItem item : items) {
			Product product = products.get(item.productId());
			ProductVariant variant = findVariant(product, item.variantId());
			if (variant.getReservedStock() < item.quantity() || variant.getStock() < item.quantity()) {
				throw conflict("Dữ liệu tồn kho tạm giữ không còn nhất quán");
			}
			variant.setReservedStock(variant.getReservedStock() - item.quantity());
			variant.setStock(variant.getStock() - item.quantity());
			variant.setSoldCount(variant.getSoldCount() + item.quantity());
			product.setSoldCount(product.getSoldCount() + item.quantity());
			derivedFields.refresh(product);
			product.setUpdatedAt(Instant.now());
		}
		List<Product> changedProducts = productRepository.saveAll(products.values());
		changedProducts.forEach(product -> publisher.publishUpdated(eventFactory.updated(product)));
		reservation.setStatus(ReservationStatus.CONFIRMED);
		reservation.setUpdatedAt(Instant.now());
		return toResponse(reservationRepository.save(reservation));
	}

	@Transactional
	public InventoryResponse release(String orderId) {
		InventoryReservation reservation = getReservation(orderId);
		if (reservation.getStatus() == ReservationStatus.RELEASED) return toResponse(reservation);
		ReservationStatus previousStatus = reservation.getStatus();
		List<InventoryRequest.InventoryItem> items = fromReservation(reservation);
		Map<String, Product> products = loadAndValidate(items, false);
		for (InventoryRequest.InventoryItem item : items) {
			Product product = products.get(item.productId());
			ProductVariant variant = findVariant(product, item.variantId());
			if (previousStatus == ReservationStatus.RESERVED) {
				if (variant.getReservedStock() < item.quantity()) {
					throw conflict("Dữ liệu tồn kho tạm giữ không còn nhất quán");
				}
				variant.setReservedStock(variant.getReservedStock() - item.quantity());
			} else if (previousStatus == ReservationStatus.CONFIRMED) {
				variant.setStock(variant.getStock() + item.quantity());
				variant.setSoldCount(Math.max(0, variant.getSoldCount() - item.quantity()));
				product.setSoldCount(Math.max(0, product.getSoldCount() - item.quantity()));
			} else {
				throw conflict("Trạng thái giao dịch tồn kho không hợp lệ");
			}
			derivedFields.refresh(product);
			product.setUpdatedAt(Instant.now());
		}
		List<Product> changedProducts = productRepository.saveAll(products.values());
		changedProducts.forEach(product -> publisher.publishUpdated(eventFactory.updated(product)));
		reservation.setStatus(ReservationStatus.RELEASED);
		reservation.setUpdatedAt(Instant.now());
		InventoryReservation saved = reservationRepository.save(reservation);
		publisher.publishStockReleased(new StockReleased(saved.getOrderId(), toEventItems(saved)));
		return toResponse(saved);
	}

	@Transactional
	public void restockReturn(String returnId, String orderId, List<StockItem> returnedItems) {
		InventoryRequest request = new InventoryRequest(orderId, returnedItems == null ? null : returnedItems.stream()
				.map(item -> new InventoryRequest.InventoryItem(item.productId(), item.variantId(), item.quantity())).toList());
		inputValidator.validate(request);
		if (returnId == null || returnId.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RETURN", "returnId không được để trống");
		}
		if (returnRestockRepository.existsByReturnId(returnId)) return;
		InventoryReservation reservation = getReservation(orderId);
		if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
			throw conflict("Chỉ có thể nhập lại hàng từ đơn đã chốt tồn kho");
		}
		List<InventoryRequest.InventoryItem> items = normalize(request.items());
		validateReturnQuantities(reservation, items);
		Map<String, Product> products = loadAndValidate(items, false);
		for (InventoryRequest.InventoryItem item : items) {
			Product product = products.get(item.productId());
			ProductVariant variant = findVariant(product, item.variantId());
			try {
				variant.setStock(Math.addExact(variant.getStock(), item.quantity()));
			} catch (ArithmeticException ex) {
				throw new ApiException(HttpStatus.CONFLICT, "STOCK_OVERFLOW",
						"Tồn kho vượt giới hạn cho phép");
			}
			variant.setSoldCount(Math.max(0, variant.getSoldCount() - item.quantity()));
			product.setSoldCount(Math.max(0, product.getSoldCount() - item.quantity()));
			derivedFields.refresh(product);
			product.setUpdatedAt(Instant.now());
		}
		List<Product> changedProducts = productRepository.saveAll(products.values());
		changedProducts.forEach(product -> publisher.publishUpdated(eventFactory.updated(product)));
		returnRestockRepository.save(new ReturnRestock(null, returnId.trim(), orderId.trim(), items.stream()
				.map(item -> new ReservationItem(item.productId(), item.variantId(), item.quantity())).toList(), Instant.now()));
		publisher.publishStockReleased(new StockReleased(orderId, items.stream()
				.map(item -> new StockItem(item.productId(), item.variantId(), item.quantity())).toList()));
	}

	private Map<String, Product> loadAndValidate(List<InventoryRequest.InventoryItem> items, boolean checkAvailable) {
		Map<String, Product> products = new LinkedHashMap<>();
		for (InventoryRequest.InventoryItem item : items) {
			Product product = products.computeIfAbsent(item.productId(), id -> productRepository.findById(id)
					.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Không tìm thấy sản phẩm " + id)));
			if (checkAvailable && (product.getStatus() != ProductStatus.ACTIVE || product.isModerationRemoved()
					|| product.isShopSuspended() || !product.isCategoryVisible() || product.getDeletedAt() != null)) {
				throw conflict("Sản phẩm không ở trạng thái đang bán: " + product.getId());
			}
			ProductVariant variant = findVariant(product, item.variantId());
			if (checkAvailable && variant.getStock() - variant.getReservedStock() < item.quantity()) {
				throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
						"Không đủ tồn kho cho biến thể " + item.variantId());
			}
		}
		return products;
	}

	private ProductVariant findVariant(Product product, String variantId) {
		return product.getVariants().stream().filter(variant -> variantId.equals(variant.getId())).findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VARIANT_NOT_FOUND",
						"Không tìm thấy biến thể " + variantId));
	}

	private List<InventoryRequest.InventoryItem> normalize(List<InventoryRequest.InventoryItem> rawItems) {
		Map<String, InventoryRequest.InventoryItem> normalized = new LinkedHashMap<>();
		try {
			for (InventoryRequest.InventoryItem item : rawItems) {
				String key = item.productId() + "\u0000" + item.variantId();
				normalized.merge(key, item, (left, right) -> new InventoryRequest.InventoryItem(
						left.productId(), left.variantId(), Math.addExact(left.quantity(), right.quantity())));
			}
		} catch (ArithmeticException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INVENTORY_REQUEST",
					"Tổng số lượng vượt giới hạn cho phép");
		}
		return new ArrayList<>(normalized.values());
	}

	private void validateReturnQuantities(InventoryReservation reservation,
			List<InventoryRequest.InventoryItem> requested) {
		Map<String, Long> purchased = new LinkedHashMap<>();
		for (ReservationItem item : reservation.getItems()) {
			purchased.put(item.getProductId() + "\u0000" + item.getVariantId(), (long) item.getQuantity());
		}
		for (ReturnRestock previous : returnRestockRepository.findAllByOrderId(reservation.getOrderId())) {
			for (ReservationItem item : previous.getItems()) {
				String key = item.getProductId() + "\u0000" + item.getVariantId();
				purchased.computeIfPresent(key, (ignored, remaining) -> remaining - item.getQuantity());
			}
		}
		for (InventoryRequest.InventoryItem item : requested) {
			String key = item.productId() + "\u0000" + item.variantId();
			if (purchased.getOrDefault(key, 0L) < item.quantity()) {
				throw new ApiException(HttpStatus.CONFLICT, "INVALID_RETURN_QUANTITY",
						"Số lượng trả vượt số lượng đã mua hoặc đã được nhập lại");
			}
		}
	}

	private InventoryReservation getReservation(String orderId) {
		return reservationRepository.findByOrderId(orderId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND",
						"Không tìm thấy giao dịch giữ tồn kho của đơn hàng"));
	}

	private boolean sameItems(InventoryReservation reservation, List<InventoryRequest.InventoryItem> items) {
		return fromReservation(reservation).equals(items);
	}

	private List<InventoryRequest.InventoryItem> fromReservation(InventoryReservation reservation) {
		return reservation.getItems().stream()
				.map(item -> new InventoryRequest.InventoryItem(item.getProductId(), item.getVariantId(), item.getQuantity())).toList();
	}

	private List<StockItem> toEventItems(InventoryReservation reservation) {
		return reservation.getItems().stream()
				.map(item -> new StockItem(item.getProductId(), item.getVariantId(), item.getQuantity())).toList();
	}

	private InventoryResponse toResponse(InventoryReservation reservation) {
		return new InventoryResponse(reservation.getOrderId(), reservation.getStatus(), fromReservation(reservation));
	}

	private ApiException conflict(String message) {
		return new ApiException(HttpStatus.CONFLICT, "INVENTORY_CONFLICT", message);
	}
}
