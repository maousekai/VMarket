package com.vmarket.review.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.vmarket.media.MediaUploadResponse;
import com.vmarket.review.service.ReviewMediaService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Review media", description = "Ảnh thực tế người mua đính kèm đánh giá (FR-REV-01)")
@RestController
@RequestMapping("/api/reviews/media")
@RequiredArgsConstructor
public class ReviewMediaController {

	private final ReviewMediaService mediaService;

	@Operation(summary = "Tải một ảnh đánh giá lên kho ảnh",
			description = "JPEG/PNG/WebP tối đa 5 MB, thu nhỏ về tối đa 1600×1600 JPEG. Trả URL để gửi kèm imageUrls.")
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public MediaUploadResponse upload(@AuthenticationPrincipal String buyerId, @RequestPart("file") MultipartFile file) {
		return mediaService.upload(buyerId, file);
	}

	@Operation(summary = "Xoá ảnh đã tải lên nhưng chưa gửi kèm đánh giá nào")
	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal String buyerId, @RequestParam String url) {
		mediaService.delete(buyerId, url);
	}
}
