package com.vmarket.user.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.user.entity.UserProfile;
import com.vmarket.user.repository.UserProfileRepository;

import lombok.RequiredArgsConstructor;

/**
 * Chèn hồ sơ rỗng trong một transaction RIÊNG.
 *
 * <p>Tồn tại như một bean tách rời chỉ vì một lý do kỹ thuật, nhưng là lý do bắt
 * buộc: trên PostgreSQL, khi một câu lệnh vi phạm ràng buộc thì <b>cả transaction
 * bị abort</b> — mọi truy vấn tiếp theo trong cùng transaction đó đều lỗi
 * {@code current transaction is aborted}. Nên không thể "thử insert, trượt thì
 * đọc lại" trong cùng một transaction (auth-service đã gặp đúng vấn đề này ở
 * {@code RegistrationService} và chọn cách không đọc lại).
 *
 * <p>{@code REQUIRES_NEW} khiến insert chạy trong transaction con: nếu nó trượt vì
 * request song song vừa tạo hồ sơ trước, chỉ transaction con bị huỷ, transaction
 * cha vẫn khoẻ và đọc lại được bản ghi kia.
 *
 * <p>Phải là bean riêng vì Spring áp {@code @Transactional} qua proxy — gọi thẳng
 * một method của chính mình sẽ bỏ qua proxy và mất luôn {@code REQUIRES_NEW}.
 */
@Component
@RequiredArgsConstructor
class UserProfileProvisioner {

	private final UserProfileRepository userProfileRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	UserProfile create(String userId) {
		UserProfile profile = new UserProfile();
		profile.setUserId(userId);
		return userProfileRepository.saveAndFlush(profile);
	}
}
