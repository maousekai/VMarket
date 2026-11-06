package com.vmarket.review.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.vmarket.review.dto.PageResponse;
import com.vmarket.review.dto.RatingSummary;
import com.vmarket.review.dto.ReviewRequests;
import com.vmarket.review.dto.ReviewResponse;
import com.vmarket.review.service.RatingService;
import com.vmarket.review.service.ReviewService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Reviews", description = "Viết đánh giá và xem điểm tổng hợp (FR-REV-01, FR-REV-02)")
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

	private final ReviewService reviewService;
	private final RatingService ratingService;

	@Operation(summary = "Viết đánh giá cho sản phẩm trong đơn đã hoàn thành",
			description = "1–5 sao, tối đa 5 ảnh. Mỗi sản phẩm trong một đơn chỉ đánh giá được một lần (409 REVIEW_ALREADY_EXISTS).")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ReviewResponse create(@AuthenticationPrincipal String buyerId,
			@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
			@Valid @RequestBody ReviewRequests.CreateReview request) {
		return reviewService.create(buyerId, authorization, request);
	}

	@Operation(summary = "Đánh giá của tôi (mới nhất trước)", description = "Truyền orderId để biết sản phẩm nào trong đơn đã được đánh giá.")
	@GetMapping("/me")
	public PageResponse<ReviewResponse> myReviews(@AuthenticationPrincipal String buyerId,
			@RequestParam(required = false) String orderId, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return reviewService.myReviews(buyerId, orderId, page, size);
	}

	@Operation(summary = "Danh sách đánh giá công khai của sản phẩm (mới nhất trước)")
	@GetMapping("/products/{productId}")
	public PageResponse<ReviewResponse> productReviews(@PathVariable String productId,
			@RequestParam(required = false) Integer rating, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return reviewService.productReviews(productId, rating, page, size);
	}

	@Operation(summary = "Điểm trung bình, số lượt và phân bố sao của sản phẩm")
	@GetMapping("/products/{productId}/summary")
	public RatingSummary productSummary(@PathVariable String productId) {
		return ratingService.productSummary(productId);
	}

	@Operation(summary = "Danh sách đánh giá công khai của gian hàng (mới nhất trước)")
	@GetMapping("/shops/{shopId}")
	public PageResponse<ReviewResponse> shopReviews(@PathVariable String shopId,
			@RequestParam(required = false) Integer rating, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return reviewService.shopReviews(shopId, rating, page, size);
	}

	@Operation(summary = "Điểm trung bình, số lượt và phân bố sao của gian hàng")
	@GetMapping("/shops/{shopId}/summary")
	public RatingSummary shopSummary(@PathVariable String shopId) {
		return ratingService.shopSummary(shopId);
	}
}
