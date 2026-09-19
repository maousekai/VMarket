package com.vmarket.auth.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.auth.dto.internal.AccountPageResponse;
import com.vmarket.auth.dto.internal.AccountResponse;
import com.vmarket.auth.dto.internal.AccountSearchRequest;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-USER-04 — phần dữ liệu tài khoản của "Admin quản lý người dùng": tìm kiếm, xem,
 * khoá / mở khoá.
 *
 * <p>Endpoint công khai cho Admin nằm ở user-service; service này chỉ phục vụ qua API
 * nội bộ vì email, username, vai trò và trạng thái khoá đều thuộc CSDL auth-service
 * (database-per-service). Không kiểm tra vai trò ADMIN ở đây — việc đó là của
 * user-service trước khi gọi; ở đây chỉ tin lời gọi đã qua {@code INTERNAL_API_KEY}.
 *
 * <p><b>Khoá bởi Admin khác khoá tạm do đăng nhập sai:</b> dùng cột {@code suspended_*}
 * riêng, không tự hết hạn, không bị gỡ bởi đăng nhập đúng hay đặt lại mật khẩu. Khoá
 * xong thu hồi mọi refresh token (SRS FR-AUTH-06); access token đang còn sống vẫn dùng
 * được tới khi hết hạn (≤ 15 phút, NFR-SEC-02) vì JWT không thu hồi được.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountAdminService {

	private final UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final UserRoleRepository userRoleRepository;
	private final RefreshTokenRepository refreshTokenRepository;

	@Transactional(readOnly = true)
	public AccountResponse get(String userId) {
		User user = mustFind(userId);
		return AccountResponse.from(user, rolesByUserId(List.of(userId)).getOrDefault(userId, List.of()), Instant.now());
	}

	@Transactional(readOnly = true)
	public AccountPageResponse search(AccountSearchRequest request) {
		// Mới tạo trước; id (ULID, tăng theo thời gian) làm khoá phụ để thứ tự ổn định
		// giữa các trang khi nhiều tài khoản trùng created_at.
		var pageable = PageRequest.of(request.page(), request.size(),
				Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		Page<User> page = userRepository.findAll(searchSpec(request), pageable);

		Map<String, List<String>> roles = rolesByUserId(page.getContent().stream().map(User::getId).toList());
		Instant now = Instant.now();
		List<AccountResponse> items = page.getContent().stream()
				.map(u -> AccountResponse.from(u, roles.getOrDefault(u.getId(), List.of()), now))
				.toList();
		return new AccountPageResponse(items, page.getNumber(), page.getSize(),
				page.getTotalElements(), page.getTotalPages());
	}

	@Transactional
	public AccountResponse suspend(String userId, String reason, String actorId) {
		if (userId.equals(actorId)) {
			// Chặn Admin tự khoá mình: khoá nhầm tài khoản Admin cuối cùng thì không còn
			// ai mở khoá được ngoài sửa tay CSDL.
			throw new ApiException("CANNOT_LOCK_SELF", HttpStatus.BAD_REQUEST,
					"Không thể tự khoá tài khoản của chính mình");
		}
		mustFind(userId);
		Instant now = Instant.now();
		if (userRepository.suspendIfActive(userId, now, reason.trim(), actorId) == 1) {
			int revoked = refreshTokenRepository.revokeAllActiveByUserId(userId, now);
			log.warn("Admin {} khoá tài khoản userId={}; thu hồi {} phiên", actorId, userId, revoked);
		} else {
			log.info("Tài khoản userId={} đã bị khoá từ trước, giữ nguyên lần khoá đầu", userId);
		}
		return get(userId);
	}

	@Transactional
	public AccountResponse unsuspend(String userId) {
		mustFind(userId);
		userRepository.unsuspend(userId, Instant.now());
		log.info("Mở khoá tài khoản userId={}", userId);
		return get(userId);
	}

	static ApiException userNotFound() {
		return new ApiException("USER_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản");
	}

	// --- helpers -------------------------------------------------------------

	private User mustFind(String userId) {
		return userRepository.findById(userId).orElseThrow(AccountAdminService::userNotFound);
	}

	/**
	 * {@code status} VÀ ({@code q} khớp email/username HOẶC id thuộc {@code userIds}).
	 * Không có cả {@code q} lẫn {@code userIds} → không lọc theo từ khoá.
	 *
	 * <p>{@code lower(...) like} thay vì {@code ilike} để chạy được cả PostgreSQL lẫn H2
	 * (test). Tham số hoá qua Criteria nên không có SQL injection; {@code %}/{@code _}
	 * người dùng gõ chỉ làm kết quả rộng hơn.
	 */
	private static Specification<User> searchSpec(AccountSearchRequest request) {
		String keyword = request.q() == null || request.q().isBlank()
				? null
				: "%" + request.q().trim().toLowerCase(Locale.ROOT) + "%";
		Collection<String> ids = request.userIds() == null ? List.of() : request.userIds();
		AccountSearchRequest.AccountFilterStatus status = request.status();

		return (root, query, cb) -> {
			List<Predicate> and = new ArrayList<>();
			if (status == AccountSearchRequest.AccountFilterStatus.SUSPENDED) {
				and.add(cb.isNotNull(root.get("suspendedAt")));
			} else if (status == AccountSearchRequest.AccountFilterStatus.ACTIVE) {
				and.add(cb.isNull(root.get("suspendedAt")));
			}

			List<Predicate> or = new ArrayList<>();
			if (keyword != null) {
				or.add(cb.like(cb.lower(root.get("email")), keyword));
				or.add(cb.like(cb.lower(root.get("username")), keyword));
			}
			if (!ids.isEmpty()) {
				or.add(root.get("id").in(ids));
			}
			if (!or.isEmpty()) {
				and.add(cb.or(or.toArray(Predicate[]::new)));
			}
			return cb.and(and.toArray(Predicate[]::new));
		};
	}

	/** userId → tên vai trò (sắp xếp), nạp bằng 2 truy vấn cho cả trang. */
	private Map<String, List<String>> rolesByUserId(Collection<String> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		Map<String, String> roleNames = roleRepository.findAll().stream()
				.collect(Collectors.toMap(Role::getId, r -> r.getName().name()));
		Map<String, List<String>> result = new TreeMap<>();
		for (UserRole ur : userRoleRepository.findByUserIdIn(userIds)) {
			String name = roleNames.get(ur.getRoleId());
			if (name != null) {
				result.computeIfAbsent(ur.getUserId(), k -> new ArrayList<>()).add(name);
			}
		}
		result.values().forEach(list -> list.sort(null));
		return result;
	}
}
