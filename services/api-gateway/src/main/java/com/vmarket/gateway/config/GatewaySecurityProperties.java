package com.vmarket.gateway.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chính sách route public / protected / theo vai trò tại gateway.
 *
 * <ul>
 *   <li>{@code public-paths}: mọi HTTP method đều được truy cập không cần token
 *       (đăng ký/đăng nhập, health, swagger...).</li>
 *   <li>{@code public-get-paths}: CHỈ GET/HEAD được truy cập không cần token,
 *       dùng cho việc xem sản phẩm/shop công khai của người mua chưa đăng nhập.
 *       Các method khác (POST/PUT/DELETE) vẫn bắt buộc token.</li>
 *   <li>{@code role-required-paths} (PBL6-46): route protected (đã qua bước xác
 *       thực JWT ở trên) còn đòi hỏi user có ÍT NHẤT MỘT trong các vai trò liệt
 *       kê ({@code roles}) — thiếu vai trò phù hợp → 403. Không khớp method nào
 *       trong {@code methods} (để trống = mọi method) thì không áp dụng rule đó.</li>
 * </ul>
 *
 * <p>Các route protected còn lại (không khớp {@code role-required-paths}) chỉ cần
 * token hợp lệ, không phân biệt vai trò — giữ nguyên hành vi PBL6-38.
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

	/** Route protected còn đòi hỏi vai trò cụ thể (PBL6-46). */
	private List<RoleRule> roleRequiredPaths = new ArrayList<>();

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

	public List<RoleRule> getRoleRequiredPaths() {
		return roleRequiredPaths;
	}

	public void setRoleRequiredPaths(List<RoleRule> roleRequiredPaths) {
		this.roleRequiredPaths = roleRequiredPaths;
	}

	/** Một quy tắc: pattern + (method tuỳ chọn) + danh sách vai trò hợp lệ (any-of). */
	public static class RoleRule {

		private String pattern;

		/** Method áp dụng rule; để trống = mọi method. */
		private List<String> methods = new ArrayList<>();

		/** Cần ít nhất MỘT vai trò trong danh sách này. */
		private List<String> roles = new ArrayList<>();

		public String getPattern() {
			return pattern;
		}

		public void setPattern(String pattern) {
			this.pattern = pattern;
		}

		public List<String> getMethods() {
			return methods;
		}

		public void setMethods(List<String> methods) {
			this.methods = methods;
		}

		public List<String> getRoles() {
			return roles;
		}

		public void setRoles(List<String> roles) {
			this.roles = roles;
		}
	}
}
