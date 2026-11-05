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
import com.vmarket.notification.domain.ShopOwner;
import com.vmarket.notification.repository.ShopOwnerRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Tìm người nhận cho sự kiện chỉ mang {@code orderId} / {@code shopId}:
 * người mua lấy từ order-service, chủ gian hàng lấy từ bản chiếu {@link ShopOwner}.
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

	/** shopId → ownerId của các gian hàng trong đơn mà Notification đã biết chủ. */
	public Map<String, String> sellers(OrderSnapshot order) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String shopId : order.shopIds()) {
			Optional<ShopOwner> owner = shopOwners.findById(shopId);
			if (owner.isPresent() && owner.get().getOwnerId() != null) {
				result.put(shopId, owner.get().getOwnerId());
			} else {
				log.warn("Chưa biết chủ gian hàng {} (chưa nhận ShopApproved) — không báo Seller cho đơn {}",
						shopId, order.id());
			}
		}
		return result;
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
