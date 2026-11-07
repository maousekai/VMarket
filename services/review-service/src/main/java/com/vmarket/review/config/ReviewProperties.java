package com.vmarket.review.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

/** Cấu hình Review Service (khoá {@code app.review.*}). */
@ConfigurationProperties("app.review")
@Validated
@Getter
@Setter
public class ReviewProperties {

	private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1");

	/** Order Service — xác minh người mua đã nhận hàng trước khi cho đánh giá (FR-REV-01). */
	@NotBlank
	private String orderUrl = "http://localhost:8086";

	/** Shop Service — xác định gian hàng của seller khi phản hồi đánh giá (FR-REV-03). */
	@NotBlank
	private String shopUrl = "http://localhost:8083";

	/**
	 * Origin ({@code scheme://host[:port]}) của kho ảnh/CDN mà ảnh đánh giá được phép trỏ tới, ví dụ
	 * MinIO {@code MINIO_PUBLIC_ORIGIN}. Ảnh hiển thị công khai nên không cho trỏ host tuỳ ý (ảnh
	 * tracking lộ IP người xem, nội dung không kiểm soát, mixed-content).
	 */
	@NotEmpty
	private List<String> imageOrigins = new ArrayList<>();

	/** Origin ngoài loopback bắt buộc https — chặn cấu hình http ở môi trường thật ngay lúc khởi động. */
	@AssertTrue(message = "app.review.image-origins phải là origin https (http chỉ cho localhost)")
	public boolean isImageOriginsSecure() {
		return imageOrigins.stream().allMatch(ReviewProperties::isSecureOrigin);
	}

	private static boolean isSecureOrigin(String origin) {
		try {
			URI uri = URI.create(origin);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			boolean bareOrigin = (uri.getRawPath() == null || uri.getRawPath().isEmpty()) && uri.getRawQuery() == null
					&& uri.getRawFragment() == null && uri.getRawUserInfo() == null;
			return !host.isEmpty() && bareOrigin
					&& ("https".equals(scheme) || ("http".equals(scheme) && LOOPBACK_HOSTS.contains(host)));
		} catch (IllegalArgumentException ex) {
			return false;
		}
	}
}
