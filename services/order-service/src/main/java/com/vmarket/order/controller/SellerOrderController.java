package com.vmarket.order.controller;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.order.dto.RejectReturnRequest;
import com.vmarket.order.dto.ReturnResponse;
import com.vmarket.order.dto.SellerOrderResponse;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.entity.ReturnStatus;
import com.vmarket.order.service.OrderReturnService;
import com.vmarket.order.service.SellerOrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * API quản lý và xử lý đơn hàng dành cho Seller (FR-ORD-04, FR-ORD-07).
 */
@Tag(name = "Seller Orders", description = "Quản lý và xử lý đơn hàng cho Seller (FR-ORD-04, FR-ORD-07)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/orders/seller")
@RequiredArgsConstructor
public class SellerOrderController {

	private final SellerOrderService sellerOrderService;
	private final OrderReturnService returnService;

	@Operation(summary = "Danh sách đơn hàng của gian hàng (FR-ORD-04)")
	@GetMapping
	public Page<SellerOrderResponse> listOrders(
			@AuthenticationPrincipal String userId,
			@RequestHeader(name = "Authorization", required = false) String bearerToken,
			@RequestParam(required = false) String shopId,
			@RequestParam(required = false) OrderStatus status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return sellerOrderService.listSellerOrders(userId, bearerToken, shopId, status, page, size);
	}

	@Operation(summary = "Chi tiết một đơn hàng của gian hàng (FR-ORD-04)")
	@GetMapping("/{orderId}")
	public SellerOrderResponse getOrder(
			@AuthenticationPrincipal String userId,
			@RequestHeader(name = "Authorization", required = false) String bearerToken,
			@RequestParam(required = false) String shopId,
			@PathVariable String orderId) {
		return sellerOrderService.getSellerOrder(userId, bearerToken, shopId, orderId);
	}

	@Operation(summary = "Seller xác nhận đơn và chuẩn bị hàng (FR-ORD-04)",
			description = "Chuyển đơn từ PENDING sang PROCESSING và phát sự kiện OrderStatusChanged.")
	@PostMapping("/{orderId}/confirm")
	public SellerOrderResponse confirmOrder(
			@AuthenticationPrincipal String userId,
			@RequestHeader(name = "Authorization", required = false) String bearerToken,
			@RequestParam(required = false) String shopId,
			@PathVariable String orderId) {
		return sellerOrderService.confirmOrder(userId, bearerToken, shopId, orderId);
	}

	@Operation(summary = "Danh sách yêu cầu trả hàng gửi tới gian hàng (FR-ORD-07)")
	@GetMapping("/returns")
	public Page<ReturnResponse> listReturns(
			@AuthenticationPrincipal String userId,
			@RequestHeader(name = "Authorization", required = false) String bearerToken,
			@RequestParam(required = false) String shopId,
			@RequestParam(required = false) ReturnStatus status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return returnService.listSellerReturns(userId, bearerToken, shopId, status, page, size);
	}

	@Operation(summary = "Seller chấp nhận yêu cầu trả hàng (FR-ORD-07)")
	@PostMapping("/returns/{returnId}/accept")
	public ReturnResponse acceptReturn(
			@AuthenticationPrincipal String userId,
			@RequestHeader(name = "Authorization", required = false) String bearerToken,
			@RequestParam(required = false) String shopId,
			@PathVariable String returnId) {
		return returnService.acceptReturn(userId, bearerToken, shopId, returnId);
	}

	@Operation(summary = "Seller từ chối yêu cầu trả hàng kèm lý do (FR-ORD-07)")
	@PostMapping("/returns/{returnId}/reject")
	public ReturnResponse rejectReturn(
			@AuthenticationPrincipal String userId,
			@RequestHeader(name = "Authorization", required = false) String bearerToken,
			@RequestParam(required = false) String shopId,
			@PathVariable String returnId,
			@Valid @RequestBody RejectReturnRequest request) {
		return returnService.rejectReturn(userId, bearerToken, shopId, returnId, request.reason());
	}

	@Operation(summary = "Seller xác nhận đã nhận lại hàng thu hồi (FR-ORD-07)",
			description = "Chuyển sang RESOLVED, phát sự kiện ReturnResolved để Product Catalog cộng tồn kho và Payment Service hoàn tiền.")
	@PostMapping("/returns/{returnId}/confirm-receipt")
	public ReturnResponse confirmReceipt(
			@AuthenticationPrincipal String userId,
			@RequestHeader(name = "Authorization", required = false) String bearerToken,
			@RequestParam(required = false) String shopId,
			@PathVariable String returnId) {
		return returnService.confirmReceipt(userId, bearerToken, shopId, returnId);
	}
}
