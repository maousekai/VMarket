package com.vmarket.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.vmarket.media.MediaStorage;
import com.vmarket.media.ProcessedImage;
import com.vmarket.media.StoredMedia;

/**
 * Ảnh đính kèm đánh giá (FR-REV-01): key ngẫu nhiên không chứa buyerId, chủ sở hữu lưu ở server, chỉ
 * chủ ảnh xoá được và không xoá ảnh đang dùng.
 */
class ReviewMediaTest extends ReviewTestSupport {

	private static final String URL = "https://cdn.vmarket.vn/vmarket-media-test/reviews/0f1e2d3c/a.jpg";

	@MockitoBean
	MediaStorage mediaStorage;

	@Test
	void uploadUsesARandomFolderThatDoesNotRevealTheBuyer() throws Exception {
		ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
		when(mediaStorage.upload(prefix.capture(), any(ProcessedImage.class)))
				.thenReturn(new StoredMedia("reviews/0f1e2d3c/a.jpg", URL));

		upload(BUYER);

		assertThat(prefix.getValue()).matches("reviews/[0-9a-f]{32}").doesNotContain(BUYER);
		assertThat(jdbc.queryForObject("select buyer_id from review_media where url = ?", String.class, URL))
				.isEqualTo(BUYER);
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
		when(mediaStorage.upload(anyString(), any(ProcessedImage.class)))
				.thenReturn(new StoredMedia("reviews/0f1e2d3c/a.jpg", URL));
		upload(BUYER);
		mvc.perform(delete("/api/reviews/media").param("url", URL).header("Authorization", token(BUYER, "BUYER")))
				.andExpect(status().isNoContent());
		verify(mediaStorage).deleteQuietly(URL, "reviews");

		upload(BUYER);
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

	@Test
	void onlyTheUploaderCanDeleteAnImage() throws Exception {
		when(mediaStorage.upload(anyString(), any(ProcessedImage.class)))
				.thenReturn(new StoredMedia("reviews/0f1e2d3c/a.jpg", URL));
		upload(BUYER);

		mvc.perform(delete("/api/reviews/media").param("url", URL).header("Authorization", token(OTHER_BUYER, "BUYER")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INVALID_MEDIA_URL"));
		mvc.perform(delete("/api/reviews/media").param("url", URL + "x").header("Authorization", token(BUYER, "BUYER")))
				.andExpect(status().isBadRequest());
		verify(mediaStorage, never()).deleteQuietly(anyString(), anyString());
	}

	@Test
	void deletedOrForeignImageCannotBeAttachedToAReview() throws Exception {
		when(mediaStorage.upload(anyString(), any(ProcessedImage.class)))
				.thenReturn(new StoredMedia("reviews/0f1e2d3c/a.jpg", URL));
		upload(BUYER);
		givenOrder("order-1", "DELIVERED", PRODUCT);

		// Người khác không gắn được ảnh của mình.
		givenOrder("order-2", "DELIVERED", PRODUCT);
		mvc.perform(post("/api/reviews")
				.header("Authorization", token(OTHER_BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-2", PRODUCT, 5, List.of(URL))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("IMAGE_NOT_FOUND"));

		// Trình duyệt hết thời gian chờ và dọn ảnh trước khi request lưu tới: URL đã xoá bị từ chối.
		mvc.perform(delete("/api/reviews/media").param("url", URL).header("Authorization", token(BUYER, "BUYER")))
				.andExpect(status().isNoContent());
		mvc.perform(post("/api/reviews")
				.header("Authorization", token(BUYER, "BUYER"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody("order-1", PRODUCT, 5, List.of(URL))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("IMAGE_NOT_FOUND"));
		assertThat(jdbc.queryForObject("select count(*) from reviews", Long.class)).isZero();
	}

	private void upload(String buyer) throws Exception {
		mvc.perform(multipart("/api/reviews/media").file(jpegFile()).header("Authorization", token(buyer, "BUYER")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").value(URL));
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
