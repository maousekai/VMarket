package com.vmarket.media;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Kiểm tra và chuẩn hoá ảnh upload.
 *
 * <p>Không bao giờ lưu nguyên byte người dùng gửi lên: mọi ảnh được giải mã rồi mã hoá
 * lại, nhờ vậy EXIF/GPS bị loại bỏ và kích thước tối đa do server quyết định. Header
 * được kiểm tra trước khi gọi {@link ImageIO} để chặn sớm SVG/GIF và các định dạng
 * không nằm trong danh sách cho phép.
 */
public class ImageProcessor {

	static {
		// TwelveMonkeys đăng ký WebP reader qua SPI; quét một lần để ImageIO nhìn thấy
		// plugin kể cả khi ImageIO đã được khởi tạo bởi code khác trước đó.
		ImageIO.scanForPlugins();
		ImageIO.setUseCache(false);
	}

	private static final int MAGIC_READ_LIMIT = 512;

	public ProcessedImage process(byte[] input, ImageKind kind) {
		if (input == null || input.length == 0) {
			throw new MediaException(MediaException.Reason.FILE_REQUIRED,
					"Vui lòng chọn " + kind.label());
		}
		if (input.length > kind.maxBytes()) {
			throw new MediaException(MediaException.Reason.FILE_TOO_LARGE,
					kind.label().substring(0, 1).toUpperCase(Locale.ROOT) + kind.label().substring(1)
							+ " tối đa " + (kind.maxBytes() / (1024 * 1024)) + " MB");
		}

		Format format = detect(input);
		if (format == Format.WEBP && animatedWebp(input)) {
			throw new MediaException(MediaException.Reason.ANIMATED_IMAGE,
					"Không hỗ trợ ảnh động. Vui lòng chọn ảnh tĩnh JPEG, PNG hoặc WebP");
		}

		BufferedImage image = read(input);
		long pixels = (long) image.getWidth() * image.getHeight();
		if (pixels > ImageKind.maxPixels()) {
			throw new MediaException(MediaException.Reason.IMAGE_TOO_LARGE,
					"Ảnh quá lớn về kích thước. Vui lòng chọn ảnh dưới "
							+ (ImageKind.maxPixels() / 1_000_000) + " megapixel");
		}

		return kind.square() ? square(image, kind) : cover(image, kind);
	}

