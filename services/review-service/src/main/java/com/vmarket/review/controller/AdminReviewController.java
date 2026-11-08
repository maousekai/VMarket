package com.vmarket.review.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.review.dto.PageResponse;
import com.vmarket.review.dto.ReviewRequests;
import com.vmarket.review.dto.ReviewResponse;
import com.vmarket.review.entity.ReviewStatus;
import com.vmarket.review.service.ModerationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Admin reviews", description = "[Admin] Kiểm duyệt đánh giá (FR-REV-04)")
@RestController
@RequestMapping("/api/reviews/admin")
@RequiredArgsConstructor
public class AdminReviewController {

	private final ModerationService moderationService;

	@Operation(summary = "Tìm đánh giá theo trạng thái / sản phẩm / gian hàng")
	@GetMapping
	public PageResponse<ReviewResponse> search(@RequestParam(required = false) ReviewStatus status,
			@RequestParam(required = false) String productId, @RequestParam(required = false) String shopId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return moderationService.search(status, productId, shopId, page, size);
	}

	@Operation(summary = "Ẩn đánh giá vi phạm", description = "Đánh giá bị ẩn không hiển thị công khai và không tính vào điểm trung bình.")
	@PostMapping("/{reviewId}/hide")
	public ReviewResponse hide(@AuthenticationPrincipal String adminId, @PathVariable String reviewId,
			@Valid @RequestBody ReviewRequests.Hide request) {
		return moderationService.hide(adminId, reviewId, request.reason());
	}

	@Operation(summary = "Khôi phục đánh giá đã ẩn")
	@PostMapping("/{reviewId}/restore")
	public ReviewResponse restore(@AuthenticationPrincipal String adminId, @PathVariable String reviewId) {
		return moderationService.restore(adminId, reviewId);
	}
}
