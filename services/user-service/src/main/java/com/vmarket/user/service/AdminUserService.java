package com.vmarket.user.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.vmarket.user.client.AuthAccount;
import com.vmarket.user.client.AuthAccountActivityPage;
import com.vmarket.user.client.AuthAccountPage;
import com.vmarket.user.client.AuthAccountSearch;
import com.vmarket.user.client.AuthServiceClient;
import com.vmarket.user.dto.AccountStatus;
import com.vmarket.user.dto.AdminUserResponse;
import com.vmarket.user.dto.PageResponse;
import com.vmarket.user.dto.UserActivityResponse;
import com.vmarket.user.entity.UserProfile;
import com.vmarket.user.exception.ApiException;
import com.vmarket.user.repository.UserProfileRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-USER-04 — Admin quản lý người dùng: tìm kiếm, xem, khoá / mở khoá, xem lịch sử
 * hoạt động cơ bản.
 *
 * <p><b>Dữ liệu nằm ở hai service.</b> Email, username, vai trò, trạng thái khoá và lịch
 * sử hoạt động thuộc auth-service; họ tên, số điện thoại, ảnh thuộc user-service. Danh
 * sách lấy từ auth-service (nơi có <i>mọi</i> tài khoản — hồ sơ ở đây chỉ được tạo khi
 * người dùng mở trang hồ sơ lần đầu) rồi ghép hồ sơ vào.
 *
 * <p><b>Tìm kiếm một ô cho cả hai CSDL:</b> tìm userId có họ tên/SĐT khớp ở đây trước,
 * gửi kèm từ khoá sang auth-service để lọc "email/username khớp HOẶC id thuộc danh sách
 * đó". Phân trang và lọc trạng thái diễn ra một chỗ duy nhất (auth-service) nên tổng số
 * bản ghi và số trang đúng — không phải ghép hai trang kết quả rồi đếm lại.
 *
 * <p><b>Giới hạn {@value AuthAccountSearch#MAX_USER_IDS} hồ sơ khớp họ tên/SĐT.</b> Vượt
 * giới hạn thì trả 400 {@code SEARCH_TOO_BROAD} để Admin gõ cụ thể hơn, KHÔNG cắt bớt
 * danh sách rồi tìm tiếp: cắt trước khi lọc trạng thái sẽ bỏ sót đúng những người cần
 * tìm (vd. hồ sơ LOCKED cũ nhất rơi khỏi 500 hồ sơ mới nhất) mà tổng số vẫn trông như
 * đầy đủ (review PR #22, m2).
 *
 * <p>Vai trò ADMIN trong access token đã được kiểm ở {@code SecurityConfig} và controller;
 * mọi lời gọi còn truyền {@code adminId} để auth-service kiểm tra lại theo CSDL (Admin
 * vừa bị khoá / mất vai trò vẫn cầm token cũ tới 15 phút).
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

	public PageResponse<AdminUserResponse> search(String keyword, AccountStatus status, int page, int size,
			String adminId) {
		String q = keyword == null || keyword.isBlank() ? null : keyword.trim();
		List<String> profileMatches = q == null ? List.of() : profileMatches(q);

		AuthAccountPage accounts = authServiceClient.searchAccounts(new AuthAccountSearch(
				q, status == null ? null : status.toAuthFilter(), profileMatches, page, size), adminId);

		Map<String, UserProfile> profiles = profilesOf(accounts.items());
		List<AdminUserResponse> items = accounts.items().stream()
				.map(a -> AdminUserResponse.of(a, profiles.get(a.userId())))
				.toList();
		return new PageResponse<>(items, accounts.page(), accounts.size(),
				accounts.totalElements(), accounts.totalPages());
	}

	public AdminUserResponse get(String userId, String adminId) {
		return withProfile(authServiceClient.getAccount(userId, adminId));
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

	/**
	 * Mở khoá. {@code adminId} được gửi sang auth-service: Admin đang bị khoá dùng access
	 * token cũ gọi mở khoá chính mình sẽ bị từ chối 403 {@code ADMIN_ACCESS_REVOKED}
	 * (review PR #22, M2).
	 */
	public AdminUserResponse unlock(String userId, String adminId) {
		AuthAccount account = authServiceClient.unsuspend(userId, adminId);
		log.info("Admin {} mở khoá tài khoản userId={}", adminId, userId);
		return withProfile(account);
	}

	public PageResponse<UserActivityResponse> activities(String userId, int page, int size, String adminId) {
		AuthAccountActivityPage result = authServiceClient.getActivities(userId, page, size, adminId);
		return new PageResponse<>(result.items().stream().map(UserActivityResponse::of).toList(),
				result.page(), result.size(), result.totalElements(), result.totalPages());
	}

	/**
	 * userId có họ tên/SĐT khớp {@code q}. Lấy dư một bản ghi để biết có vượt giới hạn
	 * hay không, vượt thì báo lỗi thay vì cắt bớt (xem javadoc lớp).
	 */
	private List<String> profileMatches(String q) {
		List<String> ids = userProfileRepository.findUserIdsByKeyword("%" + q.toLowerCase(Locale.ROOT) + "%",
				PageRequest.of(0, AuthAccountSearch.MAX_USER_IDS + 1));
		if (ids.size() > AuthAccountSearch.MAX_USER_IDS) {
			throw ApiException.badRequest("SEARCH_TOO_BROAD",
					"Từ khoá khớp hơn " + AuthAccountSearch.MAX_USER_IDS + " hồ sơ theo họ tên / số điện thoại. "
							+ "Hãy nhập cụ thể hơn (họ tên đầy đủ, email, username hoặc số điện thoại)");
		}
		return ids;
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
