package com.vmarket.gateway.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chính sách route public / protected tại gateway.
 *
 * <p><b>PBL6-38 chỉ xác thực (token hợp lệ hay không)</b>, KHÔNG phân quyền theo
 * vai trò (RBAC per-role thuộc PBL6-46). Quy tắc ở đây chia route thành 2 nhóm:
 *
 * <ul>
 *   <li>{@code public-paths}: mọi HTTP method đều được truy cập không cần token
 *       (đăng ký/đăng nhập, health, swagger...).</li>
 *   <li>{@code public-get-paths}: CHỈ GET/HEAD được truy cập không cần token,
 *       dùng cho việc xem sản phẩm/shop công khai của người mua chưa đăng nhập.
 *       Các method khác (POST/PUT/DELETE) vẫn bắt buộc token.</li>
 * </ul>
 *
 * <p>Các route còn lại đều là protected: thiếu/token sai → HTTP 401.
 *
 * <p>Pattern dùng cú pháp Ant ({@code /**} khớp nhiều đoạn path); match trên
 * {@code request.getRequestURI()}.
 */
@ConfigurationProperties(prefix = "app.security")
public class GatewaySecurityProperties {

	/** Path public mọi method. */
	private List<String> publicPaths = new ArrayList<>();

	/** Path chỉ public cho GET/HEAD. */
	private List<String> publicGetPaths = new ArrayList<>();

	public List<String> getPublicPaths() {
		return publicPaths;
	}

	public void setPublicPaths(List<String> publicPaths) {
		this.publicPaths = publicPaths;
	}

	public List<String> getPublicGetPaths() {
		return publicGetPaths;
	}

	public void setPublicGetPaths(List<String> publicGetPaths) {
		this.publicGetPaths = publicGetPaths;
	}
}