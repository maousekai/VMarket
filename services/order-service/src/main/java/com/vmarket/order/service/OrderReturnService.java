package com.vmarket.order.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.events.EventType;
import com.vmarket.events.ReturnRequested;
import com.vmarket.events.ReturnResolved;
import com.vmarket.events.StockItem;
import com.vmarket.order.dto.AdminResolveReturnRequest;
import com.vmarket.order.dto.CreateReturnRequest;
import com.vmarket.order.dto.ReturnItemRequest;
import com.vmarket.order.dto.ReturnResponse;
import com.vmarket.order.entity.Order;
import com.vmarket.order.entity.OrderItem;
import com.vmarket.order.entity.OrderReturn;
import com.vmarket.order.entity.OrderReturnItem;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.entity.ReturnStatus;
import com.vmarket.order.event.Outbox;
import com.vmarket.order.exception.ApiException;
import com.vmarket.order.repository.OrderRepository;
import com.vmarket.order.repository.OrderReturnRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Xử lý yêu cầu trả hàng và hoàn tiền (FR-ORD-06, FR-ORD-07).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderReturnService {

	private final Clock clock;
	private final OrderRepository orderRepository;
	private final OrderReturnRepository returnRepository;
	private final SellerOrderService sellerOrderService;
	private final Outbox outbox;
	private final ObjectMapper json;

	/**
	 * Buyer tạo yêu cầu trả hàng trong vòng 7 ngày kể từ khi nhận hàng (FR-ORD-06).
	 */
	@Transactional
	public ReturnResponse createReturn(String userId, String orderId, CreateReturnRequest request) {
		// Khóa đơn hàng với pessimistic lock để chống race condition khi tạo yêu cầu
		// trả hàng đồng thời
		Order order = orderRepository.lock(orderId)
				.filter(o -> userId.equals(o.getUserId()))
				.orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));

		if (order.getStatus() != OrderStatus.DELIVERED) {
			throw ApiException.conflict("ORDER_NOT_RETURNABLE",
					"Chỉ có thể yêu cầu trả hàng khi đơn đã được giao thành công (DELIVERED). Trạng thái hiện tại: "
							+ order.getStatus());
		}

		Instant deliveredAt = order.getDeliveredAt() != null ? order.getDeliveredAt() : order.getUpdatedAt();
		if (clock.instant().isAfter(deliveredAt.plus(Duration.ofDays(7)))) {
			throw ApiException.conflict("RETURN_WINDOW_EXPIRED",
					"Đã quá thời hạn 7 ngày kể từ khi nhận hàng để tạo yêu cầu trả hàng");
		}

		Map<String, OrderItem> orderItemMap = order.getItems().stream()
				.collect(Collectors.toMap(OrderItem::getId, Function.identity()));

		String targetShopId = null;
		BigDecimal totalRefund = BigDecimal.ZERO;
		List<OrderReturnItem> returnItems = new ArrayList<>();
		Set<String> seenItemIds = new HashSet<>();

		for (ReturnItemRequest itemReq : request.items()) {
			if (itemReq.quantity() <= 0) {
				throw ApiException.badRequest("INVALID_QUANTITY",
						"Số lượng trả phải lớn hơn 0: " + itemReq.quantity());
			}

			if (!seenItemIds.add(itemReq.orderItemId())) {
				throw ApiException.badRequest("DUPLICATE_RETURN_ITEM",
						"Mỗi mặt hàng chỉ được xuất hiện một lần trong yêu cầu trả hàng: " + itemReq.orderItemId());
			}

			OrderItem orderItem = orderItemMap.get(itemReq.orderItemId());
			if (orderItem == null) {
				throw ApiException.badRequest("ITEM_NOT_IN_ORDER",
						"Mặt hàng " + itemReq.orderItemId() + " không thuộc đơn hàng này");
			}

			if (returnRepository.isOrderItemAlreadyRequested(orderId, orderItem.getId())) {
				throw ApiException.conflict("ITEM_ALREADY_RETURNED",
						"Mỗi sản phẩm trong đơn chỉ được yêu cầu trả một lần: " + orderItem.getId());
			}

			if (itemReq.quantity() > orderItem.getQuantity()) {
				throw ApiException.badRequest("INVALID_QUANTITY",
						"Số lượng trả (" + itemReq.quantity() + ") vượt quá số lượng đã mua ("
								+ orderItem.getQuantity() + ")");
			}

			if (targetShopId == null) {
				targetShopId = orderItem.getShopId();
			} else if (!targetShopId.equals(orderItem.getShopId())) {
				throw ApiException.badRequest("MULTIPLE_SHOPS_IN_RETURN",
						"Các mặt hàng yêu cầu trả phải thuộc cùng một gian hàng");
			}

			BigDecimal lineTotal = orderItem.getUnitPrice().multiply(BigDecimal.valueOf(itemReq.quantity()));
			totalRefund = totalRefund.add(lineTotal);

			OrderReturnItem returnItem = new OrderReturnItem();
			returnItem.setOrderItemId(orderItem.getId());
			returnItem.setProductId(orderItem.getProductId());
			returnItem.setVariantId(orderItem.getVariantId());
			returnItem.setQuantity(itemReq.quantity());
			returnItem.setUnitPrice(orderItem.getUnitPrice());
			returnItem.setLineTotal(lineTotal);
			returnItems.add(returnItem);
		}

		String evidenceJson = null;
		if (request.evidenceUrls() != null && !request.evidenceUrls().isEmpty()) {
			evidenceJson = json.writeValueAsString(request.evidenceUrls());
		}

		OrderReturn orderReturn = new OrderReturn();
		orderReturn.setOrderId(orderId);
		orderReturn.setUserId(userId);
		orderReturn.setShopId(targetShopId);
		orderReturn.setStatus(ReturnStatus.PENDING);
		orderReturn.setReason(request.reason().trim());
		orderReturn.setEvidenceUrls(evidenceJson);
		orderReturn.setRefundAmount(totalRefund);

		for (OrderReturnItem item : returnItems) {
			item.setOrderReturn(orderReturn);
			orderReturn.getItems().add(item);
		}

		OrderReturn saved = returnRepository.save(orderReturn);

		List<StockItem> stockItems = saved.getItems().stream()
				.map(i -> new StockItem(i.getProductId(), i.getVariantId(), i.getQuantity()))
				.toList();

		outbox.add(
				EventType.RETURN_REQUESTED,
				new ReturnRequested(
						saved.getId(),
						orderId,
						targetShopId,
						userId,
						saved.getReason(),
						stockItems));

		log.info("Buyer userId={} đã tạo yêu cầu trả hàng {} cho đơn {}, hoàn tiền {}",
				userId, saved.getId(), orderId, totalRefund);
		return ReturnResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public List<ReturnResponse> listMyReturns(String userId) {
		return returnRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
				.map(ReturnResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<ReturnResponse> listReturnsForOrder(String userId, String orderId) {
		orderRepository.findByIdAndUserId(orderId, userId)
				.orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));
		return returnRepository.findByOrderId(orderId).stream()
				.map(ReturnResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public ReturnResponse getMyReturn(String userId, String returnId) {
		OrderReturn r = returnRepository.findByIdAndUserId(returnId, userId)
				.orElseThrow(() -> ApiException.notFound("RETURN_NOT_FOUND", "Không tìm thấy yêu cầu trả hàng"));
		return ReturnResponse.from(r);
	}

	/**
	 * Buyer khiếu nại quyết định từ chối của Seller lên Admin phân xử (FR-ORD-07).
	 */
	@Transactional
	public ReturnResponse disputeReturn(String userId, String returnId, String reason) {
		OrderReturn r = returnRepository.findById(returnId)
				.filter(x -> userId.equals(x.getUserId()))
				.orElseThrow(() -> ApiException.notFound("RETURN_NOT_FOUND", "Không tìm thấy yêu cầu trả hàng"));

		Order order = orderRepository.lock(r.getOrderId())
				.orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng"));

		if (order.getStatus() == OrderStatus.COMPLETED) {
			throw ApiException.conflict("ORDER_ALREADY_COMPLETED",
					"Không thể khiếu nại yêu cầu trả hàng của đơn đã hoàn tất.");
		}

		r = returnRepository.lock(returnId).orElseThrow();

		if (r.getStatus() == ReturnStatus.ADMIN_REJECTED) {
			throw ApiException.conflict("CANNOT_DISPUTE_ADMIN_DECISION",
					"Quyết định của Quản trị viên là phán quyết cuối cùng, không thể khiếu nại lại.");
		}

		if (r.getStatus() != ReturnStatus.REJECTED) {
			throw ApiException.conflict("INVALID_RETURN_STATUS",
					"Chỉ có thể khiếu nại yêu cầu đã bị người bán từ chối (REJECTED). Trạng thái hiện tại: "
							+ r.getStatus());
		}

		r.setStatus(ReturnStatus.DISPUTED);
		r.setAdminNote("Buyer khiếu nại: " + reason.trim());
		OrderReturn saved = returnRepository.save(r);
		log.info("Buyer userId={} đã khiếu nại yêu cầu trả hàng {}", userId, returnId);
		return ReturnResponse.from(saved);
	}

	// --- [Seller] FR-ORD-07 ---

	@Transactional(readOnly = true)
	public Page<ReturnResponse> listSellerReturns(
			String userId, String bearerToken, String shopId, ReturnStatus status, int page, int size) {
		String targetShopId = sellerOrderService.resolveShopId(userId, bearerToken, shopId);
		Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));

		Page<OrderReturn> returns = (status != null)
				? returnRepository.findByShopIdAndStatusOrderByCreatedAtDesc(targetShopId, status, pageable)
				: returnRepository.findByShopIdOrderByCreatedAtDesc(targetShopId, pageable);

		return returns.map(ReturnResponse::from);
	}

	@Transactional
	public ReturnResponse acceptReturn(String userId, String bearerToken, String shopId, String returnId) {
		String targetShopId = sellerOrderService.resolveShopId(userId, bearerToken, shopId);
		OrderReturn r = returnRepository.lock(returnId)
				.orElseThrow(() -> ApiException.notFound("RETURN_NOT_FOUND", "Không tìm thấy yêu cầu trả hàng"));

		if (!targetShopId.equals(r.getShopId())) {
			throw ApiException.forbidden("FORBIDDEN", "Yêu cầu không thuộc gian hàng của bạn");
		}
		if (r.getStatus() != ReturnStatus.PENDING) {
			throw ApiException.conflict("INVALID_RETURN_STATUS",
					"Chỉ có thể chấp nhận yêu cầu ở trạng thái PENDING. Trạng thái hiện tại: " + r.getStatus());
		}
		if (clock.instant().isAfter(r.getCreatedAt().plus(Duration.ofHours(48)))) {
			throw ApiException.conflict("SELLER_RESPONSE_WINDOW_EXPIRED",
					"Đã quá thời hạn 48 giờ để người bán phản hồi. Yêu cầu đã được chuyển cho Quản trị viên phân xử.");
		}

		r.setStatus(ReturnStatus.APPROVED);
		OrderReturn saved = returnRepository.save(r);
		log.info("Seller shopId={} chấp nhận yêu cầu trả hàng {}", targetShopId, returnId);
		return ReturnResponse.from(saved);
	}

	@Transactional
	public ReturnResponse rejectReturn(
			String userId, String bearerToken, String shopId, String returnId, String reason) {
		String targetShopId = sellerOrderService.resolveShopId(userId, bearerToken, shopId);
		OrderReturn r = returnRepository.lock(returnId)
				.orElseThrow(() -> ApiException.notFound("RETURN_NOT_FOUND", "Không tìm thấy yêu cầu trả hàng"));

		if (!targetShopId.equals(r.getShopId())) {
			throw ApiException.forbidden("FORBIDDEN", "Yêu cầu không thuộc gian hàng của bạn");
		}
		if (r.getStatus() != ReturnStatus.PENDING) {
			throw ApiException.conflict("INVALID_RETURN_STATUS",
					"Chỉ có thể từ chối yêu cầu ở trạng thái PENDING. Trạng thái hiện tại: " + r.getStatus());
		}
		if (clock.instant().isAfter(r.getCreatedAt().plus(Duration.ofHours(48)))) {
			throw ApiException.conflict("SELLER_RESPONSE_WINDOW_EXPIRED",
					"Đã quá thời hạn 48 giờ để người bán phản hồi. Yêu cầu đã được chuyển cho Quản trị viên phân xử.");
		}

		r.setStatus(ReturnStatus.REJECTED);
		r.setSellerNote(reason.trim());
		OrderReturn saved = returnRepository.save(r);
		log.info("Seller shopId={} từ chối yêu cầu trả hàng {}. Lý do: {}", targetShopId, returnId, reason);
		return ReturnResponse.from(saved);
	}

	/**
	 * Seller xác nhận đã nhận lại hàng thu hồi: kích hoạt hoàn tiền và nhập kho lại
	 * (FR-ORD-07).
	 */
	@Transactional
	public ReturnResponse confirmReceipt(String userId, String bearerToken, String shopId, String returnId) {
		String targetShopId = sellerOrderService.resolveShopId(userId, bearerToken, shopId);
		OrderReturn r = returnRepository.lock(returnId)
				.orElseThrow(() -> ApiException.notFound("RETURN_NOT_FOUND", "Không tìm thấy yêu cầu trả hàng"));

		if (!targetShopId.equals(r.getShopId())) {
			throw ApiException.forbidden("FORBIDDEN", "Yêu cầu không thuộc gian hàng của bạn");
		}
		if (r.getStatus() != ReturnStatus.APPROVED) {
			throw ApiException.conflict("INVALID_RETURN_STATUS",
					"Chỉ có thể xác nhận nhận hàng khi yêu cầu đã được người bán chấp thuận (APPROVED). Trạng thái hiện tại: "
							+ r.getStatus());
		}

		r.setStatus(ReturnStatus.RESOLVED);
		r.setResolvedAt(clock.instant());
		OrderReturn saved = returnRepository.save(r);

		List<StockItem> items = saved.getItems().stream()
				.map(i -> new StockItem(i.getProductId(), i.getVariantId(), i.getQuantity()))
				.toList();

		outbox.add(
				EventType.RETURN_RESOLVED,
				new ReturnResolved(saved.getId(), saved.getOrderId(), saved.getRefundAmount(), true, items));

		log.info("Seller shopId={} xác nhận thu hồi hàng trả {} cho đơn {} -> RESOLVED (phát ReturnResolved)",
				targetShopId, returnId, saved.getOrderId());
		return ReturnResponse.from(saved);
	}

	// --- [Admin] FR-ORD-07 ---

	@Transactional(readOnly = true)
	public Page<ReturnResponse> listAdminReturns(ReturnStatus status, int page, int size) {
		Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
		Page<OrderReturn> returns = (status != null)
				? returnRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
				: returnRepository.findAllByOrderByCreatedAtDesc(pageable);
		return returns.map(ReturnResponse::from);
	}

	@Transactional
	public ReturnResponse resolveDispute(
			String adminId, String returnId, AdminResolveReturnRequest request) {
		OrderReturn r = returnRepository.lock(returnId)
				.orElseThrow(() -> ApiException.notFound("RETURN_NOT_FOUND", "Không tìm thấy yêu cầu trả hàng"));

		boolean isPendingOverdue = r.getStatus() == ReturnStatus.PENDING
				&& !clock.instant().isBefore(r.getCreatedAt().plus(Duration.ofHours(48)));

		if (r.getStatus() != ReturnStatus.DISPUTED && !isPendingOverdue) {
			throw ApiException.conflict("INVALID_RETURN_STATUS",
					"Chỉ có thể phân xử yêu cầu ở trạng thái DISPUTED hoặc PENDING đã quá hạn 48 giờ phản hồi. Trạng thái hiện tại: "
							+ r.getStatus());
		}

		if (request.approved()) {
			r.setStatus(ReturnStatus.RESOLVED);
			r.setResolvedAt(clock.instant());
			r.setAdminNote(request.note() != null ? request.note().trim() : "Admin chấp thuận khiếu nại");

			List<StockItem> items = r.getItems().stream()
					.map(i -> new StockItem(i.getProductId(), i.getVariantId(), i.getQuantity()))
					.toList();

			outbox.add(
					EventType.RETURN_RESOLVED,
					new ReturnResolved(r.getId(), r.getOrderId(), r.getRefundAmount(), request.restock(), items));

			log.info("Admin {} phân xử CHẤP THUẬN yêu cầu trả hàng {} -> RESOLVED (restock={})",
					adminId, returnId, request.restock());
		} else {
			r.setStatus(ReturnStatus.ADMIN_REJECTED);
			r.setResolvedAt(clock.instant());
			r.setAdminNote(request.note() != null ? request.note().trim() : "Admin bác bỏ khiếu nại");
			log.info("Admin {} phân xử BÁC BỎ yêu cầu trả hàng {} -> ADMIN_REJECTED", adminId, returnId);
		}

		return ReturnResponse.from(returnRepository.save(r));
	}
}
