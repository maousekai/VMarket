package com.vmarket.shop.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.shop.dto.PageResponse;
import com.vmarket.shop.dto.ShopResponse;
import com.vmarket.shop.dto.ShopStatusHistoryResponse;
import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopAction;
import com.vmarket.shop.entity.ShopStatus;
import com.vmarket.shop.exception.ApiException;
import com.vmarket.shop.repository.ShopRepository;
import com.vmarket.shop.repository.ShopSpecifications;
import com.vmarket.shop.repository.ShopStatusHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-SHOP-04 — [Admin] Duyệt gian hàng: xem danh sách / chi tiết, duyệt, từ chối (kèm
 * lý do), đình chỉ (kèm lý do), gỡ đình chỉ.
 *
 * <p>Mọi thay đổi trạng thái đi qua {@link ShopStatusTransitioner} (kiểm tra bước
 * chuyển + ghi lịch sử + phát sự kiện sau commit). {@code saveAndFlush} sau mỗi bước để
 * xung đột {@code @Version} với một thao tác song song lộ ra ngay trong transaction,
 * trước khi response được dựng.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopModerationService {

	/** Mới nộp trước; id là khoá phụ để thứ tự ổn định giữa các trang khi trùng thời điểm. */
	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private final ShopRepository shopRepository;
	private final ShopStatusHistoryRepository historyRepository;
	private final ShopStatusTransitioner transitioner;

	@Transactional(readOnly = true)
	public PageResponse<ShopResponse> search(ShopStatus status, String keyword, int page, int size) {
		var result = shopRepository.findAll(ShopSpecifications.matching(status, keyword),
				PageRequest.of(page, size, NEWEST_FIRST));
		return PageResponse.from(result, ShopResponse::from);
	}

	@Transactional(readOnly = true)
	public ShopResponse get(String shopId) {
		return ShopResponse.from(mustFind(shopId));
	}

	@Transactional(readOnly = true)
	public List<ShopStatusHistoryResponse> history(String shopId) {
		mustFind(shopId);
		return historyRepository.findByShopIdOrderByCreatedAtAscIdAsc(shopId).stream()
				.map(ShopStatusHistoryResponse::from)
				.toList();
	}

	/** Chờ duyệt → Hoạt động; phát {@code ShopApproved}. */
	@Transactional
	public ShopResponse approve(String adminId, String shopId) {
		return moderate(adminId, shopId, ShopAction.APPROVE, null);
	}

	/** Chờ duyệt → Bị từ chối (kèm lý do). Người bán sửa rồi gửi lại được. */
	@Transactional
	public ShopResponse reject(String adminId, String shopId, String reason) {
		return moderate(adminId, shopId, ShopAction.REJECT, reason);
	}

	/** Hoạt động → Bị đình chỉ (kèm lý do); phát {@code ShopSuspended}. */
	@Transactional
	public ShopResponse suspend(String adminId, String shopId, String reason) {
		return moderate(adminId, shopId, ShopAction.SUSPEND, reason);
	}

	/** Bị đình chỉ → Hoạt động; phát {@code ShopApproved} với {@code reinstated = true}. */
	@Transactional
	public ShopResponse reinstate(String adminId, String shopId) {
		return moderate(adminId, shopId, ShopAction.REINSTATE, null);
	}

	private ShopResponse moderate(String adminId, String shopId, ShopAction action, String reason) {
		Shop shop = mustFind(shopId);
		transitioner.apply(shop, action, adminId, reason == null ? null : reason.trim());
		Shop saved = shopRepository.saveAndFlush(shop);
		log.info("Admin {} thực hiện {} gian hàng id={}", adminId, action, shopId);
		return ShopResponse.from(saved);
	}

	private Shop mustFind(String shopId) {
		return shopRepository.findById(shopId)
				.orElseThrow(() -> ApiException.notFound("SHOP_NOT_FOUND", "Không tìm thấy gian hàng"));
	}
}
