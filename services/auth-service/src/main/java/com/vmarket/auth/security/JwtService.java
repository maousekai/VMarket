package com.vmarket.auth.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.vmarket.auth.config.AuthJwtProperties;
import com.vmarket.auth.entity.User;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Sinh JWT access token (HS256). Khoá ký lấy từ {@code auth.jwt.secret} — dùng
 * chung với API Gateway khi gateway verify (PBL6-46).
 *
 * <p>Claims: {@code sub} = user id (ULID), {@code email}, {@code username},
 * {@code roles} (mảng), {@code email_verified}, {@code jti} (định danh duy nhất
 * của riêng token này — chưa có consumer, để dành cho việc thu hồi/blacklist theo
 * token sau này nếu cần), {@code iss} = auth-service.
 */
@Service
public class JwtService {

	private static final String ISSUER = "auth-service";

	private final SecretKey key;
	private final long accessTtlSeconds;

	public JwtService(AuthJwtProperties props) {
		this.key = Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8));
		this.accessTtlSeconds = props.getAccessTtl().toSeconds();
	}

	public long getAccessTtlSeconds() {
		return accessTtlSeconds;
	}

	public String createAccessToken(User user, Collection<String> roleNames) {
		Instant now = Instant.now();
		return Jwts.builder()
				.issuer(ISSUER)
				.id(UlidCreator.getUlid().toString())
				.subject(user.getId())
				.claim("email", user.getEmail())
				.claim("username", user.getUsername())
				.claim("roles", List.copyOf(roleNames))
				.claim("email_verified", user.isEmailVerified())
				.issuedAt(java.util.Date.from(now))
				.expiration(java.util.Date.from(now.plusSeconds(accessTtlSeconds)))
				.signWith(key, Jwts.SIG.HS256)
				.compact();
	}
}
