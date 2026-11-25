package com.vmarket.shop.client;

import java.time.Instant;
import java.util.List;

import org.springframework.http.MediaType;

import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Đọc dữ liệu catalog tối thiểu cho dashboard.
 *
 * <p>Không có endpoint thống kê sản phẩm, nên "đang bán" lấy từ cùng API browse mà
 * trang công khai dùng ({@code totalElements}) và tên/ảnh lấy từ snapshot nội bộ.
 * Product-service lỗi thì trả {@code null}/rỗng — số liệu phụ không được làm sập
 * toàn bộ dashboard.
 */
@Slf4j
public class ProductServiceClient {

	private final RestClient restClient;

	public ProductServiceClient(RestClient restClient) {
		this.restClient = restClient;
	}

	public Long activeProductCount(String shopId) {
		try {
			ProductPage page = restClient.get()
					.uri(builder -> builder.path("/api/products")
							.queryParam("shopId", shopId)
							.queryParam("page", 0)
							.queryParam("size", 1)
							.build())
					.retrieve()
					.body(ProductPage.class);
			return page == null ? null : page.totalElements();
		} catch (RuntimeException ex) {
			log.warn("Không đếm được sản phẩm của shop {}: {}", shopId, ex.getMessage());
			return null;
		}
	}

	public ProductSnapshot snapshot(String productId) {
		try {
			return restClient.get()
					.uri("/api/products/internal/search-snapshots/{id}", productId)
					.retrieve()
					.body(ProductSnapshot.class);
		} catch (RuntimeException ex) {
			log.warn("Không đọc được snapshot sản phẩm {}: {}", productId, ex.getMessage());
			return null;
		}
	}

	/**
	 * Đẩy trạng thái gian hàng đã duyệt sang product-service (khoá nội bộ đã gắn sẵn).
	 *
	 * @return {@code false} khi product-service lỗi / chưa sẵn sàng — lần sau thử lại
	 */
	public boolean reconcileShopAccess(List<ShopAccessSnapshot> shops) {
		try {
			restClient.post()
					.uri("/api/products/internal/shop-access/reconcile")
					.contentType(MediaType.APPLICATION_JSON)
					.body(new ShopAccessSyncRequest(shops))
					.retrieve()
					.toBodilessEntity();
			return true;
		} catch (RuntimeException ex) {
			log.warn("Không đối soát được quyền đăng bán sang product-service: {}", ex.getMessage());
			return false;
		}
	}

	public record ShopAccessSnapshot(String shopId, String sellerId, boolean active, Instant updatedAt,
			String shopName) {
	}

	public record ShopAccessSyncRequest(List<ShopAccessSnapshot> shops) {
	}

	public record ProductPage(List<ProductView> content, long totalElements) {
	}

	public record ProductView(String id) {
	}

	public record ProductSnapshot(String id, String name, List<String> imageUrls, Long availableStock) {
	}
}
