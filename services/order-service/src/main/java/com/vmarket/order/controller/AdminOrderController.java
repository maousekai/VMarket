package com.vmarket.order.controller;

import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.order.dto.AdminCancelOrderRequest;
import com.vmarket.order.dto.AdminResolveReturnRequest;
import com.vmarket.order.dto.OrderResponse;
import com.vmarket.order.dto.ReturnResponse;
import com.vmarket.order.entity.OrderStatus;
import com.vmarket.order.entity.ReturnStatus;
import com.vmarket.order.service.AdminOrderService;
import com.vmarket.order.service.OrderReturnService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * API quản trị và giám sát đơn hàng toàn sàn dành cho Admin (FR-ORD-05, FR-ORD-07).
 */
@Tag(name = "Admin Orders", description = "Giám sát đơn hàng toàn sàn và phân xử tranh chấp (FR-ORD-05, FR-ORD-07)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/orders/admin")
@RequiredArgsConstructor
public class AdminOrderController {

	private final AdminOrderService adminOrderService;
	private final OrderReturnService returnService;

	@Operation(summary = "Xem toàn bộ đơn hàng toàn sàn (FR-ORD-05)")
	@GetMapping
	public Page<OrderResponse> listOrders(
			@RequestParam(required = false) OrderStatus status,
			@RequestParam(required = false) String userId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return adminOrderService.listOrders(status, userId, page, size);
	}

	@Operation(summary = "Chi tiết đơn hàng bất kỳ (FR-ORD-05)")
	@GetMapping("/{orderId}")
	public OrderResponse getOrder(@PathVariable String orderId) {
		return adminOrderService.getOrder(orderId);
	}

	@Operation(summary = "Admin can thiệp huỷ đơn hàng khi có tranh chấp (FR-ORD-05)")
	@PostMapping("/{orderId}/cancel")
	public OrderResponse cancelOrder(
			@AuthenticationPrincipal String adminId,
			@PathVariable String orderId,
			@Valid @RequestBody AdminCancelOrderRequest request) {
		return adminOrderService.cancelOrder(adminId, orderId, request.reason());
	}

	@Operation(summary = "Danh sách yêu cầu trả hàng cần phân xử (FR-ORD-07)")
	@GetMapping("/returns")
	public Page<ReturnResponse> listReturns(
			@RequestParam(required = false) ReturnStatus status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return returnService.listAdminReturns(status, page, size);
	}

	@Operation(summary = "Admin phân xử yêu cầu trả hàng / khiếu nại (FR-ORD-07)")
	@PostMapping("/returns/{returnId}/resolve")
	public ReturnResponse resolveReturn(
			@AuthenticationPrincipal String adminId,
			@PathVariable String returnId,
			@Valid @RequestBody AdminResolveReturnRequest request) {
		return returnService.resolveDispute(adminId, returnId, request);
	}
}
