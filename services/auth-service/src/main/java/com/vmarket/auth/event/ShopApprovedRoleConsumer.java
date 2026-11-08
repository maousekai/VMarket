package com.vmarket.auth.event;

import org.springframework.stereotype.Component;

import com.vmarket.auth.service.SellerRoleGrantService;
import com.vmarket.events.EventConsumer;
import com.vmarket.events.EventEnvelope;
import com.vmarket.events.EventType;
import com.vmarket.events.ShopApproved;

/**
 * Nhận {@code ShopApproved} từ shop-service và cấp role SELLER cho chủ gian hàng.
 *
 * <p>Áp dụng cho cả duyệt lần đầu và gỡ đình chỉ ({@code reinstated=true}): sau
 * khi bị đình chỉ, chủ shop vẫn giữ role SELLER (không có sự kiện thu hồi), nên
 * consumer chỉ cần đảm bảo role tồn tại.
 */
@Component
public class ShopApprovedRoleConsumer implements EventConsumer<ShopApproved> {

	private final SellerRoleGrantService sellerRoleGrantService;

	public ShopApprovedRoleConsumer(SellerRoleGrantService sellerRoleGrantService) {
		this.sellerRoleGrantService = sellerRoleGrantService;
	}

	@Override
	public String eventType() {
		return EventType.SHOP_APPROVED;
	}

	@Override
	public Class<ShopApproved> payloadType() {
		return ShopApproved.class;
	}

	@Override
	public void handle(ShopApproved payload, EventEnvelope envelope) {
		sellerRoleGrantService.grant(payload.ownerId());
	}
}
