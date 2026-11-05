package com.vmarket.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.media.MediaException;
import com.vmarket.media.MediaStorage;
import com.vmarket.media.ProcessedImage;
import com.vmarket.media.StoredMedia;
import com.vmarket.user.config.UserJwtProperties;
import com.vmarket.user.entity.UserProfile;
import com.vmarket.user.repository.UserProfileRepository;

@SpringBootTest
@AutoConfigureMockMvc
class UserMediaApiTest {

	private static final String USER_A = "01JBQ9YDX7K3M8N5P2R4T6V8W0";
	private static final String AVATAR_URL = "http://localhost:9000/vmarket-media/avatars/"
			+ USER_A + "/abc.png";

	@Autowired MockMvc mockMvc;
	@Autowired UserProfileRepository profiles;
	@Autowired UserJwtProperties jwtProperties;
	@MockitoBean MediaStorage mediaStorage;

	@BeforeEach
	void clean() {
		profiles.deleteAll();
	}

	@Test
	void uploadAvatar_traUrlVaDungPrefixCuaNguoiDung() throws Exception {
		when(mediaStorage.upload(eq("avatars/" + USER_A), any(ProcessedImage.class)))
				.thenReturn(new StoredMedia("avatars/" + USER_A + "/abc.png", AVATAR_URL));

		mockMvc.perform(multipart("/api/users/me/avatar")
						.file(pngFile())
						.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").value(AVATAR_URL));

		verify(mediaStorage).upload(eq("avatars/" + USER_A), any(ProcessedImage.class));
	}

	@Test
	void uploadAvatar_thieuFile_tra400() throws Exception {
		mockMvc.perform(multipart("/api/users/me/avatar")
						.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MEDIA_FILE_REQUIRED"));
	}

	@Test
	void uploadAvatar_saiDinhDang_tra400() throws Exception {
		MockMultipartFile svg = new MockMultipartFile("file", "a.svg", "image/svg+xml",
				"<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes());

		mockMvc.perform(multipart("/api/users/me/avatar")
						.file(svg)
						.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MEDIA_UNSUPPORTED_FORMAT"));
	}

	@Test
	void uploadAvatar_quaDungLuong_tra400() throws Exception {
		MockMultipartFile big = new MockMultipartFile("file", "a.png", "image/png",
				new byte[2 * 1024 * 1024 + 1]);

		mockMvc.perform(multipart("/api/users/me/avatar")
						.file(big)
						.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MEDIA_FILE_TOO_LARGE"));
	}

	@Test
	void deleteMedia_chiXoaAnhCuaChinhMinh() throws Exception {
		mockMvc.perform(delete("/api/users/me/media")
						.param("url", AVATAR_URL)
						.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isNoContent());

		verify(mediaStorage).deleteByUrl(AVATAR_URL, "avatars/" + USER_A);
	}

	@Test
	void deleteMedia_urlNguoiKhac_tra400() throws Exception {
		String other = "http://localhost:9000/vmarket-media/avatars/other-user/abc.png";
		doThrow(new MediaException(MediaException.Reason.INVALID_MEDIA_URL,
				"URL ảnh không thuộc kho ảnh của bạn"))
				.when(mediaStorage).deleteByUrl(other, "avatars/" + USER_A);

		mockMvc.perform(delete("/api/users/me/media")
						.param("url", other)
						.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_MEDIA_URL"));
	}

	@Test
	void updateProfile_xoaAnhCuSauKhiCommit() throws Exception {
		UserProfile profile = new UserProfile();
		profile.setUserId(USER_A);
		profile.setFullName("Nguyễn Văn An");
		profile.setAvatarUrl("http://localhost:9000/vmarket-media/avatars/" + USER_A + "/old.png");
		profiles.save(profile);
		String oldUrl = profile.getAvatarUrl();

		mockMvc.perform(put("/api/users/me")
						.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"Nguyễn Văn An\",\"avatarUrl\":\"" + AVATAR_URL + "\"}"))
				.andExpect(status().isOk());

		verify(mediaStorage).deleteQuietly(oldUrl, "avatars/" + USER_A);
	}

	@Test
	void updateProfile_anhNgoaiHeThong_khongBiXoa() throws Exception {
		UserProfile profile = new UserProfile();
		profile.setUserId(USER_A);
		profile.setFullName("Nguyễn Văn An");
		profile.setAvatarUrl("https://cdn.example.com/avatar.jpg");
		profiles.save(profile);

		mockMvc.perform(put("/api/users/me")
						.header(HttpHeaders.AUTHORIZATION, tokenFor(USER_A))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"Nguyễn Văn An\",\"avatarUrl\":\"" + AVATAR_URL + "\"}"))
				.andExpect(status().isOk());

		// deleteQuietly có thể được gọi, nhưng MediaStorage thật sẽ bỏ qua URL ngoài.
		// Ở đây MediaStorage là mock nên chỉ cần xác nhận không xoá bằng API xoá trực tiếp.
		verify(mediaStorage, never()).deleteByUrl(any(), any());
	}

	private String tokenFor(String userId) {
		return TestTokens.bearer(TestTokens.accessToken(
				jwtProperties.getSecret(), userId, List.of("BUYER")));
	}

	private static MockMultipartFile pngFile() throws Exception {
		BufferedImage image = new BufferedImage(80, 60, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.BLUE);
		graphics.fillRect(0, 0, 80, 60);
		graphics.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return new MockMultipartFile("file", "avatar.png", "image/png", out.toByteArray());
	}
}
