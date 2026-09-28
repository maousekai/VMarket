package com.vmarket.auth.security;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Trích User-Agent + IP client từ request (FR-AUTH-06, hiển thị danh sách phiên).
 *
 * <p>Best-effort, CHỈ để hiển thị — không dùng cho bất kỳ quyết định bảo mật/giới
 * hạn tần suất nào. Ưu tiên đọc {@code X-Forwarded-For}, rơi về
 * {@code getRemoteAddr()} nếu thiếu. Lưu ý: khác với rate-limit ở gateway (nơi
 * CHỦ ĐỘNG không tin XFF vì gateway là network edge — request tới gateway không
 * đi qua proxy nào khác), gateway hiện tại KHÔNG tự thêm/ghi đè
 * {@code X-Forwarded-For} khi proxy sang auth-service — giá trị đọc được ở đây là
 * bất kỳ header nào caller (kể cả client cuối) tự gửi lên, hoàn toàn có thể giả
 * mạo. Chấp nhận được vì hệ quả chỉ là hiển thị sai IP trong danh sách phiên của
 * chính người dùng đó, không ảnh hưởng tới ai khác hay tới quyết định cấp quyền.
 */
@Component
public class DeviceMetaResolver {

	private static final String XFF_HEADER = "X-Forwarded-For";
	private static final int USER_AGENT_MAX_LENGTH = 255;
	private static final int IP_MAX_LENGTH = 45; // du cho IPv6 (cot refresh_tokens.ip_address)

	public DeviceMeta resolve(HttpServletRequest request) {
		return new DeviceMeta(
				truncate(request.getHeader("User-Agent"), USER_AGENT_MAX_LENGTH),
				truncate(clientIp(request), IP_MAX_LENGTH));
	}

	private String clientIp(HttpServletRequest request) {
		String xff = request.getHeader(XFF_HEADER);
		if (StringUtils.hasText(xff)) {
			return xff.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}

	private String truncate(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		return value.length() > maxLength ? value.substring(0, maxLength) : value;
	}
}
