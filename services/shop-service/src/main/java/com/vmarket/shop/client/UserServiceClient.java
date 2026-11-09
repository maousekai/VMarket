package com.vmarket.shop.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Đọc tóm tắt hồ sơ (tên, avatar) từ user-service.
 *
 * <p>Đây chỉ là dữ liệu hiển thị phụ. user-service không sẵn sàng thì trả map rỗng
 * thay vì làm hỏng cả danh sách gian hàng của Admin; nơi gọi sẽ để tên/avatar là
 * {@code null} và UI hiển thị "—".
 */
@Slf4j
public class UserServiceClient {

	private static final int BATCH_SIZE = 100;

	private final RestClient restClient;

	public UserServiceClient(RestClient restClient) {
		this.restClient = restClient;
	}

	public Map<String, UserSummary> summaries(Collection<String> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return Map.of();
		}
		List<String> unique = userIds.stream()
				.filter(id -> id != null && !id.isBlank())
				.distinct()
				.toList();
		Map<String, UserSummary> result = new LinkedHashMap<>();
		try {
			for (int start = 0; start < unique.size(); start += BATCH_SIZE) {
				List<String> chunk = new ArrayList<>(unique.subList(start, Math.min(start + BATCH_SIZE, unique.size())));
				List<UserSummary> rows = restClient.post()
						.uri("/internal/users/profiles")
						.body(Map.of("userIds", chunk))
						.retrieve()
						.body(new ParameterizedTypeReference<List<UserSummary>>() {
						});
				if (rows != null) {
					rows.forEach(row -> result.putIfAbsent(row.userId(), row));
				}
			}
			return result;
		} catch (RuntimeException ex) {
			log.warn("Không lấy được tóm tắt hồ sơ từ user-service: {}", ex.getMessage());
			return Map.of();
		}
	}

	public record UserSummary(String userId, String fullName, String avatarUrl) {
	}
}
