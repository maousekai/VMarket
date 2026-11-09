package com.vmarket.media;

/**
 * Loại ảnh mà hệ thống nhận. Mọi giới hạn nằm ở đây để service và test dùng chung một
 * nguồn sự thật, tránh chỗ nhận 2 MB chỗ nhận 5 MB.
 */
public enum ImageKind {

	/** Ảnh đại diện: vuông 512×512, PNG (giữ trong suốt), tối đa 2 MB. */
	AVATAR("ảnh đại diện", 2 * 1024 * 1024, 512, 512, "png"),

	/** Logo gian hàng: cùng chuẩn với avatar nhưng thông điệp lỗi khác. */
	LOGO("logo gian hàng", 2 * 1024 * 1024, 512, 512, "png"),

	/** Ảnh bìa: vừa trong 1600×900, JPEG, tối đa 5 MB. */
	COVER("ảnh bìa", 5 * 1024 * 1024, 1600, 900, "jpg"),

	/**
	 * Ảnh sản phẩm: vừa trong 1200×1200, JPEG, tối đa 5 MB. Không cắt vuông — giữ
	 * nguyên khung hình người bán chụp; thẻ sản phẩm tự cắt bằng CSS khi hiển thị.
	 */
	PRODUCT("ảnh sản phẩm", 5 * 1024 * 1024, 1200, 1200, "jpg"),

	/** Ảnh minh chứng trả hàng: vừa trong 1600×1600, JPEG, tối đa 5 MB. */
	EVIDENCE("ảnh minh chứng", 5 * 1024 * 1024, 1600, 1600, "jpg");

	private static final long MAX_PIXELS = 25_000_000L;

	private final String label;
	private final long maxBytes;
	private final int maxWidth;
	private final int maxHeight;
	private final String extension;

	ImageKind(String label, long maxBytes, int maxWidth, int maxHeight, String extension) {
		this.label = label;
		this.maxBytes = maxBytes;
		this.maxWidth = maxWidth;
		this.maxHeight = maxHeight;
		this.extension = extension;
	}

	public String label() {
		return label;
	}

	public long maxBytes() {
		return maxBytes;
	}

	public int maxWidth() {
		return maxWidth;
	}

	public int maxHeight() {
		return maxHeight;
	}

	public String extension() {
		return extension;
	}

	/** Cắt vuông và giữ PNG (trong suốt) — chỉ cho avatar và logo. */
	public boolean square() {
		return this == AVATAR || this == LOGO;
	}

	public static long maxPixels() {
		return MAX_PIXELS;
	}
}
