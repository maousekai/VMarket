package com.vmarket.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.user.client.AuthAccount;
import com.vmarket.user.client.AuthAccountPage;
import com.vmarket.user.client.AuthAccountSearch;
import com.vmarket.user.client.AuthServiceClient;
import com.vmarket.user.config.UserJwtProperties;
import com.vmarket.user.entity.UserProfile;
import com.vmarket.user.exception.ApiException;
import com.vmarket.user.repository.AddressRepository;
import com.vmarket.user.repository.UserProfileRepository;

/**
 * FR-USER-04 — Admin tìm kiếm, xem, khoá / mở khoá người dùng.
 *
 * <p>{@link AuthServiceClient} được mock: phía auth-service có bộ test riêng
 * ({@code AccountAdminApiTest}), cách client gọi HTTP và ánh xạ lỗi kiểm ở
 * {@code AuthServiceClientTest}. Ở đây kiểm phần của user-service: phân quyền, kiểm tra
 * đầu vào, lấy adminId từ token, ghép hồ sơ và tìm theo họ tên/SĐT.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserApiTest {

	private static final String ADMIN = "01JBQ9YDX7K3M8N5P2R4T6V8AA";
	private static final String AN = "01JBQ9YDX7K3M8N5P2R4T6V8BB";
	private static final String BINH = "01JBQ9YDX7K3M8N5P2R4T6V8CC";

	@Autowired MockMvc mockMvc;
	@Autowired UserProfileRepository userProfileRepository;
	@Autowired AddressRepository addressRepository;
	@Autowired UserJwtProperties jwtProperties;

	@MockitoBean AuthServiceClient authServiceClient;

	@BeforeEach
	void clean() {
		addressRepository.deleteAll();
		userProfileRepository.deleteAll();
	}

	private String token(String userId, String... roles) {
		return TestTokens.bearer(TestTokens.accessToken(jwtProperties.getSecret(), userId, List.of(roles)));
	}

	private String adminToken() {
		return token(ADMIN, "ADMIN");
	}

	private static AuthAccount account(String userId, String email, boolean suspended) {
		return new AuthAccount(userId, email, email.substring(0, email.indexOf('@')), List.of("BUYER"), true,
				suspended, suspended ? Instant.parse("2026-09-14T10:00:00Z") : null,
				suspended ? "Spam" : null, suspended ? ADMIN : null, null, Instant.parse("2026-09-01T00:00:00Z"));
	}

	private void seedProfile(String userId, String fullName, String phone) {
		UserProfile p = new UserProfile();
		p.setUserId(userId);
		p.setFullName(fullName);
		p.setPhone(phone);
		userProfileRepository.save(p);
	}

	// --- phân quyền ----------------------------------------------------------

	@Test
	void khongCoToken_tra401() throws Exception {
		mockMvc.perform(get("/api/users"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	void nguoiDungThuong_tra403_moiEndpointAdmin_khongGoiAuthService() throws Exception {
		String buyer = token(AN, "BUYER");
		mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, buyer))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		mockMvc.perform(get("/api/users/" + BINH).header(HttpHeaders.AUTHORIZATION, buyer))
				.andExpect(status().isForbidden());
		mockMvc.perform(put("/api/users/" + BINH + "/lock").header(HttpHeaders.AUTHORIZATION, buyer)
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(put("/api/users/" + BINH + "/unlock").header(HttpHeaders.AUTHORIZATION, buyer))
				.andExpect(status().isForbidden());

		verifyNoInteractions(authServiceClient);
	}

	@Test
	void seller_cungKhongPhaiAdmin_tra403() throws Exception {
		mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, token(AN, "BUYER", "SELLER")))
				.andExpect(status().isForbidden());
	}

	@Test
	void nguoiDungThuong_thamSoSai_vanTra403_khongLoQuyTacKiemTra() throws Exception {
		mockMvc.perform(get("/api/users").param("page", "-1").header(HttpHeaders.AUTHORIZATION, token(AN, "BUYER")))
				.andExpect(status().isForbidden());
	}

	// --- tìm kiếm ------------------------------------------------------------

	@Test
	void timKiem_ghepHoSo_nguoiChuaCoHoSoVanHien() throws Exception {
		seedProfile(AN, "Nguyễn Văn An", "0912345678");
		when(authServiceClient.searchAccounts(any())).thenReturn(new AuthAccountPage(
				List.of(account(AN, "an@example.com", false), account(BINH, "binh@example.com", true)), 0, 20, 2, 1));

		mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.items[0].userId").value(AN))
				.andExpect(jsonPath("$.items[0].email").value("an@example.com"))
				.andExpect(jsonPath("$.items[0].fullName").value("Nguyễn Văn An"))
				.andExpect(jsonPath("$.items[0].phone").value("0912345678"))
				.andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$.items[1].userId").value(BINH))
				.andExpect(jsonPath("$.items[1].fullName").doesNotExist())
				.andExpect(jsonPath("$.items[1].status").value("LOCKED"))
				.andExpect(jsonPath("$.items[1].lockReason").value("Spam"));

		ArgumentCaptor<AuthAccountSearch> captor = ArgumentCaptor.forClass(AuthAccountSearch.class);
		verify(authServiceClient).searchAccounts(captor.capture());
		assertThat(captor.getValue().q()).isNull();
		assertThat(captor.getValue().userIds()).isEmpty();
		assertThat(captor.getValue().status()).isNull();
	}

	@Test
	void timKiem_theoHoTen_guiUserIdKhopSangAuthService_khongPhanBietHoaThuong() throws Exception {
		seedProfile(AN, "Nguyễn Văn An", "0912345678");
		seedProfile(BINH, "Trần Thị Bình", "0987654321");
		when(authServiceClient.searchAccounts(any())).thenReturn(new AuthAccountPage(List.of(), 0, 20, 0, 0));

		mockMvc.perform(get("/api/users").param("q", "  TRẦN thị ").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk());

		ArgumentCaptor<AuthAccountSearch> captor = ArgumentCaptor.forClass(AuthAccountSearch.class);
		verify(authServiceClient).searchAccounts(captor.capture());
		assertThat(captor.getValue().q()).isEqualTo("TRẦN thị");
		assertThat(captor.getValue().userIds()).containsExactly(BINH);
	}

	@Test
	void timKiem_theoSoDienThoai() throws Exception {
		seedProfile(AN, "Nguyễn Văn An", "0912345678");
		when(authServiceClient.searchAccounts(any())).thenReturn(new AuthAccountPage(List.of(), 0, 20, 0, 0));

		mockMvc.perform(get("/api/users").param("q", "091234").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk());

		ArgumentCaptor<AuthAccountSearch> captor = ArgumentCaptor.forClass(AuthAccountSearch.class);
		verify(authServiceClient).searchAccounts(captor.capture());
		assertThat(captor.getValue().userIds()).containsExactly(AN);
	}

	@Test
	void timKiem_locTrangThai_vaPhanTrang_chuyenDungSangAuthService() throws Exception {
		when(authServiceClient.searchAccounts(any())).thenReturn(new AuthAccountPage(List.of(), 2, 5, 11, 3));

		mockMvc.perform(get("/api/users").param("status", "LOCKED").param("page", "2").param("size", "5")
				.header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(2))
				.andExpect(jsonPath("$.totalPages").value(3))
				.andExpect(jsonPath("$.items.length()").value(0));

		ArgumentCaptor<AuthAccountSearch> captor = ArgumentCaptor.forClass(AuthAccountSearch.class);
		verify(authServiceClient).searchAccounts(captor.capture());
		assertThat(captor.getValue().status()).isEqualTo("SUSPENDED");
		assertThat(captor.getValue().page()).isEqualTo(2);
		assertThat(captor.getValue().size()).isEqualTo(5);
	}

	@Test
	void timKiem_trangThaiKhongHopLe_tra400() throws Exception {
		mockMvc.perform(get("/api/users").param("status", "BANNED").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void timKiem_thamSoPhanTrangSai_tra400_khongGoiAuthService() throws Exception {
		mockMvc.perform(get("/api/users").param("page", "-1").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		mockMvc.perform(get("/api/users").param("size", "1000").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(authServiceClient);
	}

	@Test
	void authServiceKhongPhanHoi_tra503_voiBodyLoiChuan() throws Exception {
		when(authServiceClient.searchAccounts(any())).thenThrow(new ApiException(
				"AUTH_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ tài khoản tạm thời không khả dụng"));

		mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("AUTH_SERVICE_UNAVAILABLE"));
	}

	// --- xem chi tiết --------------------------------------------------------

	@Test
	void xemChiTiet_200_ghepHoSo() throws Exception {
		seedProfile(AN, "Nguyễn Văn An", "0912345678");
		when(authServiceClient.getAccount(AN)).thenReturn(account(AN, "an@example.com", false));

		mockMvc.perform(get("/api/users/" + AN).header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("an@example.com"))
				.andExpect(jsonPath("$.roles[0]").value("BUYER"))
				.andExpect(jsonPath("$.fullName").value("Nguyễn Văn An"));
	}

	@Test
	void xemChiTiet_khongTonTai_404_chuyenTiepTuAuthService() throws Exception {
		when(authServiceClient.getAccount(BINH))
				.thenThrow(new ApiException("USER_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy tài khoản"));

		mockMvc.perform(get("/api/users/" + BINH).header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void userIdSaiDinhDang_tra400_khongGoiAuthService() throws Exception {
		mockMvc.perform(get("/api/users/abc").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(authServiceClient);
	}

	@Test
	void duongDanMe_vanLaHoSoCuaChinhMinh_khongBiHieuLaUserId() throws Exception {
		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(ADMIN));
		verifyNoInteractions(authServiceClient);
	}

	// --- khoá / mở khoá ------------------------------------------------------

	@Test
	void khoa_200_adminIdLayTuToken() throws Exception {
		when(authServiceClient.suspend(eq(AN), anyString(), anyString())).thenReturn(account(AN, "an@example.com", true));

		mockMvc.perform(put("/api/users/" + AN + "/lock").header(HttpHeaders.AUTHORIZATION, adminToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"  Spam  \"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("LOCKED"))
				.andExpect(jsonPath("$.lockedBy").value(ADMIN));

		verify(authServiceClient).suspend(AN, "Spam", ADMIN);
	}

	@Test
	void khoa_thieuLyDo_tra400_khongGoiAuthService() throws Exception {
		mockMvc.perform(put("/api/users/" + AN + "/lock").header(HttpHeaders.AUTHORIZATION, adminToken())
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.details[0].field").value("reason"));

		verify(authServiceClient, never()).suspend(anyString(), anyString(), anyString());
	}

	@Test
	void khoa_tuKhoaMinh_400_chuyenTiepTuAuthService() throws Exception {
		when(authServiceClient.suspend(eq(ADMIN), anyString(), eq(ADMIN))).thenThrow(new ApiException(
				"CANNOT_LOCK_SELF", HttpStatus.BAD_REQUEST, "Không thể tự khoá tài khoản của chính mình"));

		mockMvc.perform(put("/api/users/" + ADMIN + "/lock").header(HttpHeaders.AUTHORIZATION, adminToken())
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Thử\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("CANNOT_LOCK_SELF"));
	}

	@Test
	void moKhoa_200() throws Exception {
		when(authServiceClient.unsuspend(AN)).thenReturn(account(AN, "an@example.com", false));

		mockMvc.perform(put("/api/users/" + AN + "/unlock").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.lockReason").doesNotExist());

		verify(authServiceClient).unsuspend(AN);
	}
}
