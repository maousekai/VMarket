package com.vmarket.shop.service;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopAction;
import com.vmarket.shop.entity.ShopStatus;
import com.vmarket.shop.entity.ShopStatusHistory;
import com.vmarket.shop.event.ShopStatusChangedEvent;
import com.vmarket.shop.exception.ApiException;
import com.vmarket.shop.repository.ShopStatusHistoryRepository;

import lombok.RequiredArgsConstructor;

/**
 * Cửa DUY NHẤT để đổi trạng thái gian hàng. Mỗi lần đổi làm đủ ba việc, trong cùng
 * transaction với thay đổi của gian hàng:
 * <ol>
 *   <li>kiểm tra bước chuyển có hợp lệ theo {@link ShopAction} không;</li>
 *   <li>ghi một dòng {@link ShopStatusHistory} (ai, lúc nào, lý do gì);</li>
 *   <li>đăng ký {@link ShopStatusChangedEvent} — chỉ được phát lên Event Bus nếu
 *       transaction commit thành công (xem {@code ShopEventPublisher}).</li>
 * </ol>
 * Gom vào một chỗ để không service nào đổi {@code status} mà quên ghi lịch sử hoặc
 * quên phát sự kiện.
 *
 * <p>{@code MANDATORY}: gọi ngoài transaction là lỗi lập trình — lịch sử và trạng thái
 * sẽ được lưu rời nhau, và sự kiện sau-commit không bao giờ được phát.
 */
@Component
@RequiredArgsConstructor
public class ShopStatusTransitioner {

	private final ShopStatusHistoryRepository historyRepository;
	private final ApplicationEventPublisher applicationEventPublisher;

	/** Ghi bước đầu tiên của hồ sơ vừa nộp (chưa có trạng thái nguồn). Gian hàng phải đã có id. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordSubmission(Shop shop, String ownerId) {
		saveHistory(shop.getId(), null, shop.getStatus(), ownerId, null);
	}

	/**
	 * Thực hiện {@code action} trên gian hàng.
	 *
	 * @param reason lý do (bắt buộc với REJECT / SUSPEND — đã kiểm ở tầng request);
	 *               trở thành {@code statusReason} hiển thị cho người bán
	 * @throws ApiException 409 {@code INVALID_STATUS_TRANSITION} nếu trạng thái hiện tại
	 *                      không cho phép hành động này
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void apply(Shop shop, ShopAction action, String actorId, String reason) {
		ShopStatus current = shop.getStatus();
		if (!action.isAllowedFrom(current)) {
			throw ApiException.conflict("INVALID_STATUS_TRANSITION",
					"Không thể " + action.label() + " gian hàng đang ở trạng thái \"" + current.label() + "\"");
		}

		shop.setStatus(action.to());
		// Lý do chỉ có nghĩa với trạng thái vừa chuyển tới: gửi lại / duyệt thì xoá lý
		// do từ chối cũ, để người bán không còn thấy "Bị từ chối vì..." trên hồ sơ đã
		// được duyệt.
		shop.setStatusReason(reason);
		if (action.to() == ShopStatus.ACTIVE && shop.getApprovedAt() == null) {
			shop.setApprovedAt(Instant.now());
		}

		saveHistory(shop.getId(), current, action.to(), actorId, reason);
		applicationEventPublisher.publishEvent(new ShopStatusChangedEvent(
				shop.getId(), shop.getOwnerId(), shop.getName(), action, actorId, reason));
	}

	private void saveHistory(String shopId, ShopStatus from, ShopStatus to, String actorId, String reason) {
		ShopStatusHistory history = new ShopStatusHistory();
		history.setShopId(shopId);
		history.setFromStatus(from);
		history.setToStatus(to);
		history.setChangedBy(actorId);
		history.setReason(reason);
		historyRepository.save(history);
	}
}
