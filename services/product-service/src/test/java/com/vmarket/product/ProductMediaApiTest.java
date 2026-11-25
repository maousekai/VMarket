package com.vmarket.product;

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
import com.vmarket.product.repository.ProductRepository;

/** Upload / dọn ảnh sản phẩm của seller (PBL6-28). */
@SpringBootTest
@AutoConfigureMockMvc
class ProductMediaApiTest {

	private static final String SELLER = "seller-1";
	private static final String URL = "http://localhost:9000/vmarket-media/products/" + SELLER + "/a.jpg";

	@Autowired MockMvc mockMvc;
	@MockitoBean MediaStorage mediaStorage;
	@MockitoBean ProductRepository productRepository;

	@Test
	void sellerUploadsImageUnderOwnPrefix() throws Exception {
		when(mediaStorage.upload(eq("products/" + SELLER), any(ProcessedImage.class)))
				.thenReturn(new StoredMedia("products/" + SELLER + "/a.jpg", URL));

		mockMvc.perform(multipart("/api/products/seller/media")
				.file(jpegFile())
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(SELLER, "SELLER")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.url").value(URL));
	}

	@Test
	void buyerCannotUploadProductImages() throws Exception {
		mockMvc.perform(multipart("/api/products/seller/media")
				.file(jpegFile())
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer("buyer-1", "BUYER")))
				.andExpect(status().isForbidden());

		verify(mediaStorage, never()).upload(anyString(), any());
	}

	@Test
	void missingFileReturnsMediaFileRequired() throws Exception {
		mockMvc.perform(multipart("/api/products/seller/media")
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(SELLER, "SELLER")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MEDIA_FILE_REQUIRED"));
	}

	@Test
	void nonImageFileIsRejected() throws Exception {
		MockMultipartFile text = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());

		mockMvc.perform(multipart("/api/products/seller/media")
				.file(text)
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(SELLER, "SELLER")))
				.andExpect(status().isBadRequest());

		verify(mediaStorage, never()).upload(anyString(), any());
	}

	@Test
	void deletingUnusedImageUsesSellerPrefix() throws Exception {
		when(productRepository.existsBySellerIdAndImageUrlsAndDeletedAtIsNull(SELLER, URL)).thenReturn(false);

		mockMvc.perform(delete("/api/products/seller/media").param("url", URL)
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(SELLER, "SELLER")))
				.andExpect(status().isNoContent());

		verify(mediaStorage).deleteByUrl(URL, "products/" + SELLER);
	}

	@Test
	void deletingImageInUseReturnsConflict() throws Exception {
		when(productRepository.existsBySellerIdAndImageUrlsAndDeletedAtIsNull(SELLER, URL)).thenReturn(true);

		mockMvc.perform(delete("/api/products/seller/media").param("url", URL)
				.header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(SELLER, "SELLER")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("MEDIA_IN_USE"));

		verify(mediaStorage, never()).deleteByUrl(anyString(), anyString());
	}

	private static MockMultipartFile jpegFile() throws Exception {
		BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.ORANGE);
		graphics.fillRect(0, 0, 40, 30);
		graphics.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return new MockMultipartFile("file", "a.jpg", "image/jpeg", out.toByteArray());
	}
}
