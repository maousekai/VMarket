package com.vmarket.shop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.vmarket.media.MediaException;
import com.vmarket.media.MediaStorage;
import com.vmarket.media.ProcessedImage;
import com.vmarket.media.StoredMedia;

class ShopMediaApiTest extends ShopApiTestSupport {

	private static final String LOGO_URL = "http://localhost:9000/vmarket-media/shops/"
			+ OWNER_A + "/logo/new.png";
	private static final String COVER_URL = "http://localhost:9000/vmarket-media/shops/"
			+ OWNER_A + "/cover/new.jpg";

	@MockitoBean MediaStorage mediaStorage;

	@Test
	void uploadLogo_dungPrefixLogoVaTraUrl() throws Exception {
		when(mediaStorage.upload(eq("shops/" + OWNER_A + "/logo"), any(ProcessedImage.class)))
				.thenReturn(new StoredMedia("shops/" + OWNER_A + "/logo/new.png", LOGO_URL));

		mockMvc.perform(multipart("/api/shops/me/logo")
						.file(pngFile())
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").value(LOGO_URL));

		verify(mediaStorage).upload(eq("shops/" + OWNER_A + "/logo"), any(ProcessedImage.class));
	}

	@Test
	void uploadCover_dungPrefixCoverVaTraUrl() throws Exception {
		when(mediaStorage.upload(eq("shops/" + OWNER_A + "/cover"), any(ProcessedImage.class)))
				.thenReturn(new StoredMedia("shops/" + OWNER_A + "/cover/new.jpg", COVER_URL));

		mockMvc.perform(multipart("/api/shops/me/cover")
						.file(jpegFile())
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").value(COVER_URL));

		verify(mediaStorage).upload(eq("shops/" + OWNER_A + "/cover"), any(ProcessedImage.class));
	}

	@Test
	void uploadLogo_thieuFile_tra400() throws Exception {
		mockMvc.perform(multipart("/api/shops/me/logo")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MEDIA_FILE_REQUIRED"));
	}

	@Test
	void deleteMedia_chiXoaAnhCuaChinhMinh() throws Exception {
		mockMvc.perform(delete("/api/shops/me/media")
						.param("url", LOGO_URL)
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isNoContent());

		verify(mediaStorage).deleteByUrl(LOGO_URL, "shops/" + OWNER_A);
	}

	@Test
	void deleteMedia_urlNguoiKhac_tra400() throws Exception {
		String other = "http://localhost:9000/vmarket-media/shops/" + OWNER_B + "/logo/x.png";
		doThrow(new MediaException(MediaException.Reason.INVALID_MEDIA_URL,
				"URL ảnh không thuộc kho ảnh của bạn"))
				.when(mediaStorage).deleteByUrl(other, "shops/" + OWNER_A);

		mockMvc.perform(delete("/api/shops/me/media")
						.param("url", other)
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_MEDIA_URL"));
	}

	@Test
	void updateMine_xoaLogoVaCoverCuSauKhiCommit() throws Exception {
		String oldLogo = "http://localhost:9000/vmarket-media/shops/" + OWNER_A + "/logo/old.png";
		String oldCover = "http://localhost:9000/vmarket-media/shops/" + OWNER_A + "/cover/old.jpg";
		Map<String, Object> registration = validShop("Gốm Thanh Hà");
		registration.put("logoUrl", oldLogo);
		registration.put("coverUrl", oldCover);
		mockMvc.perform(post("/api/shops")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(registration)))
				.andExpect(status().isCreated());

		Map<String, Object> update = validShop("Gốm Thanh Hà");
		update.put("logoUrl", LOGO_URL);
		update.put("coverUrl", COVER_URL);
		mockMvc.perform(put("/api/shops/me")
						.header(HttpHeaders.AUTHORIZATION, buyer(OWNER_A))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json(update)))
				.andExpect(status().isOk());

		verify(mediaStorage).deleteQuietly(oldLogo, "shops/" + OWNER_A + "/logo");
		verify(mediaStorage).deleteQuietly(oldCover, "shops/" + OWNER_A + "/cover");
	}

	private static MockMultipartFile pngFile() throws Exception {
		BufferedImage image = new BufferedImage(80, 60, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.BLUE);
		graphics.fillRect(0, 0, 80, 60);
		graphics.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return new MockMultipartFile("file", "logo.png", "image/png", out.toByteArray());
	}

	private static MockMultipartFile jpegFile() throws Exception {
		BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.ORANGE);
		graphics.fillRect(0, 0, 320, 180);
		graphics.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return new MockMultipartFile("file", "cover.jpg", "image/jpeg", out.toByteArray());
	}
}
