package com.vmarket.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ExtendWith(MockitoExtension.class)
class MediaStorageTest {

	@Mock S3Client s3;
	private MediaStorage storage;

	@BeforeEach
	void setUp() {
		MediaProperties properties = new MediaProperties();
		properties.setEndpoint("http://minio:9000");
		properties.setPublicOrigin("http://localhost:9000/");
		properties.setBucket("vmarket-media");
		properties.setAccessKey("access");
		properties.setSecretKey("secret");
		storage = new MediaStorage(s3, properties);
	}

	@Test
	void upload_datKeyTheoHashVaTraUrlCongKhai() {
		StoredMedia result = storage.upload("avatars/u1",
				new ProcessedImage(new byte[] {1, 2, 3}, "image/png", "png"));

		assertThat(result.key()).startsWith("avatars/u1/").endsWith(".png");
		assertThat(result.url()).isEqualTo(
				"http://localhost:9000/vmarket-media/" + result.key());
		verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
	}

	@Test
	void keyOf_chiChapNhanUrlThuocDungPrefix() {
		String own = "http://localhost:9000/vmarket-media/avatars/u1/abc.png";

		assertThat(storage.keyOf(own, "avatars/u1")).isEqualTo("avatars/u1/abc.png");
		assertThatThrownBy(() -> storage.keyOf(
				"http://localhost:9000/vmarket-media/avatars/u2/abc.png", "avatars/u1"))
				.isInstanceOf(MediaException.class);
		assertThatThrownBy(() -> storage.keyOf(
				"http://evil.example/vmarket-media/avatars/u1/abc.png", "avatars/u1"))
				.isInstanceOf(MediaException.class);
	}

	@Test
	void keyOf_tuChoiTruyVanVaDuongDanNguyHiem() {
		assertThatThrownBy(() -> storage.keyOf(
				"http://localhost:9000/vmarket-media/avatars/u1/../u2/x.png", "avatars/u1"))
				.isInstanceOf(MediaException.class);
		assertThatThrownBy(() -> storage.keyOf(
				"http://localhost:9000/vmarket-media/avatars/u1/x.png?x=1", "avatars/u1"))
				.isInstanceOf(MediaException.class);
	}

	@Test
	void deleteQuietly_boQuaUrlNgoaiHeThong() {
		storage.deleteQuietly("https://cdn.example.com/anh.jpg", "avatars/u1");

		// URL ngoài MinIO không được gửi tới S3 (không xoá ảnh legacy của người dùng).
		verifyNoInteractions(s3);
	}

	@Test
	void isManaged_phanBietAnhNoiBoVaAnhNgoai() {
		assertThat(storage.isManaged(
				"http://localhost:9000/vmarket-media/shops/u1/logo/a.png", "shops/u1")).isTrue();
		assertThat(storage.isManaged("https://cdn.example.com/a.png", "shops/u1")).isFalse();
	}
}
