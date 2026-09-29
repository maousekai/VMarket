package com.vmarket.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Cấu hình JWT của order-service — chỉ có {@code secret} để <b>verify</b>.
 *
 * <p>Dùng chung prefix {@code auth.jwt} và chung biến môi trường
 * {@code AUTH_JWT_SECRET} với auth-service: token do auth-service ký bằng HS256,
 * order-service verify bằng đúng khoá đó (thuật toán đối xứng nên hai bên phải
 * cùng một chuỗi bí mật). Đặt tên khác đi sẽ dẫn tới cảnh hai service cùng chạy
 * mà token của bên này bên kia không đọc được.
 *
 * <p>Cố ý <b>không</b> khai {@code access-ttl} / {@code refresh-ttl}: thời hạn là
 * việc của bên phát hành token. Bên verify chỉ cần kiểm tra {@code exp}, và jjwt
 * đã tự làm điều đó.
 *
 * <p>Không có giá trị mặc định ở {@code application.yml} nền — thiếu
 * {@code AUTH_JWT_SECRET} ở prod là service chết ngay lúc khởi động thay vì chạy
 * với khoá đoán được. Giá trị giả cho local nằm ở {@code application-dev.yml}.
 */
@ConfigurationProperties(prefix = "auth.jwt")
@Validated
@Getter
@Setter
public class OrderJwtProperties {

	/**
	 * Khoá bí mật HS256 — tối thiểu 32 ký tự (256-bit), dùng chung với auth-service.
	 *
	 * <p>{@code @Size} chứ không chỉ {@code @NotBlank}: khoá ngắn hơn 32 byte vẫn qua
	 * được bean validation nhưng {@code Keys.hmacShaKeyFor} sẽ ném
	 * {@code WeakKeyException} lúc filter chạy — tức là service khởi động xanh rồi
	 * mọi request có token đều 500. Chặn ngay lúc bind cấu hình thì rõ nguyên nhân hơn.
	 */
	@NotBlank
	@Size(min = 32, message = "AUTH_JWT_SECRET phải >= 32 ký tự (256-bit) cho HS256")
	private String secret;
}