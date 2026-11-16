package com.vmarket.product.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.vmarket.media.MediaUploadResponse;
import com.vmarket.product.config.OpenApiConfig;
import com.vmarket.product.security.RequestIdentity;
import com.vmarket.product.service.ProductMediaService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/**
 * Upload / dọn ảnh sản phẩm của seller (PBL6-28). Nằm dưới {@code /seller/} để không
 * đụng route công khai {@code GET /api/products/{id}} và {@code DELETE /api/products/{id}}.
 */
@RestController
@RequestMapping("/api/products/seller/media")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ProductMediaController {

	private final ProductMediaService mediaService;
	private final RequestIdentity identity;

	public ProductMediaController(ProductMediaService mediaService, RequestIdentity identity) {
		this.mediaService = mediaService;
		this.identity = identity;
	}

	@Operation(summary = "Tải một ảnh sản phẩm lên kho ảnh",
			description = "Ảnh được thu nhỏ về tối đa 1200×1200 JPEG. Trả URL để gửi kèm imageUrls khi lưu sản phẩm.")
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public MediaUploadResponse upload(@RequestPart("file") MultipartFile file) {
		return mediaService.upload(identity.requireSeller(), file);
	}

	@Operation(summary = "Xoá ảnh đã tải lên nhưng không dùng",
			description = "Chỉ xoá được ảnh của chính seller; ảnh đang gắn với sản phẩm trả 409 MEDIA_IN_USE.")
	@DeleteMapping
	public ResponseEntity<Void> delete(@RequestParam String url) {
		mediaService.delete(identity.requireSeller(), url);
		return ResponseEntity.noContent().build();
	}
}
