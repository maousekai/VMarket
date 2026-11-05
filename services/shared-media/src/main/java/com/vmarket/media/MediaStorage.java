package com.vmarket.media;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Giao tiếp MinIO qua S3 API: upload ảnh đã chuẩn hoá, xoá ảnh cũ và tạo bucket/policy
 * công khai ở lần dùng đầu tiên.
 *
 * <p>Key luôn chứa hash SHA-256 của nội dung nên một ảnh được upload lại sẽ ghi đè đúng
 * object cũ thay vì sinh rác mới. URL lưu trong CSDL là URL công khai dạng
 * {@code {publicOrigin}/{bucket}/{key}}; không bao giờ lưu presigned URL vì chúng hết hạn.
 */
public class MediaStorage {

	private static final Logger log = LoggerFactory.getLogger(MediaStorage.class);

	private final S3Client s3;
	private final MediaProperties properties;
	private volatile boolean bucketReady;

	public MediaStorage(S3Client s3, MediaProperties properties) {
		this.s3 = s3;
		this.properties = properties;
	}

	/**
	 * Upload ảnh với key {@code prefix}/{sha256}.{ext}.
	 *
	 * @param keyPrefix tiền tố do service quyết định, vd {@code avatars/{userId}} hoặc
	 *                  {@code shops/{userId}/logo}
	 */
	public StoredMedia upload(String keyPrefix, ProcessedImage image) {
		String prefix = normalizePrefix(keyPrefix);
		String key = prefix + "/" + sha256(image.bytes()) + "." + image.extension();
		ensureBucketReady();
		try {
			s3.putObject(PutObjectRequest.builder()
							.bucket(properties.getBucket())
							.key(key)
							.contentType(image.contentType())
							// Key bất biến theo nội dung -> cache dài hạn an toàn.
							.cacheControl("public, max-age=31536000, immutable")
							.build(),
					RequestBody.fromBytes(image.bytes()));
			return new StoredMedia(key, publicUrl(key));
		} catch (S3Exception ex) {
			throw storageUnavailable("Không lưu được ảnh", ex);
		}
	}

	/** Xoá theo URL; URL không thuộc prefix của người dùng bị từ chối. */
	public void deleteByUrl(String url, String keyPrefix) {
		String key = keyOf(url, keyPrefix);
		try {
			s3.deleteObject(DeleteObjectRequest.builder()
					.bucket(properties.getBucket())
					.key(key)
					.build());
		} catch (S3Exception ex) {
			throw storageUnavailable("Không xoá được ảnh", ex);
		}
	}

	/**
	 * Xoá best-effort dùng cho dọn ảnh cũ sau commit hoặc khi client báo lưu thất bại.
	 * Không ném lỗi: ảnh cũ còn sót không đáng để biến một thao tác đã thành công thành
	 * lỗi, và URL ngoài hệ thống tuyệt đối không bị chạm tới.
	 */
	public void deleteQuietly(String url, String keyPrefix) {
		try {
			deleteByUrl(url, keyPrefix);
		} catch (MediaException ex) {
			if (ex.getReason() != MediaException.Reason.INVALID_MEDIA_URL) {
				log.warn("Không dọn được ảnh cũ {}: {}", url, ex.getMessage());
			}
		} catch (RuntimeException ex) {
			log.warn("Không dọn được ảnh cũ {}", url, ex);
		}
	}

	public boolean isManaged(String url, String keyPrefix) {
		try {
			keyOf(url, keyPrefix);
			return true;
		} catch (MediaException ex) {
			return false;
		}
	}

	/**
	 * Đổi URL công khai thành key trong bucket, đồng thời chặn người dùng xoá ảnh của
	 * người khác bằng cách gửi URL của họ lên.
	 */
	public String keyOf(String url, String keyPrefix) {
		String prefix = normalizePrefix(keyPrefix);
		if (url == null || url.isBlank() || url.indexOf('?') >= 0 || url.indexOf('#') >= 0) {
			throw invalidUrl();
		}

		String base = publicOrigin() + "/" + properties.getBucket() + "/";
		if (!url.startsWith(base)) {
			throw invalidUrl();
		}
		String key = url.substring(base.length());
		if (key.isBlank() || key.startsWith("/") || key.contains("..") || key.contains("\\")
				|| key.contains(" ")) {
			throw invalidUrl();
		}
		if (!key.startsWith(prefix + "/")) {
			throw invalidUrl();
		}
		return key;
	}

	public String publicUrl(String key) {
		return publicOrigin() + "/" + properties.getBucket() + "/" + key;
	}

