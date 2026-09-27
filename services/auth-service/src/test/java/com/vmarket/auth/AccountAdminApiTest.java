package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.vmarket.auth.config.InternalApiProperties;
import com.vmarket.auth.controller.InternalAccountController;
import com.vmarket.auth.entity.AccountActivityType;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.AccountActivityRepository;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.security.OpaqueTokenCodec;

/**
 * FR-USER-04 — API nội bộ cho Admin quản lý tài khoản: xem, tìm kiếm, khoá, mở khoá,
 * lịch sử hoạt động; hiệu lực của khoá lên đăng nhập / làm mới phiên; và việc kiểm tra
 * Admin thực hiện ({@code X-Actor-Id}) theo CSDL chứ không theo access token.
 *
 * <p>Các tình huống hai transaction chạy xen nhau (khoá trong lúc đăng nhập / đổi mật
 * khẩu...) nằm ở {@code AccountSuspensionRaceTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountAdminApiTest {

	@Autowired MockMvc mockMvc;
	@Autowired RoleRepository roleRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserRoleRepository userRoleRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;
	@Autowired AccountActivityRepository activityRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired OpaqueTokenCodec tokenCodec;
	@Autowired InternalApiProperties internalApiProperties;
	@Autowired TransactionTemplate transactionTemplate;

	private static final String PASSWORD = "Abcd1234@";
	private static final String UNKNOWN_ID = "01JBQ9YDX7K3M8N5P2R4T6V8ZZ";

	private User admin;
	private User an;
	private User binh;

	@BeforeEach
	void seed() {
		admin = seedUser("admin@example.com", "admin.vmarket", RoleName.ADMIN);
		an = seedUser("an.nguyen@example.com", "an.nguyen", RoleName.BUYER);
		binh = seedUser("binh.tran@example.com", "binh.tran", RoleName.SELLER);
	}

	@AfterEach
	void cleanup() {
		activityRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		userRoleRepository.deleteAll();
		userRepository.deleteAll();
	}

	private User seedUser(String email, String username, RoleName roleName) {
		Role role = roleRepository.findByName(roleName).orElseGet(() -> {
			Role r = new Role();
			r.setName(roleName);
			return roleRepository.save(r);
		});
		User u = new User();
		u.setEmail(email);
		u.setUsername(username);
		u.setPasswordHash(passwordEncoder.encode(PASSWORD));
		u.setEmailVerified(true);
		userRepository.save(u);
		userRoleRepository.save(new UserRole(u.getId(), role.getId()));
		return u;
	}

	/** Gọi API nội bộ với khoá nội bộ đúng, Admin thực hiện = {@code actorId}. */
	private ResultActions internalAs(String actorId, MockHttpServletRequestBuilder req) throws Exception {
		return mockMvc.perform(req.header(InternalApiProperties.HEADER, internalApiProperties.getApiKey())
				.header(InternalAccountController.ACTOR_HEADER, actorId)
				.contentType(MediaType.APPLICATION_JSON));
	}

	private ResultActions internal(MockHttpServletRequestBuilder req) throws Exception {
		return internalAs(admin.getId(), req);
	}

	private ResultActions suspend(String userId, String reason, String actorId) throws Exception {
		return internalAs(actorId, put("/internal/users/" + userId + "/suspension")
				.content("{\"reason\":\"" + reason + "\"}"));
	}

	private ResultActions unsuspend(String userId, String actorId) throws Exception {
		return internalAs(actorId, delete("/internal/users/" + userId + "/suspension"));
	}

	private ResultActions activities(String userId) throws Exception {
		return internal(get("/internal/users/" + userId + "/activities"));
	}

	private ResultActions search(String body) throws Exception {
		return internal(post("/internal/users/search").content(body));
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	private boolean isSuspended(User u) {
		return userRepository.findById(u.getId()).orElseThrow().isSuspended();
	}

	private long activityCount(User u) {
		return activityRepository.findByUserId(u.getId(), Pageable.unpaged()).getTotalElements();
	}

	// --- khoá / mở khoá ------------------------------------------------------

	@Test
	void khoa_200_chanDangNhap_vaThuHoiRefreshToken() throws Exception {
		String refreshToken = JsonPath.read(login(an.getEmail(), PASSWORD).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(), "$.refreshToken");

		suspend(an.getId(), "Spam đánh giá", admin.getId())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suspended").value(true))
				.andExpect(jsonPath("$.suspendedReason").value("Spam đánh giá"))
				.andExpect(jsonPath("$.suspendedBy").value(admin.getId()))
				.andExpect(jsonPath("$.suspendedAt").isNotEmpty());

		assertThat(refreshTokenRepository.findByTokenHash(tokenCodec.hash(refreshToken)).orElseThrow().getRevokedAt())
				.isNotNull();
		login(an.getEmail(), PASSWORD)
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
		mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refreshToken + "\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void khoa_saiMatKhau_vanTraInvalidCredentials_khongLoTrangThaiKhoa() throws Exception {
		suspend(an.getId(), "Vi phạm", admin.getId()).andExpect(status().isOk());

		login(an.getEmail(), "Wrong123@")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void refreshTokenPhatTruocKhiKhoa_nhungChuaThuHoi_vanBiChan423() throws Exception {
		String refreshToken = JsonPath.read(login(an.getEmail(), PASSWORD).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(), "$.refreshToken");
		// Lớp phòng thủ thứ hai: trạng thái khoá có mà token chưa bị thu hồi (vd. sửa tay CSDL).
		User u = userRepository.findById(an.getId()).orElseThrow();
		u.setSuspendedAt(Instant.now());
		userRepository.save(u);

		mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refreshToken + "\"}"))
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
		assertThat(refreshTokenRepository.findByTokenHash(tokenCodec.hash(refreshToken)).orElseThrow().getRevokedAt())
				.isNotNull();
	}

	@Test
	void khoaLan2_idempotent_giuLyDoLanDau_khongGhiThemLichSu() throws Exception {
		suspend(an.getId(), "Lý do đầu", admin.getId()).andExpect(status().isOk());
		suspend(an.getId(), "Lý do sau", admin.getId())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suspendedReason").value("Lý do đầu"));

		assertThat(activityCount(an)).isEqualTo(1);
	}

	@Test
	void adminTuKhoaMinh_400() throws Exception {
		suspend(admin.getId(), "Thử", admin.getId())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("CANNOT_LOCK_SELF"));
		assertThat(isSuspended(admin)).isFalse();
	}

	@Test
	void khoa_thieuLyDo_400() throws Exception {
		suspend(an.getId(), "  ", admin.getId())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void khoa_userKhongTonTai_404() throws Exception {
		suspend(UNKNOWN_ID, "Vi phạm", admin.getId())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void goKhoaTamDoDangNhapSai_khongMoDuocKhoaCuaAdmin() throws Exception {
		suspend(an.getId(), "Vi phạm", admin.getId()).andExpect(status().isOk());

		// Đúng thao tác mà đăng nhập thành công thực hiện.
		transactionTemplate.executeWithoutResult(tx -> userRepository.clearLock(an.getId()));

		login(an.getEmail(), PASSWORD).andExpect(status().isLocked())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
	}

	@Test
	void doiMatKhau_sauKhiBiKhoa_khongGoKhoaCuaAdmin() throws Exception {
		suspend(an.getId(), "Vi phạm", admin.getId()).andExpect(status().isOk());

		// Đặt lại mật khẩu (PBL6-45) giờ ghi bằng UPDATE đúng cột: không đụng suspended_*.
		transactionTemplate.executeWithoutResult(
				tx -> userRepository.resetPasswordAndClearLock(an.getId(), passwordEncoder.encode("Newpass1@"),
						Instant.now()));

		assertThat(isSuspended(an)).isTrue();
		login(an.getEmail(), "Newpass1@").andExpect(status().isLocked())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
	}

	@Test
	void moKhoa_dangNhapLaiDuoc_vaGoLuonKhoaTam() throws Exception {
		suspend(an.getId(), "Vi phạm", admin.getId()).andExpect(status().isOk());
		transactionTemplate.executeWithoutResult(
				tx -> userRepository.lockUntil(an.getId(), Instant.now().plus(15, ChronoUnit.MINUTES)));

		unsuspend(an.getId(), admin.getId())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suspended").value(false))
				.andExpect(jsonPath("$.suspendedReason").doesNotExist())
				.andExpect(jsonPath("$.lockedUntil").doesNotExist());

		login(an.getEmail(), PASSWORD).andExpect(status().isOk());
	}

	// --- Admin thực hiện phải còn là Admin đang hoạt động (review PR #22, M2) ---

	/**
	 * Đúng kịch bản của review: Admin A đăng nhập, Admin B khoá A, A dùng access token cũ
	 * (còn ghi ADMIN, còn hạn ≤ 15 phút) gọi mở khoá chính mình. user-service chỉ thấy
	 * token hợp lệ nên chuyển tiếp; auth-service phải từ chối theo CSDL.
	 */
	@Test
	void adminBiKhoa_dungTokenCu_tuMoKhoaChinhMinh_403_vanBiKhoa() throws Exception {
		User admin2 = seedUser("admin2@example.com", "admin2", RoleName.ADMIN);
		suspend(admin.getId(), "Lạm quyền", admin2.getId()).andExpect(status().isOk());

		unsuspend(admin.getId(), admin.getId())
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("ADMIN_ACCESS_REVOKED"));

		assertThat(isSuspended(admin)).isTrue();
		assertThat(activityCount(admin)).as("không ghi UNSUSPENDED").isEqualTo(1);
		login(admin.getEmail(), PASSWORD).andExpect(status().isLocked());
	}

	@Test
	void adminBiKhoa_khongKhoaMoKhoaXemDuocAi() throws Exception {
		User admin2 = seedUser("admin2@example.com", "admin2", RoleName.ADMIN);
		suspend(admin.getId(), "Lạm quyền", admin2.getId()).andExpect(status().isOk());
		suspend(an.getId(), "Vi phạm", admin2.getId()).andExpect(status().isOk());

		// Trả đũa Admin đã khoá mình / mở khoá đồng bọn / đọc dữ liệu: đều bị chặn.
		suspend(admin2.getId(), "Trả đũa", admin.getId())
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("ADMIN_ACCESS_REVOKED"));
		unsuspend(an.getId(), admin.getId()).andExpect(status().isForbidden());
		internalAs(admin.getId(), get("/internal/users/" + binh.getId())).andExpect(status().isForbidden());
		internalAs(admin.getId(), post("/internal/users/search").content("{\"page\":0,\"size\":20}"))
				.andExpect(status().isForbidden());
		internalAs(admin.getId(), get("/internal/users/" + an.getId() + "/activities"))
				.andExpect(status().isForbidden());

		assertThat(isSuspended(admin2)).isFalse();
		assertThat(isSuspended(an)).isTrue();
	}

	@Test
	void adminKhacMoKhoaChoAdminBiKhoa_200() throws Exception {
		User admin2 = seedUser("admin2@example.com", "admin2", RoleName.ADMIN);
		suspend(admin.getId(), "Nhầm", admin2.getId()).andExpect(status().isOk());

		unsuspend(admin.getId(), admin2.getId()).andExpect(status().isOk())
				.andExpect(jsonPath("$.suspended").value(false));
	}

	@Test
	void actorKhongPhaiAdmin_403_moiThaoTac() throws Exception {
		suspend(binh.getId(), "Vi phạm", an.getId())
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("ADMIN_ACCESS_REVOKED"));
		unsuspend(binh.getId(), an.getId()).andExpect(status().isForbidden());
		internalAs(an.getId(), get("/internal/users/" + binh.getId())).andExpect(status().isForbidden());
		internalAs(an.getId(), post("/internal/users/search").content("{\"page\":0,\"size\":20}"))
				.andExpect(status().isForbidden());

		assertThat(isSuspended(binh)).isFalse();
	}

	/** Vai trò ADMIN bị thu hồi nhưng access token cũ vẫn ghi ADMIN tới khi hết hạn. */
	@Test
	void actorBiThuHoiVaiTroAdmin_403() throws Exception {
		Role adminRole = roleRepository.findByName(RoleName.ADMIN).orElseThrow();
		userRoleRepository.deleteById(new UserRole.UserRoleId(admin.getId(), adminRole.getId()));

		suspend(an.getId(), "Vi phạm", admin.getId())
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("ADMIN_ACCESS_REVOKED"));
		assertThat(isSuspended(an)).isFalse();
	}

	@Test
	void actorKhongTonTai_403() throws Exception {
		internalAs(UNKNOWN_ID, get("/internal/users/" + an.getId()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("ADMIN_ACCESS_REVOKED"));
	}

	@Test
	void thieuHeaderActor_400() throws Exception {
		mockMvc.perform(get("/internal/users/" + an.getId())
				.header(InternalApiProperties.HEADER, internalApiProperties.getApiKey()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	// --- lịch sử hoạt động (review PR #22, M3) --------------------------------

	/** Khoá → mở khoá → khoá lại: không mất lần khoá nào, có người mở khoá. */
	@Test
	void lichSu_khoaMoKhoaKhoaLai_giuDuMoiLan_moiNhatTruoc() throws Exception {
		User admin2 = seedUser("admin2@example.com", "admin2", RoleName.ADMIN);
		suspend(an.getId(), "Spam lần 1", admin.getId()).andExpect(status().isOk());
		unsuspend(an.getId(), admin2.getId()).andExpect(status().isOk());
		suspend(an.getId(), "Spam lần 2", admin2.getId()).andExpect(status().isOk());

		activities(an.getId())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.items[0].action").value("SUSPENDED"))
				.andExpect(jsonPath("$.items[0].reason").value("Spam lần 2"))
				.andExpect(jsonPath("$.items[0].actorId").value(admin2.getId()))
				.andExpect(jsonPath("$.items[0].actorUsername").value("admin2"))
				.andExpect(jsonPath("$.items[1].action").value("UNSUSPENDED"))
				.andExpect(jsonPath("$.items[1].actorId").value(admin2.getId()))
				.andExpect(jsonPath("$.items[1].reason").doesNotExist())
				.andExpect(jsonPath("$.items[2].action").value("SUSPENDED"))
				.andExpect(jsonPath("$.items[2].reason").value("Spam lần 1"))
				.andExpect(jsonPath("$.items[2].actorId").value(admin.getId()))
				.andExpect(jsonPath("$.items[2].actorUsername").value("admin.vmarket"))
				.andExpect(jsonPath("$.items[2].createdAt").isNotEmpty());
	}

	@Test
	void lichSu_moKhoaTaiKhoanKhongBiKhoa_khongGhi() throws Exception {
		unsuspend(an.getId(), admin.getId()).andExpect(status().isOk());
		assertThat(activityCount(an)).isZero();
	}

	@Test
	void lichSu_khoaTamDoSai5Lan_duocGhi() throws Exception {
		for (int i = 0; i < 5; i++) {
			login(an.getEmail(), "Wrong123@");
		}

		activities(an.getId())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].action").value(AccountActivityType.LOGIN_LOCKED.name()))
				.andExpect(jsonPath("$.items[0].actorId").doesNotExist());
	}

	@Test
	void lichSu_doiMatKhau_duocGhi() throws Exception {
		internal(put("/internal/users/" + an.getId() + "/password")
				.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Xyz98765#\"}"))
				.andExpect(status().isNoContent());

		activities(an.getId())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].action").value(AccountActivityType.PASSWORD_CHANGED.name()));
	}

	@Test
	void lichSu_phanTrang() throws Exception {
		suspend(an.getId(), "Lần 1", admin.getId()).andExpect(status().isOk());
		unsuspend(an.getId(), admin.getId()).andExpect(status().isOk());
		suspend(an.getId(), "Lần 2", admin.getId()).andExpect(status().isOk());

		internal(get("/internal/users/" + an.getId() + "/activities").param("page", "1").param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].reason").value("Lần 1"));
	}

	@Test
	void lichSu_thamSoPhanTrangSai_400() throws Exception {
		internal(get("/internal/users/" + an.getId() + "/activities").param("size", "1000"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void lichSu_userKhongTonTai_404() throws Exception {
		activities(UNKNOWN_ID)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	// --- xem / tìm kiếm ------------------------------------------------------

	@Test
	void xemTaiKhoan_200_coVaiTro() throws Exception {
		internal(get("/internal/users/" + binh.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(binh.getId()))
				.andExpect(jsonPath("$.email").value("binh.tran@example.com"))
				.andExpect(jsonPath("$.username").value("binh.tran"))
				.andExpect(jsonPath("$.roles[0]").value("SELLER"))
				.andExpect(jsonPath("$.emailVerified").value(true))
				.andExpect(jsonPath("$.suspended").value(false));
	}

	@Test
	void xemTaiKhoan_khongTonTai_404() throws Exception {
		internal(get("/internal/users/" + UNKNOWN_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void timKiem_khongDieuKien_traTatCa() throws Exception {
		search("{\"page\":0,\"size\":20}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.items.length()").value(3));
	}

	@Test
	void timKiem_theoEmailHoacUsername_khongPhanBietHoaThuong() throws Exception {
		search("{\"q\":\"BINH\",\"page\":0,\"size\":20}")
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].userId").value(binh.getId()));
		search("{\"q\":\"an.nguyen@\",\"page\":0,\"size\":20}")
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].userId").value(an.getId()));
	}

	@Test
	void timKiem_tuKhoaHoacUserIds_hopKetQua() throws Exception {
		// "binh" khớp username của Bình; id của An đến từ user-service (khớp họ tên/SĐT).
		search("{\"q\":\"binh\",\"userIds\":[\"" + an.getId() + "\"],\"page\":0,\"size\":20}")
				.andExpect(jsonPath("$.totalElements").value(2));
	}

	@Test
	void timKiem_locTheoTrangThai() throws Exception {
		suspend(an.getId(), "Vi phạm", admin.getId()).andExpect(status().isOk());

		search("{\"status\":\"SUSPENDED\",\"page\":0,\"size\":20}")
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].userId").value(an.getId()));
		search("{\"status\":\"ACTIVE\",\"q\":\"binh\",\"page\":0,\"size\":20}")
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].userId").value(binh.getId()));
	}

	@Test
	void timKiem_phanTrang() throws Exception {
		search("{\"page\":1,\"size\":1}")
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(1))
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(3))
				.andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	void timKiem_sizeVuotTran_400() throws Exception {
		search("{\"page\":0,\"size\":1000}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void thieuInternalApiKey_401() throws Exception {
		mockMvc.perform(get("/internal/users/" + an.getId()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}
}
