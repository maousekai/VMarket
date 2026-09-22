package com.vmarket.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

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
import com.vmarket.user.client.AuthAccountActivity;
import com.vmarket.user.client.AuthAccountActivityPage;
import com.vmarket.user.client.AuthAccountPage;
import com.vmarket.user.client.AuthAccountSearch;
import com.vmarket.user.client.AuthServiceClient;
import com.vmarket.user.config.UserJwtProperties;
import com.vmarket.user.entity.UserProfile;
import com.vmarket.user.exception.ApiException;
import com.vmarket.user.repository.AddressRepository;
import com.vmarket.user.repository.IdempotencyRecordRepository;
import com.vmarket.user.repository.UserProfileRepository;
import com.vmarket.user.web.IdempotencyFilter;

/**
 * FR-USER-04 — Admin tìm kiếm, xem, khoá / mở khoá người dùng, xem lịch sử hoạt động.
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
	@Autowired IdempotencyRecordRepository idempotencyRecordRepository;
	@Autowired UserJwtProperties jwtProperties;

	@MockitoBean AuthServiceClient authServiceClient;

	@BeforeEach
	void clean() {
		addressRepository.deleteAll();
		userProfileRepository.deleteAll();
		idempotencyRecordRepository.deleteAll();
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
		when(authServiceClient.searchAccounts(any(), eq(ADMIN))).thenReturn(new AuthAccountPage(
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
		verify(authServiceClient).searchAccounts(captor.capture(), eq(ADMIN));
		assertThat(captor.getValue().q()).isNull();
		assertThat(captor.getValue().userIds()).isEmpty();
		assertThat(captor.getValue().status()).isNull();
	}

	@Test
	void timKiem_theoHoTen_guiUserIdKhopSangAuthService_khongPhanBietHoaThuong() throws Exception {
		seedProfile(AN, "Nguyễn Văn An", "0912345678");
		seedProfile(BINH, "Trần Thị Bình", "0987654321");
		when(authServiceClient.searchAccounts(any(), eq(ADMIN))).thenReturn(new AuthAccountPage(List.of(), 0, 20, 0, 0));

		mockMvc.perform(get("/api/users").param("q", "  TRẦN thị ").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk());

		ArgumentCaptor<AuthAccountSearch> captor = ArgumentCaptor.forClass(AuthAccountSearch.class);
		verify(authServiceClient).searchAccounts(captor.capture(), eq(ADMIN));
		assertThat(captor.getValue().q()).isEqualTo("TRẦN thị");
		assertThat(captor.getValue().userIds()).containsExactly(BINH);
	}

	@Test
	void timKiem_theoSoDienThoai() throws Exception {
		seedProfile(AN, "Nguyễn Văn An", "0912345678");
		when(authServiceClient.searchAccounts(any(), eq(ADMIN))).thenReturn(new AuthAccountPage(List.of(), 0, 20, 0, 0));

		mockMvc.perform(get("/api/users").param("q", "091234").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk());

		ArgumentCaptor<AuthAccountSearch> captor = ArgumentCaptor.forClass(AuthAccountSearch.class);
		verify(authServiceClient).searchAccounts(captor.capture(), eq(ADMIN));
		assertThat(captor.getValue().userIds()).containsExactly(AN);
	}

	@Test
	void timKiem_locTrangThai_vaPhanTrang_chuyenDungSangAuthService() throws Exception {
		when(authServiceClient.searchAccounts(any(), eq(ADMIN))).thenReturn(new AuthAccountPage(List.of(), 2, 5, 11, 3));

		mockMvc.perform(get("/api/users").param("status", "LOCKED").param("page", "2").param("size", "5")
				.header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(2))
				.andExpect(jsonPath("$.totalPages").value(3))
				.andExpect(jsonPath("$.items.length()").value(0));

		ArgumentCaptor<AuthAccountSearch> captor = ArgumentCaptor.forClass(AuthAccountSearch.class);
		verify(authServiceClient).searchAccounts(captor.capture(), eq(ADMIN));
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
		when(authServiceClient.searchAccounts(any(), eq(ADMIN))).thenThrow(new ApiException(
				"AUTH_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ tài khoản tạm thời không khả dụng"));

		mockMvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.error.code").value("AUTH_SERVICE_UNAVAILABLE"));
	}

	// --- xem chi tiết --------------------------------------------------------

	@Test
	void xemChiTiet_200_ghepHoSo() throws Exception {
		seedProfile(AN, "Nguyễn Văn An", "0912345678");
		when(authServiceClient.getAccount(AN, ADMIN)).thenReturn(account(AN, "an@example.com", false));

		mockMvc.perform(get("/api/users/" + AN).header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("an@example.com"))
				.andExpect(jsonPath("$.roles[0]").value("BUYER"))
				.andExpect(jsonPath("$.fullName").value("Nguyễn Văn An"));
	}

	@Test
	void xemChiTiet_khongTonTai_404_chuyenTiepTuAuthService() throws Exception {
		when(authServiceClient.getAccount(BINH, ADMIN))
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
		when(authServiceClient.unsuspend(AN, ADMIN)).thenReturn(account(AN, "an@example.com", false));

		mockMvc.perform(put("/api/users/" + AN + "/unlock").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.lockReason").doesNotExist());

		verify(authServiceClient).unsuspend(AN, ADMIN);
	}

	// --- Admin bị khoá / mất vai trò mà token cũ còn hạn (review PR #22, M2) ---

	/**
	 * Admin A bị khoá, dùng access token cũ (còn ghi ADMIN) gọi mở khoá chính mình.
	 * user-service chỉ thấy token hợp lệ; phần chặn nằm ở auth-service (kiểm tra theo CSDL,
	 * xem {@code AccountAdminApiTest}). Ở đây kiểm: adminId từ token được gửi sang, và 403
	 * của auth-service tới được client nguyên mã chứ không bị biến thành 502.
	 */
	@Test
	void moKhoaChinhMinh_khiDaBiKhoa_403_chuyenTiepTuAuthService() throws Exception {
		when(authServiceClient.unsuspend(ADMIN, ADMIN)).thenThrow(new ApiException("ADMIN_ACCESS_REVOKED",
				HttpStatus.FORBIDDEN, "Tài khoản của bạn không còn quyền quản trị"));

		mockMvc.perform(put("/api/users/" + ADMIN + "/unlock").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("ADMIN_ACCESS_REVOKED"));

		verify(authServiceClient).unsuspend(ADMIN, ADMIN);
	}

	// --- Idempotency-Key không được phát lại cho người không còn là Admin (m1) ---

	@Test
	void idempotencyKey_tokenMoiChiConBuyer_403_khongPhatLaiResponseCu() throws Exception {
		when(authServiceClient.suspend(eq(AN), anyString(), eq(ADMIN))).thenReturn(account(AN, "an@example.com", true));
		String key = "lock-an-6f1c2a3e";
		String body = "{\"reason\":\"Spam\"}";

		mockMvc.perform(put("/api/users/" + AN + "/lock").header(HttpHeaders.AUTHORIZATION, adminToken())
				.header(IdempotencyFilter.HEADER, key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk());

		// Cùng sub, cùng key, cùng body — nhưng token mới chỉ còn BUYER.
		mockMvc.perform(put("/api/users/" + AN + "/lock").header(HttpHeaders.AUTHORIZATION, token(ADMIN, "BUYER"))
				.header(IdempotencyFilter.HEADER, key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
				.andExpect(jsonPath("$.email").doesNotExist())
				.andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

		// Token vẫn ADMIN thì phát lại như cũ (không phá tính năng Idempotency-Key).
		mockMvc.perform(put("/api/users/" + AN + "/lock").header(HttpHeaders.AUTHORIZATION, adminToken())
				.header(IdempotencyFilter.HEADER, key)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));

		verify(authServiceClient, times(1)).suspend(eq(AN), anyString(), eq(ADMIN));
	}

	// --- tìm kiếm: không cắt bớt âm thầm (m2) ----------------------------------

	private static String userId(int i) {
		return "01JBQ9YDX7K3M8N5P2R4T" + String.format("%05d", i);
	}

	@Test
	void timKiem_tuKhoaKhopQuaNhieuHoSo_400_khongTraKetQuaThieu() throws Exception {
		userProfileRepository.saveAll(IntStream.rangeClosed(1, AuthAccountSearch.MAX_USER_IDS + 1).mapToObj(i -> {
			UserProfile p = new UserProfile();
			p.setUserId(userId(i));
			p.setFullName("Nguyễn Văn " + i);
			return p;
		}).toList());

		mockMvc.perform(get("/api/users").param("q", "nguyễn").param("status", "LOCKED")
				.header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("SEARCH_TOO_BROAD"));

		verifyNoInteractions(authServiceClient);
	}

	@Test
	void timKiem_dungBangGioiHan_guiDuMoiUserId() throws Exception {
		userProfileRepository.saveAll(IntStream.rangeClosed(1, AuthAccountSearch.MAX_USER_IDS).mapToObj(i -> {
			UserProfile p = new UserProfile();
			p.setUserId(userId(i));
			p.setFullName("Nguyễn Văn " + i);
			return p;
		}).toList());
		when(authServiceClient.searchAccounts(any(), eq(ADMIN))).thenReturn(new AuthAccountPage(List.of(), 0, 20, 0, 0));

		mockMvc.perform(get("/api/users").param("q", "nguyễn").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk());

		ArgumentCaptor<AuthAccountSearch> captor = ArgumentCaptor.forClass(AuthAccountSearch.class);
		verify(authServiceClient).searchAccounts(captor.capture(), eq(ADMIN));
		assertThat(captor.getValue().userIds()).hasSize(AuthAccountSearch.MAX_USER_IDS);
	}

	// --- lịch sử hoạt động (M3) ----------------------------------------------

	@Test
	void lichSu_200_chuyenDungTrangVaAdminId() throws Exception {
		when(authServiceClient.getActivities(AN, 1, 5, ADMIN)).thenReturn(new AuthAccountActivityPage(List.of(
				new AuthAccountActivity("01JBQ9YDX7K3M8N5P2R4T6V8D1", "UNSUSPENDED", BINH, "binh.admin", null,
						Instant.parse("2026-09-15T10:00:00Z")),
				new AuthAccountActivity("01JBQ9YDX7K3M8N5P2R4T6V8D0", "SUSPENDED", ADMIN, "admin", "Spam",
						Instant.parse("2026-09-14T10:00:00Z"))),
				1, 5, 7, 2));

		mockMvc.perform(get("/api/users/" + AN + "/activities").param("page", "1").param("size", "5")
				.header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.totalElements").value(7))
				.andExpect(jsonPath("$.items[0].action").value("UNSUSPENDED"))
				.andExpect(jsonPath("$.items[0].actorUsername").value("binh.admin"))
				.andExpect(jsonPath("$.items[1].action").value("SUSPENDED"))
				.andExpect(jsonPath("$.items[1].reason").value("Spam"))
				.andExpect(jsonPath("$.items[1].actorId").value(ADMIN));
	}

	@Test
	void lichSu_nguoiDungThuong_403_khongGoiAuthService() throws Exception {
		mockMvc.perform(get("/api/users/" + BINH + "/activities").header(HttpHeaders.AUTHORIZATION, token(AN, "BUYER")))
				.andExpect(status().isForbidden());
		verifyNoInteractions(authServiceClient);
	}

	@Test
	void lichSu_thamSoSai_400_khongGoiAuthService() throws Exception {
		mockMvc.perform(get("/api/users/abc/activities").header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/users/" + AN + "/activities").param("size", "1000")
				.header(HttpHeaders.AUTHORIZATION, adminToken()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(authServiceClient);
	}
}
