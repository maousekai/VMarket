package com.vmarket.cart.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.vmarket.cart.config.CartProperties;
import com.vmarket.cart.dto.AddCartItemRequest;
import com.vmarket.cart.dto.CartGroupDto;
import com.vmarket.cart.dto.CartItemDto;
import com.vmarket.cart.dto.CartResponse;
import com.vmarket.cart.exception.ApiException;
import com.vmarket.cart.exception.CartStorageException;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Nghiệp vụ giỏ hàng lưu trên Redis (FR-CART-01, FR-CART-02).
 *
 * <p><b>Cách lưu:</b> mỗi người dùng một key {@code cart:{userId}} chứa toàn bộ
 * giỏ dạng JSON. Giỏ là tài liệu nhỏ (vài chục item tối đa) nên đọc/ghi cả tài
 * liệu đơn giản và luôn nhất quán hơn so với hash từng item. Mỗi lần ghi sẽ
 * refresh TTL ({@code app.cart.ttl-days}) — giỏ của người dùng hoạt động thường
 * xuyên không bao giờ hết hạn.
 *
 * <p><b>Đồng thời:</b> đọc → sửa → ghi được bảo vệ bằng {@link ReentrantLock}
 * theo userId để tránh mất cập nhật khi cùng một tài khoản thao tác song song
 * (web + mobile cùng tài khoản là trường hợp FR-CART-01 nêu rõ). Lock này
 * bảo vệ trong phạm vi một instance; khi chạy nhiều instance cart-service cần
 * nâng cấp lên lock phân tán (Redis SET NX / Lua) — ghi nhận là việc của giai
 * đoạn scale-out sau.
 */
@Slf4j
@Service
public class CartService {

	/** Một item trong giỏ (bản lưu nội bộ trên Redis). */
	public record StoredItem(
			String productId,
			String variantId,
			String shopId,
			int quantity,
			BigDecimal unitPrice,
			Instant addedAt,
			Instant updatedAt) {
	}

	/** Tài liệu JSON được lưu vào Redis cho mỗi người dùng. */
	public record CartDocument(List<StoredItem> items) {
	}

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final CartProperties properties;

	/** Một lock cho mỗi userId đang hoạt động. */
	private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

