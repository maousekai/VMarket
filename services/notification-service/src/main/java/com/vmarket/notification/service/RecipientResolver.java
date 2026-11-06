package com.vmarket.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.vmarket.events.EventEnvelope;
import com.vmarket.notification.client.InternalClients;
import com.vmarket.notification.client.OrderSnapshot;
import com.vmarket.notification.client.ShopOwnerInfo;
import com.vmarket.notification.domain.ShopOwner;
import com.vmarket.notification.repository.ShopOwnerRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Tìm người nhận cho sự kiện chỉ mang {@code orderId} / {@code shopId}:
 * <ul>
 *   <li>Người mua và gian hàng của đơn: order-service.</li>
 *   <li>Chủ gian hàng: bản chiếu {@link ShopOwner}; chưa có (shop duyệt trước khi
 *       Notification chạy) thì tra shop-service rồi lưu cache vào bản chiếu.</li>
 * </ul>
 */
@Slf4j
@Service
public class RecipientResolver {

	private final InternalClients clients;
	private final ShopOwnerRepository shopOwners;
	private final Clock clock;

	public RecipientResolver(InternalClients clients, ShopOwnerRepository shopOwners, Clock clock) {
		this.clients = clients;
		this.shopOwners = shopOwners;
		this.clock = clock;
	}

	/** {@link Optional#empty()} nếu đơn không tồn tại (đã log cảnh báo). */
	public Optional<OrderSnapshot> order(String orderId) {
		Optional<OrderSnapshot> order = clients.order(orderId);
		if (order.isEmpty()) {
			log.warn("Không tìm thấy đơn {} ở order-service — bỏ qua thông báo", orderId);
		}
		return order;
	}

	/**
	 * Kết quả tra chủ gian hàng của một đơn.
	 *
	 * @param owners  shopId → ownerId đã tra được
	 * @param failure lỗi đầu tiên khi tra (shop-service lỗi / timeout), {@code null} nếu không có
	 */
	public record SellerLookup(Map<String, String> owners, RuntimeException failure) {
	}

	/**
	 * shopId → ownerId của các gian hàng trong đơn. KHÔNG ném lỗi: shop-service lỗi chỉ
	 * làm thiếu shop đó, lỗi được trả trong {@link SellerLookup#failure()} để consumer gửi
	 * xong cho buyer / các seller tra được rồi mới ném ra cho event bus retry. Shop không
	 * tồn tại thì bỏ qua (có log), không retry.
	 */
	public SellerLookup sellers(OrderSnapshot order) {
		Map<String, String> owners = new LinkedHashMap<>();
		RuntimeException failure = null;
		for (String shopId : order.shopIds()) {
			try {
				ownerOf(shopId).ifPresentOrElse(ownerId -> owners.put(shopId, ownerId),
						() -> log.warn("Không tìm thấy chủ gian hàng {} — không báo Seller cho đơn {}", shopId,
								order.id()));
			} catch (RuntimeException ex) {
				log.warn("Tra chủ gian hàng {} của đơn {} thất bại: {}", shopId, order.id(), ex.getMessage());
				if (failure == null) {
					failure = ex;
				}
			}
		}
		return new SellerLookup(owners, failure);
	}

	private Optional<String> ownerOf(String shopId) {
		Optional<ShopOwner> cached = shopOwners.findById(shopId);
		if (cached.isPresent() && cached.get().getOwnerId() != null) {
			return Optional.of(cached.get().getOwnerId());
		}
		Optional<ShopOwnerInfo> fetched = clients.shopOwner(shopId);
		fetched.filter(info -> info.ownerId() != null).ifPresent(info ->
				// eventTimestamp = 0: mọi sự kiện ShopApproved/ShopSuspended sau này đều ghi đè.
				shopOwners.save(new ShopOwner(shopId, info.ownerId(), info.name(), 0L, Instant.now(clock))));
		return fetched.map(ShopOwnerInfo::ownerId);
	}

	/** Cập nhật bản chiếu chủ gian hàng; bỏ qua sự kiện cũ hơn bản đang lưu. */
	public void rememberShopOwner(String shopId, String ownerId, String shopName, EventEnvelope envelope) {
		if (shopId == null || ownerId == null) {
			return;
		}
		Optional<ShopOwner> existing = shopOwners.findById(shopId);
		if (existing.isPresent() && existing.get().getEventTimestamp() > envelope.timestamp()) {
			return;
		}
		shopOwners.save(new ShopOwner(shopId, ownerId, shopName, envelope.timestamp(), Instant.now(clock)));
	}
}
