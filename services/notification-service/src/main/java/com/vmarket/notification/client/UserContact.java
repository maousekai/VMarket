package com.vmarket.notification.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Thông tin liên hệ từ {@code GET /internal/users/{userId}/contact} của auth-service. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserContact(String userId, String email, boolean emailVerified, boolean suspended) {
}
