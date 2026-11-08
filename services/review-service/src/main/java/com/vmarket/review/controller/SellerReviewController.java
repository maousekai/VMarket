package com.vmarket.review.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.review.dto.PageResponse;
import com.vmarket.review.dto.ReviewRequests;
import com.vmarket.review.dto.ReviewResponse;
import com.vmarket.review.entity.ReviewStatus;
import com.vmarket.review.service.SellerReplyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Seller reviews", description = "[Seller] Xem và phản hồi đánh giá của gian hàng (FR-REV-03)")
@RestController
@RequestMapping("/api/reviews/seller")
@RequiredArgsConstructor
public class SellerReviewController {

	private final SellerReplyService sellerReplyService;

	@Operation(summary = "Đánh giá thuộc gian hàng của tôi (mới nhất trước)")
	@GetMapping
	public PageResponse<ReviewResponse> myShopReviews(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
			@RequestParam(required = false) ReviewStatus status, @RequestParam(required = false) Integer rating,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
		return sellerReplyService.myShopReviews(authorization, status, rating, page, size);
	}

	@Operation(summary = "Tạo hoặc sửa phản hồi công khai cho một đánh giá")
	@PutMapping("/{reviewId}/reply")
	public ReviewResponse reply(@AuthenticationPrincipal String sellerId,
			@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @PathVariable String reviewId,
			@Valid @RequestBody ReviewRequests.Reply request) {
		return sellerReplyService.reply(sellerId, authorization, reviewId, request.content());
	}

	@Operation(summary = "Xoá phản hồi của tôi")
	@DeleteMapping("/{reviewId}/reply")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteReply(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
			@PathVariable String reviewId) {
		sellerReplyService.deleteReply(authorization, reviewId);
	}
}
