package com.vmarket.shop.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.vmarket.shop.client.UserServiceClient;
import com.vmarket.shop.client.UserServiceClient.UserSummary;
import com.vmarket.shop.dto.ShopResponse;
import com.vmarket.shop.dto.ShopStatusHistoryResponse;

import lombok.RequiredArgsConstructor;

/**
 * Gắn tên/avatar chủ shop và người xử lý vào response.
 *
 * <p>user-service lỗi thì tên để {@code null}: đây là dữ liệu hiển thị phụ, không
 * được biến một trang danh sách vẫn dùng được thành lỗi 5xx.
 */
@Service
@RequiredArgsConstructor
public class ShopProfileEnricher {

	private final UserServiceClient userServiceClient;

	private Map<String, UserSummary> safeSummaries(Collection<String> ids) {
		try {
			Map<String, UserSummary> result = userServiceClient.summaries(ids);
			return result == null ? Map.of() : result;
		} catch (RuntimeException ex) {
			return Map.of();
		}
	}

	public ShopResponse enrich(ShopResponse shop) {
		if (shop == null) {
			return null;
		}
		Map<String, UserSummary> owners = safeSummaries(List.of(shop.ownerId()));
		UserSummary owner = owners.get(shop.ownerId());
		return shop.withOwner(owner == null ? null : owner.fullName(),
				owner == null ? null : owner.avatarUrl());
	}

	public List<ShopResponse> enrichAll(Collection<ShopResponse> shops) {
		List<String> ownerIds = shops.stream().map(ShopResponse::ownerId).distinct().toList();
		Map<String, UserSummary> owners = safeSummaries(ownerIds);
		return shops.stream().map(shop -> {
			UserSummary owner = owners.get(shop.ownerId());
			return shop.withOwner(owner == null ? null : owner.fullName(),
					owner == null ? null : owner.avatarUrl());
		}).toList();
	}

	public List<ShopStatusHistoryResponse> enrichHistory(Collection<ShopStatusHistoryResponse> history) {
		List<String> actorIds = history.stream().map(ShopStatusHistoryResponse::changedBy).distinct().toList();
		Map<String, UserSummary> actors = safeSummaries(actorIds);
		return history.stream().map(row -> {
			UserSummary actor = actors.get(row.changedBy());
			return row.withActor(actor == null ? null : actor.fullName());
		}).toList();
	}
}
