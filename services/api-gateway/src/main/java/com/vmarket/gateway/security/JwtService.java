package com.vmarket.gateway.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.vmarket.gateway.config.GatewayJwtProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Xác minh JWT access token (HS256) do auth-service sinh ra, không triệu gọi
 * auth-service — dùng đúng secret chia sẻ ({@code AUTH_JWT_SECRET}).
 *
 * <p>Token hợp lệ phải: ký đúng HS256, {@code iss} = {@code auth-service}, chưa
 * hết hạn ({@code exp}). Cấu trúc claims khớp với
 * {@code com.vmarket.auth.security.JwtService} của auth-service:
 * {@code sub} = userId (ULID), {@code roles} (mảng), {@code email},
 * {@code username}, {@code email_verified}.
 */
@Service
public class JwtService {

	public static final String ISSUER = "auth-service";

	private final SecretKey key;

	public JwtService(GatewayJwtProperties props) {
		this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Parse + xác minh chữ ký, issuer và thời hạn của token.
	 *
	 * <p>Cho phép lệch đồng hồ 30 giây ({@code clockSkewSeconds}) để tránh 401 oan
	 * cho token còn hợp lệ khi đồng hồ giữa auth-service và gateway lệch nhẹ.
	 *
	 * @return claims của token hợp lệ
	 * @throws io.jsonwebtoken.JwtException       signature sai / hết hạn / sai issuer
	 * @throws IllegalArgumentException           token rỗng / không parse được
	 */
	public Claims parse(String token) {
		return Jwts.parser()
				.verifyWith(key)
				.requireIssuer(ISSUER)
				.clockSkewSeconds(30)
				.build()
				.parseSignedClaims(token)
				.getPayload();
	}
}