package com.vmarket.media;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Tự cấu hình kho ảnh dùng chung. Service chỉ cần khai {@code app.media.*} và thêm
 * dependency {@code shared-media}; không phải tạo S3Client thủ công.
 */
@AutoConfiguration
@EnableConfigurationProperties(MediaProperties.class)
public class MediaAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(name = "mediaS3Client")
	public S3Client mediaS3Client(MediaProperties properties) {
		return MediaStorage.buildClient(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public ImageProcessor imageProcessor() {
		return new ImageProcessor();
	}

	@Bean
	@ConditionalOnMissingBean
	public MediaStorage mediaStorage(S3Client mediaS3Client, MediaProperties properties) {
		return new MediaStorage(mediaS3Client, properties);
	}
}
