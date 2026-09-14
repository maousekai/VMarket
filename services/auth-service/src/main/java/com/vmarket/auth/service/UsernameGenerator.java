package com.vmarket.auth.service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Sinh username cho tài khoản tạo tự động (luồng OTP) từ phần trước {@code @} của
 * email: chuẩn hoá về {@code [a-z0-9._-]}, dài 3–50, thêm hậu tố số nếu trùng.
 */
public final class UsernameGenerator {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int MAX_LENGTH = 50;
	private static final int BASE_MAX = 42; // chừa chỗ cho hậu tố
	private static final int MAX_TRIES = 10;

	private UsernameGenerator() {
	}

	/**
	 * @param email  email nguồn
	 * @param isTaken kiểm tra username đã tồn tại chưa (vd {@code userRepository::existsByUsername})
	 */
	public static String generate(String email, Predicate<String> isTaken) {
		String local = email == null ? "" : email.split("@", 2)[0];
		String base = local.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9._-]", "")   // chỉ giữ ký tự hợp lệ
				.replaceAll("[._-]{2,}", ".")      // gộp dấu phân cách liên tiếp
				.replaceAll("^[._-]+", "")         // bỏ dấu phân cách đầu
				.replaceAll("[._-]+$", "");        // bỏ dấu phân cách cuối
		if (base.length() < 3 || !base.matches(".*[a-z0-9].*")) {
			base = "user";
		}
		if (base.length() > BASE_MAX) {
			base = base.substring(0, BASE_MAX);
		}

		if (!isTaken.test(base)) {
			return base;
		}
		for (int i = 0; i < MAX_TRIES; i++) {
			String candidate = truncate(base, MAX_LENGTH - 5) + (1000 + RANDOM.nextInt(9000));
			if (!isTaken.test(candidate)) {
				return candidate;
			}
		}
		// Rất hiếm: dùng hậu tố dài hơn để gần như chắc chắn không trùng.
		return truncate(base, MAX_LENGTH - 9) + (10_000_000 + RANDOM.nextInt(89_999_999));
	}

	private static String truncate(String s, int max) {
		return s.length() > max ? s.substring(0, max) : s;
	}
}
