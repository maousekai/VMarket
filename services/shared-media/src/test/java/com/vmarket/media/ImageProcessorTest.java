package com.vmarket.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class ImageProcessorTest {

	private final ImageProcessor processor = new ImageProcessor();

	@Test
	void avatar_duocCatVuong512VaMaHoaLaiPng() throws IOException {
		ProcessedImage result = processor.process(png(1000, 800, true), ImageKind.AVATAR);

		assertThat(result.contentType()).isEqualTo("image/png");
		assertThat(result.extension()).isEqualTo("png");
		BufferedImage output = decode(result.bytes());
		assertThat(output.getWidth()).isEqualTo(512);
		assertThat(output.getHeight()).isEqualTo(512);
		// Nen trong suot cua PNG goc duoc giu lai.
		assertThat((output.getRGB(0, 0) >>> 24)).isZero();
	}

	@Test
	void cover_duocThuNhoVeToiDa1600x900Jpeg() throws IOException {
		ProcessedImage result = processor.process(jpeg(2000, 1000), ImageKind.COVER);

		assertThat(result.contentType()).isEqualTo("image/jpeg");
		assertThat(result.extension()).isEqualTo("jpg");
		BufferedImage output = decode(result.bytes());
		assertThat(output.getWidth()).isEqualTo(1600);
		assertThat(output.getHeight()).isEqualTo(800);
	}

	@Test
	void cover_anhNhoHonKhongBiPhongTo() throws IOException {
		ProcessedImage result = processor.process(jpeg(800, 400), ImageKind.COVER);

		BufferedImage output = decode(result.bytes());
		assertThat(output.getWidth()).isEqualTo(800);
		assertThat(output.getHeight()).isEqualTo(400);
	}

	@Test
	void tuChoiFileRong() {
		assertThatThrownBy(() -> processor.process(new byte[0], ImageKind.AVATAR))
				.isInstanceOfSatisfying(MediaException.class,
						ex -> assertThat(ex.getReason()).isEqualTo(MediaException.Reason.FILE_REQUIRED));
	}

	@Test
	void tuChoiAnhNenNhoVuotMegapixelTruocKhiGiaiMa() throws IOException {
		byte[] compressed;
		try (InputStream in = getClass().getResourceAsStream(
				"/images/compressed-12000x12000.png")) {
			assertThat(in).isNotNull();
			compressed = in.readAllBytes();
		}

		// Chi ~140 KB nhưng giải mã sẽ cần 576 MB nếu đọc trước khi kiểm tra kích thước.
		assertThat(compressed.length).isLessThan(200_000);
		assertThatThrownBy(() -> processor.process(compressed, ImageKind.AVATAR))
				.isInstanceOfSatisfying(MediaException.class,
						ex -> assertThat(ex.getReason()).isEqualTo(MediaException.Reason.IMAGE_TOO_LARGE));
	}

	@Test
	void tuChoiFileVuotGioiHan() {
		byte[] oversized = new byte[(int) ImageKind.AVATAR.maxBytes() + 1];
		assertThatThrownBy(() -> processor.process(oversized, ImageKind.AVATAR))
				.isInstanceOfSatisfying(MediaException.class,
						ex -> assertThat(ex.getReason()).isEqualTo(MediaException.Reason.FILE_TOO_LARGE));
	}

	@Test
	void tuChoiGif() {
		byte[] gif = "GIF89a................".getBytes(StandardCharsets.US_ASCII);
		assertThatThrownBy(() -> processor.process(gif, ImageKind.AVATAR))
				.isInstanceOfSatisfying(MediaException.class,
						ex -> assertThat(ex.getReason()).isEqualTo(MediaException.Reason.UNSUPPORTED_FORMAT));
	}

	@Test
	void tuChoiSvg() {
		byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"
				.getBytes(StandardCharsets.UTF_8);
		assertThatThrownBy(() -> processor.process(svg, ImageKind.AVATAR))
				.isInstanceOfSatisfying(MediaException.class,
						ex -> assertThat(ex.getReason()).isEqualTo(MediaException.Reason.UNSUPPORTED_FORMAT));
	}

	@Test
	void tuChoiWebpDong() {
		assertThatThrownBy(() -> processor.process(animatedWebp(), ImageKind.AVATAR))
				.isInstanceOfSatisfying(MediaException.class,
						ex -> assertThat(ex.getReason()).isEqualTo(MediaException.Reason.ANIMATED_IMAGE));
	}

	@Test
	void detect_dungDinhDangTheoMagicByte() {
		assertThat(ImageProcessor.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}))
				.isEqualTo(ImageProcessor.Format.JPEG);
		assertThat(ImageProcessor.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
				.isEqualTo(ImageProcessor.Format.PNG);
	}

	private static BufferedImage decode(byte[] bytes) throws IOException {
		return ImageIO.read(new ByteArrayInputStream(bytes));
	}

	private static byte[] png(int width, int height, boolean transparent) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		if (!transparent) {
			Graphics2D graphics = image.createGraphics();
			graphics.setColor(Color.BLUE);
			graphics.fillRect(0, 0, width, height);
			graphics.dispose();
		}
		return write(image, "png");
	}

	private static byte[] jpeg(int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.ORANGE);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		return write(image, "jpg");
	}

	private static byte[] write(BufferedImage image, String format) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}

	/** RIFF....WEBP + VP8X với cờ animation (bit 0x02) — đủ để bộ kiểm tra nhận ra. */
	private static byte[] animatedWebp() {
		byte[] bytes = new byte[21];
		bytes[0] = 'R';
		bytes[1] = 'I';
		bytes[2] = 'F';
		bytes[3] = 'F';
		bytes[4] = 13;
		bytes[8] = 'W';
		bytes[9] = 'E';
		bytes[10] = 'B';
		bytes[11] = 'P';
		bytes[12] = 'V';
		bytes[13] = 'P';
		bytes[14] = '8';
		bytes[15] = 'X';
		bytes[16] = 1;
		bytes[20] = 0x02;
		return bytes;
	}
}
