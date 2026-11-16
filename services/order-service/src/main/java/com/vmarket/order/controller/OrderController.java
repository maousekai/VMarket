package com.vmarket.order.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.vmarket.media.MediaUploadResponse;

import com.vmarket.order.dto.CheckoutRequest;
import com.vmarket.order.dto.CreateReturnRequest;
import com.vmarket.order.dto.DisputeReturnRequest;
import com.vmarket.order.dto.ErrorResponse;
import com.vmarket.order.dto.OrderResponse;
import com.vmarket.order.dto.PlaceOrderRequest;
import com.vmarket.order.dto.ReturnResponse;
import com.vmarket.order.service.OrderReturnService;
import com.vmarket.order.service.OrderService;
import com.vmarket.order.service.ReturnEvidenceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * API đơn hàng của người đang đăng nhập (FR-ORDER-01/02/03).
 *
 * <p>Mọi endpoint đều thao tác trong phạm vi "của tôi" nên không thể chạm vào đơn
 * của người khác, kể cả khi biết id.
 *
 * <p><b>Danh tính:</b> {@code userId} lấy từ access token (principal do
 * {@code JwtAuthenticationFilter} đặt vào SecurityContext) — không endpoint nào
 * nhận userId từ body hay path.
 */
@Tag(name = "Orders", description = "Đặt hàng từ giỏ, xem và huỷ đơn (FR-ORDER-01/02/03)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

	private final OrderService orderService;
	private final OrderReturnService returnService;
	private final ReturnEvidenceService evidenceService;

	@Operation(summary = "Đặt hàng từ giỏ hiện có (FR-ORDER-01)",
			description = "Chụp toàn bộ giỏ thành đơn với địa chỉ giao hàng được chọn. "
					+ "Giỏ trống → 400 CART_EMPTY. Đơn tạo xong thì giỏ bị xoá; xoá giỏ hỏng "
					+ "không ảnh hưởng đơn đã tạo.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Đã tạo đơn",
					content = @Content(schema = @Schema(implementation = OrderResponse.class))),
			@ApiResponse(responseCode = "400", description = "Giỏ trống (CART_EMPTY) hoặc dữ liệu không hợp lệ",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "404", description = "Địa chỉ không tồn tại hoặc không thuộc sổ của bạn (ADDRESS_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "503", description = "cart-service / user-service chưa kết nối được (CART_SERVICE_UNAVAILABLE / USER_SERVICE_UNAVAILABLE)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PostMapping
	public ResponseEntity<OrderResponse> placeOrder(
			@AuthenticationPrincipal String userId,
			@RequestHeader(name = "Authorization", required = false) String bearerToken,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody PlaceOrderRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(orderService.placeOrder(userId, bearerToken, idempotencyKey, request));
	}

	@Operation(summary = "Đặt các dòng đã chọn trong giỏ, tách một đơn cho mỗi gian hàng",
			description = "items rỗng = toàn bộ giỏ. Mỗi đơn có trạng thái, huỷ và trả hàng riêng. "
					+ "Gửi lại cùng Idempotency-Key trả về các đơn đã tạo.")
	@PostMapping("/checkout")
	public ResponseEntity<List<OrderResponse>> checkout(
			@AuthenticationPrincipal String userId,
			@RequestHeader(name = "Authorization", required = false) String bearerToken,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody CheckoutRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(orderService.checkout(userId, bearerToken, idempotencyKey, request));
	}

	@Operation(summary = "Danh sách đơn của tôi (FR-ORDER-02)",
			description = "Mới nhất trước.")
	@ApiResponse(responseCode = "200", description = "Thành công")
	@GetMapping
	public List<OrderResponse> listMyOrders(@AuthenticationPrincipal String userId) {
		return orderService.listMyOrders(userId);
	}

	@Operation(summary = "Chi tiết một đơn của tôi (FR-ORDER-02)")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Thành công",
					content = @Content(schema = @Schema(implementation = OrderResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy đơn hoặc đơn không thuộc về bạn (ORDER_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@GetMapping("/{orderId}")
	public OrderResponse getMyOrder(@AuthenticationPrincipal String userId, @PathVariable String orderId) {
		return orderService.getMyOrder(userId, orderId);
	}

	@Operation(summary = "Huỷ một đơn của tôi (FR-ORDER-03)",
			description = "Chỉ huỷ được khi đơn còn PENDING. Đã vào PROCESSING phải liên hệ người bán.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Đã huỷ",
					content = @Content(schema = @Schema(implementation = OrderResponse.class))),
			@ApiResponse(responseCode = "404", description = "Không tìm thấy đơn (ORDER_NOT_FOUND)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(responseCode = "409", description = "Đơn đã qua trạng thái được huỷ (ORDER_NOT_CANCELLABLE)",
					content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
	})
	@PutMapping("/{orderId}/cancel")
	public OrderResponse cancelMyOrder(@AuthenticationPrincipal String userId, @PathVariable String orderId) {
		return orderService.cancelMyOrder(userId, orderId);
	}

	@Operation(summary = "Người mua xác nhận đã nhận hàng và hoàn tất đơn sớm (FR-ORD-02)")
	@PostMapping("/{orderId}/complete")
	public OrderResponse completeMyOrder(@AuthenticationPrincipal String userId, @PathVariable String orderId) {
		return orderService.completeMyOrder(userId, orderId);
	}

	@Operation(summary = "Tạo yêu cầu trả hàng cho đơn đã nhận trong vòng 7 ngày (FR-ORD-06)")
	@PostMapping("/{orderId}/returns")
	public ResponseEntity<ReturnResponse> createReturn(
			@AuthenticationPrincipal String userId,
			@PathVariable String orderId,
			@Valid @RequestBody CreateReturnRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(returnService.createReturn(userId, orderId, request));
	}

	@Operation(summary = "Tải một ảnh minh chứng trả hàng lên kho ảnh (FR-ORD-06)",
			description = "Ảnh thu nhỏ về tối đa 1600×1600 JPEG. Trả URL để gửi kèm evidenceUrls (tối đa 5).")
	@PostMapping(value = "/returns/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public MediaUploadResponse uploadReturnEvidence(@AuthenticationPrincipal String userId,
			@RequestPart("file") MultipartFile file) {
		return evidenceService.upload(userId, file);
	}

	@Operation(summary = "Xoá ảnh minh chứng đã tải lên nhưng chưa gửi kèm yêu cầu nào")
	@DeleteMapping("/returns/evidence")
	public ResponseEntity<Void> deleteReturnEvidence(@AuthenticationPrincipal String userId,
			@RequestParam String url) {
		evidenceService.delete(userId, url);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "Xem các yêu cầu trả hàng của một đơn (FR-ORD-06)")
	@GetMapping("/{orderId}/returns")
	public List<ReturnResponse> listReturnsForOrder(
			@AuthenticationPrincipal String userId,
			@PathVariable String orderId) {
		return returnService.listReturnsForOrder(userId, orderId);
	}

	@Operation(summary = "Xem tất cả yêu cầu trả hàng của tôi (FR-ORD-06)")
	@GetMapping("/returns/me")
	public List<ReturnResponse> listMyReturns(@AuthenticationPrincipal String userId) {
		return returnService.listMyReturns(userId);
	}

	@Operation(summary = "Chi tiết một yêu cầu trả hàng của tôi (FR-ORD-06)")
	@GetMapping("/returns/{returnId}")
	public ReturnResponse getMyReturn(
			@AuthenticationPrincipal String userId,
			@PathVariable String returnId) {
		return returnService.getMyReturn(userId, returnId);
	}

	@Operation(summary = "Người mua khiếu nại lên Admin nếu Seller từ chối yêu cầu trả hàng (FR-ORD-07)")
	@PostMapping("/returns/{returnId}/dispute")
	public ReturnResponse disputeReturn(
			@AuthenticationPrincipal String userId,
			@PathVariable String returnId,
			@Valid @RequestBody DisputeReturnRequest request) {
		return returnService.disputeReturn(userId, returnId, request.reason());
	}
}