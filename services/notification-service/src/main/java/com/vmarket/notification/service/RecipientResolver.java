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
	 * shopId → ownerId của các gian hàng trong đơn. Lỗi mạng khi tra shop-service được
	 * ném ra để event bus retry; shop không tồn tại thì bỏ qua (có log).
	 */
	public Map<String, String> sellers(OrderSnapshot order) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String shopId : order.shopIds()) {
			ownerOf(shopId).ifPresentOrElse(ownerId -> result.put(shopId, ownerId),
					() -> log.warn("Không tìm thấy chủ gian hàng {} — không báo Seller cho đơn {}", shopId, order.id()));
		}
		return result;
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
