package com.vmarket.user.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.vmarket.user.client.AuthAccount;
import com.vmarket.user.client.AuthAccountPage;
import com.vmarket.user.client.AuthAccountSearch;
import com.vmarket.user.client.AuthServiceClient;
import com.vmarket.user.dto.AccountStatus;
import com.vmarket.user.dto.AdminUserResponse;
import com.vmarket.user.dto.PageResponse;
import com.vmarket.user.entity.UserProfile;
import com.vmarket.user.repository.UserProfileRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-USER-04 — Admin quản lý người dùng: tìm kiếm, xem, khoá / mở khoá.
 *
 * <p><b>Dữ liệu nằm ở hai service.</b> Email, username, vai trò, trạng thái khoá thuộc
 * auth-service; họ tên, số điện thoại, ảnh thuộc user-service. Danh sách lấy từ
 * auth-service (nơi có <i>mọi</i> tài khoản — hồ sơ ở đây chỉ được tạo khi người dùng
 * mở trang hồ sơ lần đầu) rồi ghép hồ sơ vào.
 *
 * <p><b>Tìm kiếm một ô cho cả hai CSDL:</b> tìm userId có họ tên/SĐT khớp ở đây trước,
 * gửi kèm từ khoá sang auth-service để lọc "email/username khớp HOẶC id thuộc danh sách
 * đó". Phân trang và lọc trạng thái diễn ra một chỗ duy nhất (auth-service) nên tổng số
 * bản ghi và số trang luôn đúng — không phải ghép hai trang kết quả rồi đếm lại.
 *
 * <p>Giới hạn đã biết: tối đa {@value AuthAccountSearch#MAX_USER_IDS} hồ sơ khớp họ
 * tên/SĐT được xét (mới nhất trước). Từ khoá rộng tới mức đó thì Admin cần gõ cụ thể hơn.
 *
 * <p>Vai trò ADMIN đã được kiểm tra ở controller ({@code @PreAuthorize}).
 *
 * <p>Cố ý <b>không</b> {@code @Transactional}: mỗi method gọi HTTP sang auth-service, bọc
 * transaction sẽ giữ một kết nối CSDL suốt thời gian chờ mạng. Các truy vấn hồ sơ ở đây
 * chỉ đọc, mỗi câu tự đủ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

	private final AuthServiceClient authServiceClient;
	private final UserProfileRepository userProfileRepository;

	public PageResponse<AdminUserResponse> search(String keyword, AccountStatus status, int page, int size) {
		String q = keyword == null || keyword.isBlank() ? null : keyword.trim();
		List<String> profileMatches = q == null
				? List.of()
				: userProfileRepository.findUserIdsByKeyword("%" + q.toLowerCase(Locale.ROOT) + "%",
						PageRequest.of(0, AuthAccountSearch.MAX_USER_IDS));

		AuthAccountPage accounts = authServiceClient.searchAccounts(new AuthAccountSearch(
				q, status == null ? null : status.toAuthFilter(), profileMatches, page, size));

		Map<String, UserProfile> profiles = profilesOf(accounts.items());
		List<AdminUserResponse> items = accounts.items().stream()
				.map(a -> AdminUserResponse.of(a, profiles.get(a.userId())))
				.toList();
		return new PageResponse<>(items, accounts.page(), accounts.size(),
				accounts.totalElements(), accounts.totalPages());
	}

	public AdminUserResponse get(String userId) {
		return withProfile(authServiceClient.getAccount(userId));
	}

	/**
	 * Khoá tài khoản. {@code adminId} lấy từ access token của Admin (không nhận từ body)
	 * để lịch sử "ai khoá" không giả mạo được. Chặn tự khoá mình do auth-service đảm nhận
	 * ({@code CANNOT_LOCK_SELF}).
	 */
	public AdminUserResponse lock(String userId, String reason, String adminId) {
		AuthAccount account = authServiceClient.suspend(userId, reason.trim(), adminId);
		log.warn("Admin {} khoá tài khoản userId={}", adminId, userId);
		return withProfile(account);
	}

	public AdminUserResponse unlock(String userId, String adminId) {
		AuthAccount account = authServiceClient.unsuspend(userId);
		log.info("Admin {} mở khoá tài khoản userId={}", adminId, userId);
		return withProfile(account);
	}

	private AdminUserResponse withProfile(AuthAccount account) {
		return AdminUserResponse.of(account, userProfileRepository.findByUserId(account.userId()).orElse(null));
	}

	private Map<String, UserProfile> profilesOf(List<AuthAccount> accounts) {
		if (accounts.isEmpty()) {
			return Map.of();
		}
		return userProfileRepository.findByUserIdIn(accounts.stream().map(AuthAccount::userId).toList()).stream()
				.collect(Collectors.toMap(UserProfile::getUserId, Function.identity()));
	}
}
