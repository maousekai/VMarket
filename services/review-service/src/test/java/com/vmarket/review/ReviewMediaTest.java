package com.vmarket.review;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.vmarket.media.MediaStorage;
import com.vmarket.media.ProcessedImage;
import com.vmarket.media.StoredMedia;

/** Ảnh đính kèm đánh giá (FR-REV-01): upload dưới thư mục của chính người mua, không xoá ảnh đang dùng. */
class ReviewMediaTest extends ReviewTestSupport {

	private static final String URL = "https://cdn.vmarket.vn/vmarket-media-test/reviews/" + BUYER + "/a.jpg";

	@MockitoBean
	MediaStorage mediaStorage;

	@Test
	void buyerUploadsReviewImageUnderOwnPrefix() throws Exception {
		when(mediaStorage.upload(eq("reviews/" + BUYER), any(ProcessedImage.class)))
				.thenReturn(new StoredMedia("reviews/" + BUYER + "/a.jpg", URL));

		mvc.perform(multipart("/api/reviews/media").file(jpegFile()).header("Authorization", token(BUYER, "BUYER")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").value(URL));
	}

	@Test
	void uploadRequiresBuyerRoleAndAValidImage() throws Exception {
		mvc.perform(multipart("/api/reviews/media").file(jpegFile()).header("Authorization", token(SELLER, "SELLER")))
				.andExpect(status().isForbidden());

		mvc.perform(multipart("/api/reviews/media")
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", "not an image".getBytes()))
				.header("Authorization", token(BUYER, "BUYER")))
				.andExpect(status().isBadRequest());

		mvc.perform(multipart("/api/reviews/media").header("Authorization", token(BUYER, "BUYER")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MEDIA_FILE_REQUIRED"));

		verify(mediaStorage, never()).upload(anyString(), any());
	}

	@Test
	void unusedImageCanBeDeletedButImageInAReviewCannot() throws Exception {
		mvc.perform(delete("/api/reviews/media").param("url", URL).header("Authorization", token(BUYER, "BUYER")))
				.andExpect(status().isNoContent());
		verify(mediaStorage).deleteByUrl(URL, "reviews/" + BUYER);

		givenOrder("order-1", "DELIVERED", PRODUCT);
		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, List.of(URL))))
				.andExpect(status().isCreated());

		mvc.perform(delete("/api/reviews/media").param("url", URL).header("Authorization", token(BUYER, "BUYER")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("MEDIA_IN_USE"));
	}

	private static MockMultipartFile jpegFile() throws Exception {
		BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.GRAY);
		graphics.fillRect(0, 0, 40, 30);
		graphics.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return new MockMultipartFile("file", "a.jpg", "image/jpeg", out.toByteArray());
	}
}
