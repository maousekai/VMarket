package com.vmarket.user;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.vmarket.user.client.AuthServiceClient;
import com.vmarket.user.config.UserJwtProperties;
import com.vmarket.user.exception.ApiException;

/**
 * FR-USER-03 — {@code PUT /api/users/me/password}.
 *
 * <p>Logic kiểm tra mật khẩu hiện tại / khoá / thu hồi phiên nằm ở auth-service và được
 * test bên đó ({@code PasswordChangeApiTest}). Ở đây kiểm phần của user-service: bắt buộc
 * đăng nhập, userId lấy từ token, kiểm tra đầu vào, và chuyển tiếp đúng lỗi.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChangePasswordApiTest {

	private static final String USER_A = "01JBQ9YDX7K3M8N5P2R4T6V8W0";
	private static final String USER_B = "01JBQ9YDX7K3M8N5P2R4T6V8W1";

	@Autowired MockMvc mockMvc;
	@Autowired UserJwtProperties jwtProperties;

	@MockitoBean AuthServiceClient authServiceClient;

	private ResultActions changePassword(String bearer, String body) throws Exception {
		var request = put("/api/users/me/password").contentType(MediaType.APPLICATION_JSON).content(body);
		if (bearer != null) {
			request.header(HttpHeaders.AUTHORIZATION, bearer);
		}
		return mockMvc.perform(request);
	}

	private String tokenFor(String userId) {
		return TestTokens.bearer(TestTokens.accessToken(jwtProperties.getSecret(), userId, List.of("BUYER")));
	}

	@Test
	void khongCoToken_401_khongGoiAuthService() throws Exception {
		changePassword(null, "{\"currentPassword\":\"Abcd1234@\",\"newPassword\":\"Xyz98765#\"}")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(authServiceClient);
	}

	@Test
	void thanhCong_200_userIdLayTuToken() throws Exception {
		changePassword(tokenFor(USER_A), "{\"currentPassword\":\"Abcd1234@\",\"newPassword\":\"Xyz98765#\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").isNotEmpty());

		verify(authServiceClient).changePassword(USER_A, "Abcd1234@", "Xyz98765#");
	}

	@Test
	void userIdTrongBody_biBoQua_khongDoiDuocMatKhauNguoiKhac() throws Exception {
		changePassword(tokenFor(USER_A),
				"{\"userId\":\"" + USER_B + "\",\"currentPassword\":\"Abcd1234@\",\"newPassword\":\"Xyz98765#\"}")
				.andExpect(status().isOk());

		verify(authServiceClient).changePassword(USER_A, "Abcd1234@", "Xyz98765#");
	}

	@Test
	void matKhauMoiYeu_400_khongGoiAuthService() throws Exception {
		changePassword(tokenFor(USER_A), "{\"currentPassword\":\"Abcd1234@\",\"newPassword\":\"abcdefgh\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.details[0].field").value("newPassword"));
		verifyNoInteractions(authServiceClient);
	}

	@Test
	void thieuMatKhauHienTai_400() throws Exception {
		changePassword(tokenFor(USER_A), "{\"newPassword\":\"Xyz98765#\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.details[0].field").value("currentPassword"));
		verifyNoInteractions(authServiceClient);
	}

	@Test
	void saiMatKhauHienTai_400_chuyenTiepMaLoi() throws Exception {
		doThrow(new ApiException("INVALID_CURRENT_PASSWORD", HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không đúng"))
				.when(authServiceClient).changePassword(anyString(), anyString(), anyString());

		changePassword(tokenFor(USER_A), "{\"currentPassword\":\"Wrong123@\",\"newPassword\":\"Xyz98765#\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_CURRENT_PASSWORD"))
				.andExpect(jsonPath("$.error.message").value("Mật khẩu hiện tại không đúng"));
	}

	@Test
	void biKhoaTam_423_chuyenTiepMaLoi() throws Exception {
		doThrow(new ApiException("ACCOUNT_LOCKED", HttpStatus.LOCKED, "Tài khoản tạm khoá"))
				.when(authServiceClient).changePassword(anyString(), anyString(), anyString());

		changePassword(tokenFor(USER_A), "{\"currentPassword\":\"Wrong123@\",\"newPassword\":\"Xyz98765#\"}")
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
	}

	@Test
	void bodySaiJson_400() throws Exception {
		changePassword(tokenFor(USER_A), "{khong phai json")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
	}
}
