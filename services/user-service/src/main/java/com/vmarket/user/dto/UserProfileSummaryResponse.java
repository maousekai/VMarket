package com.vmarket.user.dto;

import com.vmarket.user.entity.UserProfile;

/** Tóm tắt hồ sơ tối thiểu để service khác hiển thị tên/avatar. */
public record UserProfileSummaryResponse(String userId, String fullName, String avatarUrl) {

	public static UserProfileSummaryResponse from(UserProfile profile) {
		return new UserProfileSummaryResponse(
				profile.getUserId(), profile.getFullName(), profile.getAvatarUrl());
	}
}
