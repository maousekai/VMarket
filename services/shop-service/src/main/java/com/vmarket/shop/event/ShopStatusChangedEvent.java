package com.vmarket.shop.event;

import com.vmarket.shop.entity.ShopAction;

/**
 * Sự kiện NỘI BỘ (Spring {@code ApplicationEvent}, không lên RabbitMQ): một gian hàng
 * vừa đổi trạng thái trong transaction hiện tại. {@link ShopEventPublisher} nhận nó
 * <b>sau khi commit</b> rồi mới dịch sang sự kiện Event Bus.
 *
 * <p>Chụp sẵn mọi giá trị cần dùng thay vì giữ tham chiếu tới entity: listener chạy
 * sau commit, lúc đó entity đã tách khỏi persistence context.
 */
public record ShopStatusChangedEvent(
		String shopId,
		String ownerId,
		String shopName,
		ShopAction action,
		String actorId,
		String reason) {
}
