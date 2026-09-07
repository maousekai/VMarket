package com.vmarket.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * Refresh token là chuỗi ngẫu nhiên mờ (không phải JWT). Giá trị gốc chỉ trả cho
 * client một lần; DB chỉ lưu SHA-256 (hex) của nó.
 */
@Component
public class OpaqueTokenCodec {

	private static final int TOKEN_BYTES = 32; // 256-bit
	private final SecureRandom random = new SecureRandom();
	private final Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();

	/** Sinh giá trị refresh token gốc (đưa cho client). */
	public String generate() {
		byte[] buf = new byte[TOKEN_BYTES];
		random.nextBytes(buf);
		return urlEncoder.encodeToString(buf);
	}

	/** Băm giá trị gốc để tra cứu / lưu DB. */
	public String hash(String rawToken) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 không khả dụng", e);
		}
	}
}