	private BufferedImage read(byte[] input) {
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(input));
			if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
				throw new MediaException(MediaException.Reason.INVALID_IMAGE,
						"Không đọc được ảnh. Vui lòng chọn file JPEG, PNG hoặc WebP hợp lệ");
			}
			return image;
		} catch (MediaException ex) {
			throw ex;
		} catch (IOException | RuntimeException ex) {
			throw new MediaException(MediaException.Reason.INVALID_IMAGE,
					"Không đọc được ảnh. Vui lòng chọn file JPEG, PNG hoặc WebP hợp lệ", ex);
		}
	}

	private ProcessedImage square(BufferedImage source, ImageKind kind) {
		int side = Math.min(source.getWidth(), source.getHeight());
		int x = (source.getWidth() - side) / 2;
		int y = (source.getHeight() - side) / 2;

		BufferedImage out = new BufferedImage(kind.maxWidth(), kind.maxHeight(),
				BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = out.createGraphics();
		try {
			graphics.setComposite(AlphaComposite.Src);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
					RenderingHints.VALUE_RENDER_QUALITY);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
					RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.drawImage(source, 0, 0, kind.maxWidth(), kind.maxHeight(),
					x, y, x + side, y + side, null);
		} finally {
			graphics.dispose();
		}

		return new ProcessedImage(writePng(out), "image/png", kind.extension());
	}

	private ProcessedImage cover(BufferedImage source, ImageKind kind) {
		double scale = Math.min(1.0, Math.min(
				(double) kind.maxWidth() / source.getWidth(),
				(double) kind.maxHeight() / source.getHeight()));
		int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = out.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, width, height);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
					RenderingHints.VALUE_RENDER_QUALITY);
			graphics.drawImage(source, 0, 0, width, height, null);
		} finally {
			graphics.dispose();
		}

		return new ProcessedImage(writeJpeg(out), "image/jpeg", kind.extension());
	}

	private static byte[] writePng(BufferedImage image) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!ImageIO.write(image, "png", out)) {
				throw new MediaException(MediaException.Reason.INVALID_IMAGE,
						"Không mã hoá được ảnh PNG");
			}
			return out.toByteArray();
		} catch (IOException ex) {
			throw new MediaException(MediaException.Reason.INVALID_IMAGE,
					"Không mã hoá được ảnh PNG", ex);
		}
	}

	private static byte[] writeJpeg(BufferedImage image) {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) {
			throw new MediaException(MediaException.Reason.INVALID_IMAGE,
					"Máy chủ không mã hoá được ảnh JPEG");
		}
		ImageWriter writer = writers.next();
		try {
			ImageWriteParam params = writer.getDefaultWriteParam();
			params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			params.setCompressionQuality(0.86f);

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
				writer.setOutput(stream);
				writer.write(null, new IIOImage(image, null, null), params);
			}
			return out.toByteArray();
		} catch (IOException ex) {
			throw new MediaException(MediaException.Reason.INVALID_IMAGE,
					"Không mã hoá được ảnh JPEG", ex);
		} finally {
			writer.dispose();
		}
	}

	static Format detect(byte[] bytes) {
		if (bytes.length >= 3
				&& (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
				&& (bytes[2] & 0xFF) == 0xFF) {
			return Format.JPEG;
		}
		if (bytes.length >= 8
				&& (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
				&& bytes[3] == 'G' && bytes[4] == 0x0D && bytes[5] == 0x0A
				&& bytes[6] == 0x1A && bytes[7] == 0x0A) {
			return Format.PNG;
		}
		if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
				&& bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E'
				&& bytes[10] == 'B' && bytes[11] == 'P') {
			return Format.WEBP;
		}
		if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
			throw new MediaException(MediaException.Reason.UNSUPPORTED_FORMAT,
					"Không hỗ trợ ảnh GIF. Vui lòng chọn JPEG, PNG hoặc WebP");
		}
		if (looksLikeSvg(bytes)) {
			throw new MediaException(MediaException.Reason.UNSUPPORTED_FORMAT,
					"Không hỗ trợ ảnh SVG. Vui lòng chọn JPEG, PNG hoặc WebP");
		}
		throw new MediaException(MediaException.Reason.UNSUPPORTED_FORMAT,
				"Chỉ nhận ảnh JPEG, PNG hoặc WebP");
	}

	private static boolean looksLikeSvg(byte[] bytes) {
		int length = Math.min(bytes.length, MAGIC_READ_LIMIT);
		String start = new String(bytes, 0, length, StandardCharsets.UTF_8)
				.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
		return start.startsWith("<svg") || (start.startsWith("<?xml") && start.contains("<svg"));
	}

	/**
	 * WebP động chứa chunk {@code ANIM}/{@code ANMF}, hoặc cờ animation trong VP8X.
	 * {@link ImageIO} chỉ đọc frame đầu nên nếu không chặn, người dùng sẽ tưởng ảnh
	 * động được lưu nhưng thực tế chỉ còn một khung hình.
	 */
	static boolean animatedWebp(byte[] bytes) {
		int offset = 12;
		while (offset + 8 <= bytes.length) {
			String fourcc = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
			long size = readLittleEndian(bytes, offset + 4) & 0xFFFFFFFFL;
			if ("ANIM".equals(fourcc) || "ANMF".equals(fourcc)) {
				return true;
			}
			if ("VP8X".equals(fourcc) && offset + 8 < bytes.length
					&& (bytes[offset + 8] & 0x02) != 0) {
				return true;
			}
			long next = offset + 8L + size + (size & 1L);
			if (size == 0 || next <= offset || next > bytes.length) {
				break;
			}
			offset = (int) next;
		}
		return false;
	}

	private static int readLittleEndian(byte[] bytes, int offset) {
		return (bytes[offset] & 0xFF)
				| ((bytes[offset + 1] & 0xFF) << 8)
				| ((bytes[offset + 2] & 0xFF) << 16)
				| ((bytes[offset + 3] & 0xFF) << 24);
	}

	enum Format {
		JPEG, PNG, WEBP
	}
}
