package com.vmarket.shop.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.shop.dto.PublicShopResponse;
import com.vmarket.shop.dto.ShopRequest;
import com.vmarket.shop.dto.ShopResponse;
import com.vmarket.shop.dto.ShopStatusHistoryResponse;
import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopAction;
import com.vmarket.shop.entity.ShopStatus;
import com.vmarket.shop.exception.ApiException;
import com.vmarket.shop.repository.ShopRepository;
import com.vmarket.shop.repository.ShopStatusHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Nghiệp vụ phía người bán và phía công khai:
 * <ul>
 *   <li>FR-SHOP-01 — đăng ký gian hàng (hồ sơ vào trạng thái "Chờ duyệt");</li>
 *   <li>FR-SHOP-02 — người bán xem / cập nhật gian hàng của mình, gửi lại hồ sơ bị từ chối;</li>
 *   <li>FR-SHOP-03 — trang gian hàng công khai.</li>
 * </ul>
 *
 * <p>Mọi thao tác của người bán tra theo {@code ownerId} (claim {@code sub}) — không
 * có đường nào nhận id gian hàng từ client, nên không thể chạm gian hàng của người
 * khác (IDOR).
 *
 * <p>Trùng tên / trùng chủ được kiểm tra trước để trả thông báo rõ ràng, nhưng chốt
 * chặn thật là ràng buộc UNIQUE ở CSDL: hai request song song cùng lọt qua bước kiểm
 * tra thì {@code saveAndFlush} làm CSDL từ chối ngay tại chỗ, và
 * {@code GlobalExceptionHandler} dịch ra đúng mã lỗi như khi kiểm tra trước bắt được.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopService {

	private final ShopRepository shopRepository;
	private final ShopStatusHistoryRepository historyRepository;
	private final ShopStatusTransitioner transitioner;
	private final ShopProfileAuditor profileAuditor;

	/** FR-SHOP-01 — nộp hồ sơ mở gian hàng. Mỗi tài khoản một gian hàng. */
	@Transactional
	public ShopResponse register(String ownerId, ShopRequest request) {
		if (shopRepository.existsByOwnerId(ownerId)) {
			throw ApiException.conflict("SHOP_ALREADY_EXISTS", "Bạn đã đăng ký một gian hàng");
		}
		ensureNameAvailable(request.name(), null);

		Shop shop = new Shop();
		shop.setOwnerId(ownerId);
		apply(shop, request);
		shop.setStatus(ShopStatus.PENDING);

		// saveAndFlush: cần id cho dòng lịch sử, và để ràng buộc UNIQUE lên tiếng ngay
		// tại đây nếu có request song song.
		Shop saved = shopRepository.saveAndFlush(shop);
		transitioner.recordSubmission(saved, ownerId);

		log.info("Đăng ký gian hàng id={} ownerId={} — chờ duyệt", saved.getId(), ownerId);
		return ShopResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public ShopResponse getMine(String ownerId) {
		return ShopResponse.from(mustFindMine(ownerId));
	}

	/**
	 * FR-SHOP-02 — cập nhật thông tin (thay thế toàn bộ, xem {@link ShopRequest}).
	 *
	 * <p>Được sửa khi "Chờ duyệt" (sửa lỗi trước khi Admin xem), "Bị từ chối" (sửa rồi
	 * gửi lại) và "Hoạt động". Sửa gian hàng đang hoạt động <b>không</b> đưa nó về chờ
	 * duyệt lại — FR-SHOP-02 cho người bán tự quản lý thông tin của mình, và đưa về chờ
	 * duyệt thì một lần sửa mô tả cũng làm gian hàng biến mất khỏi người mua.
	 *
	 * <p>Đổi lại, nội dung đã được duyệt có thể bị thay bằng nội dung vi phạm. Nên mọi
	 * trường bị đổi đều được ghi vết cho Admin đối chiếu — xem {@link ShopProfileAuditor}
	 * và {@code GET /api/shops/admin/{shopId}/profile-history}.
	 */
	@Transactional
	public ShopResponse updateMine(String ownerId, ShopRequest request) {
		Shop shop = mustFindMine(ownerId);
		if (!shop.getStatus().isEditableByOwner()) {
			throw ApiException.conflict("SHOP_SUSPENDED",
					"Gian hàng đang bị đình chỉ, không thể cập nhật thông tin");
		}
		ensureNameAvailable(request.name(), shop.getId());

		Map<String, String> before = ShopProfileAuditor.snapshot(shop);
		apply(shop, request);
		profileAuditor.record(shop, before, ShopProfileAuditor.snapshot(shop), ownerId);
		// saveAndFlush: đụng @Version / UNIQUE thì lỗi bung ra ở đây (được dịch thành 409),
		// và updatedAt trong response là giá trị thật đã ghi xuống.
		Shop saved = shopRepository.saveAndFlush(shop);
		log.info("Cập nhật gian hàng id={} ownerId={}", saved.getId(), ownerId);
		return ShopResponse.from(saved);
	}

	/** Gửi lại hồ sơ đã sửa sau khi bị từ chối (UC-15, luồng thay thế) → "Chờ duyệt". */
	@Transactional
	public ShopResponse resubmit(String ownerId) {
		Shop shop = mustFindMine(ownerId);
		transitioner.apply(shop, ShopAction.RESUBMIT, ownerId, null);
		Shop saved = shopRepository.saveAndFlush(shop);
		log.info("Gửi lại hồ sơ gian hàng id={} ownerId={}", saved.getId(), ownerId);
		return ShopResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public List<ShopStatusHistoryResponse> myHistory(String ownerId) {
		Shop shop = mustFindMine(ownerId);
		return historyRepository.findByShopIdOrderByCreatedAtAscIdAsc(shop.getId()).stream()
				.map(ShopStatusHistoryResponse::from)
				.toList();
	}

	/**
	 * FR-SHOP-03 — chỉ gian hàng đang hoạt động. Chờ duyệt / bị từ chối / bị đình chỉ
	 * đều trả 404 giống hệt "không tồn tại": người ngoài không cần (và không nên) biết một
	 * gian hàng đang bị xử lý.
	 */
	@Transactional(readOnly = true)
	public PublicShopResponse getPublic(String shopId) {
		return shopRepository.findByIdAndStatus(shopId, ShopStatus.ACTIVE)
				.map(PublicShopResponse::from)
				.orElseThrow(() -> ApiException.notFound("SHOP_NOT_FOUND", "Không tìm thấy gian hàng"));
	}

	private Shop mustFindMine(String ownerId) {
		return shopRepository.findByOwnerId(ownerId)
				.orElseThrow(() -> ApiException.notFound("SHOP_NOT_FOUND", "Bạn chưa đăng ký gian hàng"));
	}

	/** {@code exceptShopId = null} khi đăng ký mới; id gian hàng đang sửa khi cập nhật. */
	private void ensureNameAvailable(String rawName, String exceptShopId) {
		String nameKey = Shop.nameKeyOf(rawName);
		boolean taken = exceptShopId == null
				? shopRepository.existsByNameKey(nameKey)
				: shopRepository.existsByNameKeyAndIdNot(nameKey, exceptShopId);
		if (taken) {
			throw ApiException.conflict("SHOP_NAME_TAKEN", "Tên gian hàng đã được sử dụng");
		}
	}

	private void apply(Shop shop, ShopRequest request) {
		shop.setName(request.name());
		shop.setDescription(blankToNull(request.description()));
		shop.setLogoUrl(blankToNull(request.logoUrl()));
		shop.setCoverUrl(blankToNull(request.coverUrl()));
		shop.setPolicies(blankToNull(request.policies()));
		shop.setContactEmail(request.contactEmail().trim());
		shop.setContactPhone(request.contactPhone().trim());
		shop.setProvince(request.province().trim());
		shop.setDistrict(request.district().trim());
		shop.setWard(request.ward().trim());
		shop.setStreetAddress(request.streetAddress().trim());
	}

	/** "" và "chưa khai" không được trở thành hai trạng thái khác nhau trong CSDL. */
	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
