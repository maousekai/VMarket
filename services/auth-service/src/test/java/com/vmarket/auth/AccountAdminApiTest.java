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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.vmarket.auth.config.InternalApiProperties;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.RefreshTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.security.OpaqueTokenCodec;

/**
 * FR-USER-04 — API nội bộ cho Admin quản lý tài khoản: xem, tìm kiếm, khoá, mở khoá;
 * và hiệu lực của khoá lên đăng nhập / làm mới phiên.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountAdminApiTest {

	@Autowired MockMvc mockMvc;
	@Autowired RoleRepository roleRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserRoleRepository userRoleRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired OpaqueTokenCodec tokenCodec;
	@Autowired InternalApiProperties internalApiProperties;
	@Autowired TransactionTemplate transactionTemplate;

	private static final String PASSWORD = "Abcd1234@";
	private static final String ADMIN_ID = "01JBQ9YDX7K3M8N5P2R4T6V8AA";

	private User an;
	private User binh;

	@BeforeEach
	void seed() {
		an = seedUser("an.nguyen@example.com", "an.nguyen", RoleName.BUYER);
		binh = seedUser("binh.tran@example.com", "binh.tran", RoleName.SELLER);
	}

	@AfterEach
	void cleanup() {
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

	private ResultActions internal(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
			throws Exception {
		return mockMvc.perform(req.header(InternalApiProperties.HEADER, internalApiProperties.getApiKey())
				.contentType(MediaType.APPLICATION_JSON));
	}

	private ResultActions suspend(String userId, String reason, String actorId) throws Exception {
		return internal(put("/internal/users/" + userId + "/suspension")
				.content("{\"reason\":\"" + reason + "\",\"actorId\":\"" + actorId + "\"}"));
	}

	private ResultActions search(String body) throws Exception {
		return internal(post("/internal/users/search").content(body));
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	// --- khoá / mở khoá ------------------------------------------------------

	@Test
	void khoa_200_chanDangNhap_vaThuHoiRefreshToken() throws Exception {
		String refreshToken = JsonPath.read(login(an.getEmail(), PASSWORD).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(), "$.refreshToken");

		suspend(an.getId(), "Spam đánh giá", ADMIN_ID)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suspended").value(true))
				.andExpect(jsonPath("$.suspendedReason").value("Spam đánh giá"))
				.andExpect(jsonPath("$.suspendedBy").value(ADMIN_ID))
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
		suspend(an.getId(), "Vi phạm", ADMIN_ID).andExpect(status().isOk());

		login(an.getEmail(), "Wrong123@")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void refreshTokenPhatTruocKhiKhoa_nhungChuaThuHoi_vanBiChan423() throws Exception {
		String refreshToken = JsonPath.read(login(an.getEmail(), PASSWORD).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(), "$.refreshToken");
		// Mô phỏng token phát ra song song với thao tác khoá (không đi qua suspend()).
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
	void khoaLan2_idempotent_giuLyDoLanDau() throws Exception {
		suspend(an.getId(), "Lý do đầu", ADMIN_ID).andExpect(status().isOk());
		suspend(an.getId(), "Lý do sau", ADMIN_ID)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suspendedReason").value("Lý do đầu"));
	}

	@Test
	void adminTuKhoaMinh_400() throws Exception {
		suspend(an.getId(), "Thử", an.getId())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("CANNOT_LOCK_SELF"));
		assertThat(userRepository.findById(an.getId()).orElseThrow().isSuspended()).isFalse();
	}

	@Test
	void khoa_thieuLyDo_400() throws Exception {
		suspend(an.getId(), "  ", ADMIN_ID)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void khoa_userKhongTonTai_404() throws Exception {
		suspend("01JBQ9YDX7K3M8N5P2R4T6V8ZZ", "Vi phạm", ADMIN_ID)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void goKhoaTamDoDangNhapSai_khongMoDuocKhoaCuaAdmin() throws Exception {
		suspend(an.getId(), "Vi phạm", ADMIN_ID).andExpect(status().isOk());

		// Đúng thao tác mà đăng nhập thành công / đặt lại mật khẩu (PBL6-45) thực hiện.
		transactionTemplate.executeWithoutResult(tx -> userRepository.clearLock(an.getId()));

		login(an.getEmail(), PASSWORD).andExpect(status().isLocked())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
	}

	@Test
	void moKhoa_dangNhapLaiDuoc_vaGoLuonKhoaTam() throws Exception {
		suspend(an.getId(), "Vi phạm", ADMIN_ID).andExpect(status().isOk());
		transactionTemplate.executeWithoutResult(
				tx -> userRepository.lockUntil(an.getId(), Instant.now().plus(15, ChronoUnit.MINUTES)));

		internal(delete("/internal/users/" + an.getId() + "/suspension"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suspended").value(false))
				.andExpect(jsonPath("$.suspendedReason").doesNotExist())
				.andExpect(jsonPath("$.lockedUntil").doesNotExist());

		login(an.getEmail(), PASSWORD).andExpect(status().isOk());
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
		internal(get("/internal/users/01JBQ9YDX7K3M8N5P2R4T6V8ZZ"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void timKiem_khongDieuKien_traTatCa() throws Exception {
		search("{\"page\":0,\"size\":20}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.items.length()").value(2));
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
		suspend(an.getId(), "Vi phạm", ADMIN_ID).andExpect(status().isOk());

		search("{\"status\":\"SUSPENDED\",\"page\":0,\"size\":20}")
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].userId").value(an.getId()));
		search("{\"status\":\"ACTIVE\",\"page\":0,\"size\":20}")
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].userId").value(binh.getId()));
	}

	@Test
	void timKiem_phanTrang() throws Exception {
		search("{\"page\":1,\"size\":1}")
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(1))
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.totalPages").value(2))
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
