package com.vmarket.auth.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cấp vai trò SELLER khi gian hàng được duyệt hoặc được gỡ đình chỉ.
 *
 * <p>Cố ý <b>không</b> bọc cả phương thức trong một transaction: mỗi lệnh
 * repository tự chạy trong transaction riêng, nên khi hai consumer/instance cùng
 * xử lý một sự kiện, kẻ thua cuộc chỉ rollback lệnh insert của chính nó — không
 * làm hỏng một transaction lớn hơn. Đây là lý do không dùng
 * {@code @Transactional} + catch {@link DataIntegrityViolationException} trong
 * cùng một transaction (PostgreSQL đã đánh dấu transaction aborted).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerRoleGrantService {

	private final UserRepository users;
	private final RoleRepository roles;
	private final UserRoleRepository userRoles;

	public void grant(String ownerId) {
		if (ownerId == null || ownerId.isBlank()) {
			log.warn("ShopApproved thiếu ownerId — bỏ qua");
			return;
		}
		if (!users.existsById(ownerId)) {
			// Không retry được: tài khoản không tồn tại thì sự kiện có lặp lại vẫn vậy.
			log.warn("ShopApproved cho userId không tồn tại: {} — bỏ qua", ownerId);
			return;
		}

		Role seller = roles.findByName(RoleName.SELLER)
				.orElseThrow(() -> new IllegalStateException(
						"Thiếu role SELLER trong bảng roles — kiểm tra migration/seed"));

		if (userRoles.existsByUserIdAndRoleId(ownerId, seller.getId())) {
			log.debug("userId={} đã có role SELLER — bỏ qua", ownerId);
			return;
		}

		try {
			userRoles.saveAndFlush(new UserRole(ownerId, seller.getId()));
			log.info("Đã cấp role SELLER cho userId={}", ownerId);
		} catch (DataIntegrityViolationException duplicate) {
			// Hai instance cùng nhận một event: bên kia vừa insert trước.
			log.debug("Role SELLER của userId={} vừa được instance khác cấp", ownerId);
		}
	}
}
