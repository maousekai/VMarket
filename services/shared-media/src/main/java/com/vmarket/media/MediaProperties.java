package com.vmarket.media;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Cấu hình MinIO dùng chung cho user-service và shop-service (prefix {@code app.media}).
 *
 * <p>Ba giá trị {@code endpoint}, {@code publicOrigin} và {@code bucket} phải khớp nhau:
 * endpoint là địa chỉ service gọi S3, còn publicOrigin là origin browser dùng để tải ảnh.
 * Trong docker-compose, endpoint là {@code http://minio:9000} nhưng publicOrigin vẫn là
 * {@code http://localhost:9000} vì browser chạy trên máy host.
 */
@ConfigurationProperties(prefix = "app.media")
@Validated
public class MediaProperties {

	/** Địa chỉ S3 API mà service gọi (vd http://minio:9000). */
	@NotBlank
	private String endpoint;

	/** Origin công khai của ảnh (vd http://localhost:9000). Không có dấu / ở cuối. */
	@NotBlank
	private String publicOrigin;

	@NotBlank
	private String bucket;

	@NotBlank
	private String accessKey;

	@NotBlank
	private String secretKey;

	/** MinIO bỏ qua region nhưng SDK S3 bắt buộc có; mặc định hợp lệ cho MinIO. */
	@NotBlank
	private String region = "us-east-1";

	/** MinIO luôn dùng path-style URL: {origin}/{bucket}/{key}. */
	private boolean pathStyleAccess = true;

	@NotNull
	private Duration connectTimeout = Duration.ofSeconds(2);

	@NotNull
	private Duration readTimeout = Duration.ofSeconds(10);

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	public String getPublicOrigin() {
		return publicOrigin;
	}

	public void setPublicOrigin(String publicOrigin) {
		this.publicOrigin = publicOrigin;
	}

	public String getBucket() {
		return bucket;
	}

	public void setBucket(String bucket) {
		this.bucket = bucket;
	}

	public String getAccessKey() {
		return accessKey;
	}

	public void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public String getRegion() {
		return region;
	}

	public void setRegion(String region) {
		this.region = region;
	}

	public boolean isPathStyleAccess() {
		return pathStyleAccess;
	}

	public void setPathStyleAccess(boolean pathStyleAccess) {
		this.pathStyleAccess = pathStyleAccess;
	}

	public Duration getConnectTimeout() {
		return connectTimeout;
	}

	public void setConnectTimeout(Duration connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	public Duration getReadTimeout() {
		return readTimeout;
	}

	public void setReadTimeout(Duration readTimeout) {
		this.readTimeout = readTimeout;
	}
}
