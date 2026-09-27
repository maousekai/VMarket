package com.vmarket.user.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import tools.jackson.databind.ObjectMapper;
import com.vmarket.user.entity.IdempotencyRecord;
import com.vmarket.user.service.IdempotencyOutcome;
import com.vmarket.user.service.IdempotencyService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cho mọi endpoint ghi dữ liệu nhận header {@code Idempotency-Key}: gửi lại cùng
 * một key thì nhận lại đúng response cũ thay vì tạo thêm dữ liệu trùng.
 *
 * <p>Vì sao cần: client hết thời gian chờ rồi gửi lại là chuyện bình thường, không
 * riêng gì bấm hai lần. Với {@code POST /api/users/me/addresses}, lần gửi lại tạo
 * thêm một địa chỉ y hệt — không có lỗi nào báo ra, người dùng tự phát hiện và tự
 * dọn. Đây cũng là lưới an toàn cho khoảng hở "hai địa chỉ đầu tiên cùng xin cờ mặc
 * định" trong {@code AddressService.create}.
 *
 * <p><b>Header là tuỳ chọn.</b> Thiếu nó thì request chạy y như trước, không có
 * bảo vệ. Bắt buộc sẽ làm hỏng mọi client đang chạy.
 *
 * <p>Đặt SAU {@code AuthorizationFilter} trong chuỗi filter của Spring Security
 * (xem {@code SecurityConfig}): mỗi key chỉ có nghĩa trong phạm vi một người dùng,
 * nên phải biết chắc danh tính trước.
 */
@Slf4j
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

	public static final String HEADER = "Idempotency-Key";

	/** Gắn vào response phát lại để client (và log) phân biệt được với lần chạy thật. */
	public static final String REPLAYED_HEADER = "Idempotency-Replayed";

	/**
	 * Chỉ các method ghi dữ liệu. GET/HEAD/OPTIONS vốn không đổi trạng thái nên gửi
	 * lại bao nhiêu lần cũng không sao.
	 */
	private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

	private final IdempotencyService idempotencyService;
	private final ObjectMapper objectMapper;
	private final boolean enabled;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !enabled || !MUTATING_METHODS.contains(request.getMethod());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		String key = request.getHeader(HEADER);
		if (key == null) {
			chain.doFilter(request, response);
			return;
		}

		key = key.trim();
		if (key.isEmpty() || key.length() > IdempotencyRecord.KEY_MAX_LENGTH) {
			ErrorResponseWriter.write(response, objectMapper, HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
					"Idempotency-Key phải khác rỗng và không quá " + IdempotencyRecord.KEY_MAX_LENGTH + " ký tự");
			return;
		}

		String userId = currentUserId();
		if (userId == null) {
			// Endpoint công khai (health) hoặc request sẽ bị chặn ở tầng khác. Không có
			// danh tính thì không có phạm vi để gắn key vào.
			chain.doFilter(request, response);
			return;
		}

		CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
		String path = pathWithQuery(request);
		String fingerprint = fingerprint(request.getMethod(), path, cachedRequest.getBody());

		IdempotencyOutcome outcome = idempotencyService.begin(userId, key, request.getMethod(), path, fingerprint);

		// Java 17 (xem java.version o pom) chua co pattern matching trong switch.
		if (outcome instanceof IdempotencyOutcome.Replay replay) {
			writeReplay(response, replay);
		} else if (outcome instanceof IdempotencyOutcome.Rejected rejected) {
			ErrorResponseWriter.write(response, objectMapper, rejected.status(), rejected.code(), rejected.message());
		} else {
			runAndRemember(cachedRequest, response, chain, (IdempotencyOutcome.Proceed) outcome);
		}
	}

	/** Chạy handler thật rồi lưu lại response nếu thành công. */
	private void runAndRemember(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
			IdempotencyOutcome.Proceed proceed) throws ServletException, IOException {

		ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
		boolean remembered = false;
		try {
			chain.doFilter(request, cachedResponse);

			int status = cachedResponse.getStatus();
			byte[] body = cachedResponse.getContentAsByteArray();

			if (!isSuccess(status)) {
				// Lỗi thì KHÔNG lưu: client sửa dữ liệu rồi gửi lại cùng key vẫn phải chạy
				// được. Khoá lại một lỗi 400 sẽ biến nó thành vĩnh viễn.
				return;
			}
			if (body.length > IdempotencyRecord.BODY_MAX_LENGTH) {
				// UTF-8 luôn >= 1 byte mỗi ký tự nên so byte là đủ chặt cho cột VARCHAR.
				// Không cắt bớt body: phát lại một mẩu JSON gãy còn tệ hơn là không phát lại.
				log.warn("Response {} byte vượt hạn mức lưu của Idempotency-Key, bỏ qua việc ghi nhớ", body.length);
				return;
			}

			idempotencyService.complete(proceed.recordId(), status,
					new String(body, StandardCharsets.UTF_8), cachedResponse.getContentType());
			remembered = true;

		} finally {
			// copyBodyToResponse phải chạy kể cả khi có exception, nếu không client nhận
			// response rỗng vì body còn nằm trong buffer của wrapper.
			cachedResponse.copyBodyToResponse();
			if (!remembered) {
				idempotencyService.release(proceed.recordId());
			}
		}
	}

	private void writeReplay(HttpServletResponse response, IdempotencyOutcome.Replay replay) throws IOException {
		response.setStatus(replay.status());
		response.setHeader(REPLAYED_HEADER, "true");
		if (replay.contentType() != null) {
			response.setContentType(replay.contentType());
		}
		if (replay.body() != null && !replay.body().isEmpty()) {
			response.getWriter().write(replay.body());
		}
	}

	private static boolean isSuccess(int status) {
		return status >= 200 && status < 300;
	}

	private static String currentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		return authentication.getPrincipal() instanceof String userId && !userId.isBlank() ? userId : null;
	}

	private static String pathWithQuery(HttpServletRequest request) {
		String query = request.getQueryString();
		return query == null ? request.getRequestURI() : request.getRequestURI() + '?' + query;
	}

	/**
	 * Vân tay của request: cùng key mà khác vân tay nghĩa là client dùng lại key cho
	 * một request khác — sai, và phải báo lỗi chứ không lặng lẽ phát lại kết quả cũ.
	 */
	private static String fingerprint(String method, String path, byte[] body) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(method.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '\n');
			digest.update(path.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '\n');
			digest.update(body);
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException ex) {
			// SHA-256 là thuật toán mọi JVM bắt buộc phải có.
			throw new IllegalStateException("JVM thiếu SHA-256", ex);
		}
	}
}
