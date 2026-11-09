package com.vmarket.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.media.MediaStorage;
import com.vmarket.media.ProcessedImage;
import com.vmarket.media.StoredMedia;
import com.vmarket.order.repository.OrderReturnRepository;

/** Upload / dọn ảnh minh chứng trả hàng (FR-ORD-06, PBL6-30). */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnEvidenceApiTest {

	private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";
	private static final String BUYER = "01JBQ9USER00000000000BUYER";
	private static final String URL = "http://localhost:9000/vmarket-media/returns/" + BUYER + "/a.jpg";

	@Autowired MockMvc mockMvc;
	@MockitoBean MediaStorage mediaStorage;
	@MockitoBean OrderReturnRepository returnRepository;

	private static String buyerToken() {
		return TestTokens.bearer(TestTokens.accessToken(SECRET, BUYER, List.of("BUYER")));
	}

	@Test
	void buyerUploadsEvidenceUnderOwnPrefix() throws Exception {
		when(mediaStorage.upload(eq("returns/" + BUYER), any(ProcessedImage.class)))
				.thenReturn(new StoredMedia("returns/" + BUYER + "/a.jpg", URL));

		mockMvc.perform(multipart("/api/orders/returns/evidence")
				.file(jpegFile())
				.header(HttpHeaders.AUTHORIZATION, buyerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").value(URL));
	}

	@Test
	void uploadWithoutTokenIsUnauthorized() throws Exception {
		mockMvc.perform(multipart("/api/orders/returns/evidence").file(jpegFile()))
				.andExpect(status().isUnauthorized());

		verify(mediaStorage, never()).upload(anyString(), any());
	}

	@Test
	void missingFileReturnsMediaFileRequired() throws Exception {
		mockMvc.perform(multipart("/api/orders/returns/evidence")
				.header(HttpHeaders.AUTHORIZATION, buyerToken()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MEDIA_FILE_REQUIRED"));
	}

	@Test
	void deletingUnsubmittedEvidenceUsesBuyerPrefix() throws Exception {
		when(returnRepository.existsByUserIdAndEvidenceUrlsContaining(BUYER, URL)).thenReturn(false);

		mockMvc.perform(delete("/api/orders/returns/evidence").param("url", URL)
				.header(HttpHeaders.AUTHORIZATION, buyerToken()))
				.andExpect(status().isNoContent());

		verify(mediaStorage).deleteByUrl(URL, "returns/" + BUYER);
	}

	@Test
	void submittedEvidenceCannotBeDeleted() throws Exception {
		when(returnRepository.existsByUserIdAndEvidenceUrlsContaining(BUYER, URL)).thenReturn(true);

		mockMvc.perform(delete("/api/orders/returns/evidence").param("url", URL)
				.header(HttpHeaders.AUTHORIZATION, buyerToken()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("MEDIA_IN_USE"));

		verify(mediaStorage, never()).deleteByUrl(anyString(), anyString());
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
