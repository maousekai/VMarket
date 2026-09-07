package com.vmarket.user.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.user.dto.PageResponse;
import com.vmarket.user.dto.ProfileResponse;
import com.vmarket.user.dto.UpdateProfileRequest;
import com.vmarket.user.entity.UserProfile;
import com.vmarket.user.repository.UserProfileRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-USER-01 — Quản lý hồ sơ cá nhân, và phần tìm kiếm hồ sơ cho Admin của
 * FR-USER-04.
 *
 * <p><b>Hồ sơ được tạo lười (lazy) ở lần chạm đầu tiên.</b> auth-service tạo tài
 * khoản nhưng chưa phát sự kiện {@code UserRegistered}, và user-service chưa có
 * consumer RabbitMQ (thuộc ticket tích hợp sự kiện sau). Nếu bắt buộc phải có một
 * bước "tạo hồ sơ" riêng thì mọi người dùng vừa đăng ký sẽ nhận 404 khi mở trang
 * hồ sơ — vô lý với người dùng và bắt frontend xử lý thêm một trạng thái thừa.
 *
 * <p>Khi có sự kiện {@code UserRegistered}, consumer chỉ cần gọi đúng
 * {@link #getOrCreate(String)}: hành vi không đổi, hồ sơ chỉ được tạo sớm hơn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

	private final UserProfileRepository userProfileRepository;
	private final UserProfileProvisioner provisioner;

	/**
	 * Cố ý <b>không</b> đặt {@code readOnly = true}: lần gọi đầu tiên của một người
	 * dùng sẽ tạo hồ sơ, mà transaction read-only thì PostgreSQL từ chối mọi lệnh
	 * ghi ({@code cannot execute INSERT in a read-only transaction}).
	 */
	@Transactional
	public ProfileResponse getProfile(String userId) {
		return ProfileResponse.from(getOrCreate(userId));
	}

	@Transactional
	public ProfileResponse updateProfile(String userId, UpdateProfileRequest request) {
		UserProfile profile = getOrCreate(userId);

		// Ngữ nghĩa THAY THẾ: trường không gửi = null = xoá. Xem javadoc của
		// UpdateProfileRequest. Chuẩn hoá chuỗi rỗng thành null để "" và "chưa khai"
		// không trở thành hai trạng thái khác nhau trong CSDL.
		profile.setFullName(blankToNull(request.fullName()));
		profile.setAvatarUrl(blankToNull(request.avatarUrl()));
		profile.setPhone(blankToNull(request.phone()));
		profile.setDateOfBirth(request.dateOfBirth());
		profile.setGender(request.gender());

		log.info("Cập nhật hồ sơ userId={}", userId);
		return ProfileResponse.from(userProfileRepository.save(profile));
	}

	/** FR-USER-04 — Admin tìm kiếm / xem danh sách hồ sơ người dùng. */
	@Transactional(readOnly = true)
	public PageResponse<ProfileResponse> search(String keyword, Pageable pageable) {
		String q = (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim() + "%";
		return PageResponse.from(userProfileRepository.search(q, pageable), ProfileResponse::from);
	}

	/**
	 * Lấy hồ sơ, tạo mới nếu chưa có.
	 *
	 * <p>Hai request đầu tiên của cùng một người dùng có thể chạy song song và cùng
	 * thấy {@code findByUserId} rỗng (frontend gọi hai lần, hoặc người dùng bấm F5
	 * liên tục). Ràng buộc {@code UNIQUE(user_id)} khiến một trong hai insert trượt;
	 * việc chèn nằm trong transaction riêng nên bắt lại rồi đọc bản ghi mà request
	 * kia vừa tạo là an toàn — xem {@link UserProfileProvisioner}.
	 */
	@Transactional
	public UserProfile getOrCreate(String userId) {
		return userProfileRepository.findByUserId(userId)
				.orElseGet(() -> createOrReadExisting(userId));
	}

	private UserProfile createOrReadExisting(String userId) {
		try {
			UserProfile saved = provisioner.create(userId);
			log.info("Tạo hồ sơ rỗng cho userId={} ở lần truy cập đầu tiên", userId);
			return saved;
		} catch (DataIntegrityViolationException ex) {
			log.debug("Hồ sơ userId={} vừa được một request song song tạo trước", userId);
			return userProfileRepository.findByUserId(userId)
					.orElseThrow(() -> ex);
		}
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}
}
