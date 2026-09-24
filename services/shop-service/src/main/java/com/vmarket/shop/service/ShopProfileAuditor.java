package com.vmarket.shop.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.shop.entity.Shop;
import com.vmarket.shop.entity.ShopProfileChange;
import com.vmarket.shop.repository.ShopProfileChangeRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Ghi vết mọi lần người bán sửa hồ sơ gian hàng (ra soát PR #24, mục A).
 *
 * <p><b>Vì sao cần:</b> FR-SHOP-02 cho người bán tự sửa thông tin, và sửa gian hàng
 * đang "Hoạt động" <b>không</b> đưa nó về "Chờ duyệt" — đưa về sẽ khiến gian hàng
 * biến mất khỏi người mua chỉ vì một lần sửa mô tả. Hệ quả: nội dung đã được Admin
 * duyệt có thể bị thay bằng nội dung vi phạm mà vẫn ACTIVE. Thay vì chặn sửa, ở đây
 * ghi lại đủ dấu vết để Admin đối chiếu và đình chỉ nếu cần (FR-SHOP-04).
 *
 * <p><b>Chụp ảnh trước / sau thay vì so với request:</b> {@code snapshot()} đọc thẳng
 * từ entity, nên giá trị đem so đã qua đúng bước chuẩn hoá của
 * {@code ShopService.apply} (trim, chuỗi rỗng → null, tên gộp khoảng trắng). So với
 * request thô sẽ đẻ ra những "thay đổi" giả như {@code "Tiệm  Gốm"} → {@code "Tiệm Gốm"}.
 *
 * <p>Không có thay đổi nào thì không ghi dòng nào — gửi lại đúng hồ sơ cũ (client
 * retry, bấm lưu hai lần) không làm nhật ký phình lên.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopProfileAuditor {

	private final ShopProfileChangeRepository changeRepository;

	/**
	 * Ảnh chụp các trường người bán sửa được. {@link LinkedHashMap} để nhật ký của một
	 * lần sửa giữ đúng thứ tự trường như trong hồ sơ.
	 */
	public static Map<String, String> snapshot(Shop shop) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("name", shop.getName());
		fields.put("description", shop.getDescription());
		fields.put("logoUrl", shop.getLogoUrl());
		fields.put("coverUrl", shop.getCoverUrl());
		fields.put("policies", shop.getPolicies());
		fields.put("contactEmail", shop.getContactEmail());
		fields.put("contactPhone", shop.getContactPhone());
		fields.put("province", shop.getProvince());
		fields.put("district", shop.getDistrict());
		fields.put("ward", shop.getWard());
		fields.put("streetAddress", shop.getStreetAddress());
		return fields;
	}

	/**
	 * Ghi một dòng cho mỗi trường thật sự đổi giá trị.
	 *
	 * <p>{@code MANDATORY}: phải nằm trong cùng transaction với thay đổi của gian hàng.
	 * Gọi ngoài transaction thì hồ sơ đổi mà nhật ký không đổi (hoặc ngược lại) — dấu
	 * vết không còn đáng tin.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(Shop shop, Map<String, String> before, Map<String, String> after, String actorId) {
		for (Map.Entry<String, String> entry : after.entrySet()) {
			String field = entry.getKey();
			String oldValue = before.get(field);
			String newValue = entry.getValue();
			if (Objects.equals(oldValue, newValue)) {
				continue;
			}

			ShopProfileChange change = new ShopProfileChange();
			change.setShopId(shop.getId());
			change.setFieldName(field);
			change.setOldValue(oldValue);
			change.setNewValue(newValue);
			change.setStatusAtChange(shop.getStatus());
			change.setChangedBy(actorId);
			changeRepository.save(change);

			// Không log giá trị: mô tả / chính sách có thể rất dài và là nội dung người
			// dùng nhập. Cần đối chiếu nội dung thì đọc nhật ký qua API của Admin.
			log.info("Gian hàng id={} đổi trường {} (trạng thái lúc sửa: {})",
					shop.getId(), field, shop.getStatus());
		}
	}
}