	public CartService(StringRedisTemplate redis, ObjectMapper objectMapper, CartProperties properties) {
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	/**
	 * Lấy giỏ hàng nhóm theo gian hàng. Giỏ rỗng → nhóm rỗng, tổng 0.
	 */
	public CartResponse getCart(String userId) {
		return toResponse(userId, items(userId));
	}

	/**
	 * Thêm một item vào giỏ. Nếu đã có cùng {@code productId + variantId} thì
	 * cộng dồn số lượng và cập nhật giá snapshot theo giá vừa thêm.
	 */
	public CartResponse addItem(String userId, AddCartItemRequest request) {
		ReentrantLock lock = lockFor(userId);
		lock.lock();
		try {
			CartDocument doc = load(userId);
			Instant now = Instant.now();
			List<StoredItem> items = new ArrayList<>(doc.items());
			StoredItem existing = findItem(items, request.productId(), request.variantId());
			if (existing != null) {
				int newQuantity = existing.quantity() + request.quantity();
				items.set(items.indexOf(existing), new StoredItem(
						existing.productId(), existing.variantId(), existing.shopId(),
						newQuantity, request.unitPrice(), existing.addedAt(), now));
				log.debug("addItem: cộng dồn productId={} variantId={} → quantity={}",
						request.productId(), request.variantId(), newQuantity);
			} else {
				items.add(new StoredItem(request.productId(), normalizeVariant(request.variantId()),
						request.shopId(), request.quantity(), request.unitPrice(), now, now));
				log.debug("addItem: thêm mới productId={} variantId={}",
						request.productId(), request.variantId());
			}
			save(userId, new CartDocument(items));
			return toResponse(userId, items);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Cập nhật số lượng một item. Không tìm thấy → 404 {@code CART_ITEM_NOT_FOUND}.
	 */
	public CartResponse updateQuantity(String userId, String productId, String variantId, int quantity) {
		ReentrantLock lock = lockFor(userId);
		lock.lock();
		try {
			List<StoredItem> items = new ArrayList<>(load(userId).items());
			StoredItem existing = findItem(items, productId, variantId);
			if (existing == null) {
				throw ApiException.notFound("CART_ITEM_NOT_FOUND", "Sản phẩm không có trong giỏ hàng");
			}
			items.set(items.indexOf(existing), new StoredItem(
					existing.productId(), existing.variantId(), existing.shopId(),
					quantity, existing.unitPrice(), existing.addedAt(), Instant.now()));
			save(userId, new CartDocument(items));
			return toResponse(userId, items);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Xoá một item khỏi giỏ. Không tìm thấy → 404 {@code CART_ITEM_NOT_FOUND}.
	 */
	public CartResponse removeItem(String userId, String productId, String variantId) {
		ReentrantLock lock = lockFor(userId);
		lock.lock();
		try {
			List<StoredItem> items = new ArrayList<>(load(userId).items());
			StoredItem existing = findItem(items, productId, variantId);
			if (existing == null) {
				throw ApiException.notFound("CART_ITEM_NOT_FOUND", "Sản phẩm không có trong giỏ hàng");
			}
			items.remove(existing);
			save(userId, new CartDocument(items));
			return toResponse(userId, items);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Xoá toàn bộ giỏ hàng (FR-CART-01).
	 */
	public void clearCart(String userId) {
		ReentrantLock lock = lockFor(userId);
		lock.lock();
		try {
			redis.delete(key(userId));
			log.debug("clearCart: đã xoá giỏ của userId={}", userId);
		} catch (org.springframework.data.redis.RedisConnectionFailureException ex) {
			log.error("Không kết nối được Redis khi xoá giỏ userId={}", userId, ex);
			throw new CartStorageException("Redis không khả dụng", ex);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Danh sách item thô của giỏ (cho {@code CheckoutService} — FR-CART-03).
	 */
	public List<StoredItem> items(String userId) {
		return load(userId).items();
	}

	// ------------------------------------------------------------------
	// Phần nội bộ
	// ------------------------------------------------------------------

	private ReentrantLock lockFor(String userId) {
		return locks.computeIfAbsent(userId, id -> new ReentrantLock());
	}

	private CartDocument load(String userId) {
		String json;
		try {
			json = redis.opsForValue().get(key(userId));
		} catch (org.springframework.data.redis.RedisConnectionFailureException ex) {
			log.error("Không kết nối được Redis khi đọc giỏ userId={}", userId, ex);
			throw new CartStorageException("Redis không khả dụng", ex);
		}
		if (json == null || json.isBlank()) {
			return new CartDocument(List.of());
		}
		try {
			return objectMapper.readValue(json, CartDocument.class);
		} catch (Exception ex) {
			// Giỏ hỏng (schema cũ/không tương thích) → coi như rỗng, lần ghi kế
			// tiếp sẽ ghi đè — đừng để một key hỏng chặn người dùng vĩnh viễn.
			log.error("Giỏ của userId={} không đọc được JSON, coi như rỗng", userId, ex);
			return new CartDocument(List.of());
		}
	}

	private void save(String userId, CartDocument doc) {
		try {
			redis.opsForValue().set(key(userId), objectMapper.writeValueAsString(doc));
			redis.expire(key(userId), Duration.ofDays(properties.getTtlDays()));
		} catch (org.springframework.data.redis.RedisConnectionFailureException ex) {
			log.error("Không kết nối được Redis khi ghi giỏ userId={}", userId, ex);
			throw new CartStorageException("Redis không khả dụng", ex);
		} catch (Exception ex) {
			log.error("Lỗi ghi giỏ userId={}", userId, ex);
			throw new CartStorageException("Không ghi được giỏ hàng", ex);
		}
	}

	private String key(String userId) {
		return properties.getKeyPrefix() + userId;
	}

	private static String normalizeVariant(String variantId) {
		return (variantId == null || variantId.isBlank()) ? null : variantId;
	}

	private static StoredItem findItem(List<StoredItem> items, String productId, String variantId) {
		String variant = normalizeVariant(variantId);
		return items.stream()
				.filter(i -> Objects.equals(i.productId(), productId)
						&& Objects.equals(i.variantId(), variant))
				.findFirst()
				.orElse(null);
	}

	/**
	 * Gom item theo gian hàng, tính tạm tính từng nhóm và tổng cả giỏ (FR-CART-02).
	 */
	private static CartResponse toResponse(String userId, List<StoredItem> items) {
		Map<String, List<StoredItem>> byShop = new LinkedHashMap<>();
		Instant latest = null;
		int totalQuantity = 0;
		BigDecimal total = BigDecimal.ZERO;
		for (StoredItem item : items) {
			byShop.computeIfAbsent(item.shopId(), id -> new ArrayList<>()).add(item);
			totalQuantity += item.quantity();
			total = total.add(item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())));
			if (latest == null || item.updatedAt().isAfter(latest)) {
				latest = item.updatedAt();
			}
		}
		List<CartGroupDto> groups = byShop.entrySet().stream()
				.map(e -> new CartGroupDto(
						e.getKey(),
						e.getValue().stream().map(CartService::toDto).toList(),
						e.getValue().stream()
								.map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
								.reduce(BigDecimal.ZERO, BigDecimal::add)))
				.toList();
		return new CartResponse(userId, groups, totalQuantity, total, latest);
	}

	private static CartItemDto toDto(StoredItem item) {
		return new CartItemDto(
				item.productId(), item.variantId(), item.shopId(),
				item.quantity(), item.unitPrice(),
				item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())),
				item.addedAt(), item.updatedAt());
	}
}