	/**
	 * Tạo bucket + policy đọc công khai cho đúng các prefix ảnh. Chạy một lần cho mỗi
	 * instance; nếu MinIO chưa lên, lần upload đầu tiên sẽ báo lỗi rõ ràng thay vì làm
	 * service không khởi động được.
	 */
	private void ensureBucketReady() {
		if (bucketReady) {
			return;
		}
		synchronized (this) {
			if (bucketReady) {
				return;
			}
			try {
				try {
					s3.headBucket(HeadBucketRequest.builder().bucket(properties.getBucket()).build());
				} catch (NoSuchBucketException ex) {
					createBucket();
				} catch (S3Exception ex) {
					if (ex.statusCode() == 404) {
						createBucket();
					} else {
						throw ex;
					}
				}
				putPublicReadPolicy();
				bucketReady = true;
			} catch (S3Exception ex) {
				throw storageUnavailable("Không kết nối được kho ảnh", ex);
			}
		}
	}

	private void createBucket() {
		try {
			s3.createBucket(CreateBucketRequest.builder().bucket(properties.getBucket()).build());
			log.info("Đã tạo bucket ảnh {}", properties.getBucket());
		} catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException ex) {
			log.debug("Bucket {} đã tồn tại", properties.getBucket());
		}
	}

	private void putPublicReadPolicy() {
		String bucket = properties.getBucket();
		String policy = """
				{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",
				"Action":["s3:GetObject"],"Resource":[
				"arn:aws:s3:::%s/avatars/*",
				"arn:aws:s3:::%s/shops/*",
				"arn:aws:s3:::%s/products/*"]}]}
				""".formatted(bucket, bucket, bucket);
		try {
			s3.putBucketPolicy(PutBucketPolicyRequest.builder()
					.bucket(bucket)
					.policy(policy)
					.build());
		} catch (S3Exception ex) {
			// Credential bị giới hạn (không phải root) thường không có quyền PutBucketPolicy.
			// Bucket/policy do operator quản lý sẵn là hợp lệ; chỉ lỗi khác mới đáng chặn.
			if (ex.statusCode() == 403) {
				log.info("Không đặt được bucket policy (credential bị giới hạn); "
						+ "giả định operator đã cấu hình quyền đọc công khai cho {}", bucket);
				return;
			}
			throw ex;
		}
	}

	private static String normalizePrefix(String prefix) {
		if (prefix == null || prefix.isBlank()) {
			throw new IllegalArgumentException("keyPrefix không được để trống");
		}
		String normalized = prefix.replace('\\', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		if (normalized.isBlank() || normalized.contains("..")) {
			throw new IllegalArgumentException("keyPrefix không hợp lệ: " + prefix);
		}
		return normalized;
	}

	private String publicOrigin() {
		String origin = properties.getPublicOrigin().trim();
		while (origin.endsWith("/")) {
			origin = origin.substring(0, origin.length() - 1);
		}
		return origin;
	}

	private static MediaException invalidUrl() {
		return new MediaException(MediaException.Reason.INVALID_MEDIA_URL,
				"URL ảnh không thuộc kho ảnh của bạn");
	}

	private static MediaException storageUnavailable(String message, Throwable cause) {
		return new MediaException(MediaException.Reason.STORAGE_UNAVAILABLE, message, cause);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("JVM thiếu SHA-256", ex);
		}
	}

	/** Tạo client S3 trỏ vào MinIO. Không gọi mạng ở bước này. */
	public static S3Client buildClient(MediaProperties properties) {
		UrlConnectionHttpClient.Builder httpClientBuilder = UrlConnectionHttpClient.builder()
				.connectionTimeout(properties.getConnectTimeout())
				.socketTimeout(properties.getReadTimeout());
		SdkHttpClient httpClient = httpClientBuilder.build();

		return S3Client.builder()
				.endpointOverride(URI.create(properties.getEndpoint()))
				.region(Region.of(properties.getRegion()))
				.credentialsProvider(StaticCredentialsProvider.create(
						AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())))
				.serviceConfiguration(S3Configuration.builder()
						.pathStyleAccessEnabled(properties.isPathStyleAccess())
						.build())
				.overrideConfiguration(ClientOverrideConfiguration.builder()
						.apiCallTimeout(properties.getReadTimeout())
						.apiCallAttemptTimeout(properties.getReadTimeout())
						.build())
				.httpClient(httpClient)
				.build();
	}

	/**
	 * Chỉ dọn ảnh sau khi transaction đã commit. Nếu transaction rollback, ảnh mới chưa
	 * từng được lưu vào CSDL nên ảnh cũ vẫn phải còn.
	 */
	public static void afterCommit(Runnable cleanup) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			cleanup.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				cleanup.run();
			}
		});
	}
}
