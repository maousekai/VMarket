package com.vmarket.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.notification.domain.DeviceToken;
import com.vmarket.notification.domain.NotificationCategory;
import com.vmarket.notification.domain.NotificationType;
import com.vmarket.notification.dto.NotificationPreferencesDto;
import com.vmarket.notification.dto.NotificationResponse;
import com.vmarket.notification.dto.PageResponse;
import com.vmarket.notification.exception.ApiException;
import com.vmarket.notification.service.NotificationCenterService;
import com.vmarket.notification.service.NotificationPreferenceService;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** API trung tâm thông báo: xác thực bằng JWT, chỉ thao tác trên dữ liệu của chính mình. */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationApiTest {

	private static final String SECRET = "test-only-secret-0123456789-0123456789-0123456789";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private NotificationCenterService service;

	@MockitoBean
	private NotificationPreferenceService preferences;

	@Test
	void readsAndUpdatesPreferencesOfTokenSubject() throws Exception {
		var response = new NotificationPreferencesDto.Response(List.of(
				new NotificationPreferencesDto.Category("ORDER", "Đơn hàng & giao hàng", false, true)));
		when(preferences.get("u1")).thenReturn(response);
		when(preferences.update(eq("u1"), any())).thenReturn(response);

		mvc.perform(get("/api/notifications/preferences").header("Authorization", token("u1")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.categories[0].key").value("ORDER"))
				.andExpect(jsonPath("$.categories[0].email").value(false));
		mvc.perform(put("/api/notifications/preferences").header("Authorization", token("u1"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"categories\":[{\"key\":\"ORDER\",\"email\":false,\"push\":true}]}"))
				.andExpect(status().isOk());
		verify(preferences).update("u1", new NotificationPreferencesDto.Update(List.of(
				new NotificationPreferencesDto.CategoryUpdate(NotificationCategory.ORDER, false, true))));

		mvc.perform(put("/api/notifications/preferences").header("Authorization", token("u1"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"categories\":[{\"email\":false,\"push\":true}]}"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/notifications/preferences")).andExpect(status().isUnauthorized());
	}

	private static String token(String userId, String issuer, String secret) {
		return "Bearer " + Jwts.builder()
				.subject(userId)
				.issuer(issuer)
				.claim("roles", List.of("BUYER"))
				.issuedAt(new Date())
				.expiration(Date.from(Instant.now().plusSeconds(600)))
				.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
				.compact();
	}

	private static String token(String userId) {
		return token(userId, "auth-service", SECRET);
	}

	@Test
	void requiresValidAccessToken() throws Exception {
		mvc.perform(get("/api/notifications"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		mvc.perform(get("/api/notifications").header("Authorization",
				token("u1", "auth-service", "another-secret-0123456789-0123456789-0123")))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/notifications").header("Authorization", token("u1", "evil", SECRET)))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/notifications").header("X-User-Id", "u1"))
				.andExpect(status().isUnauthorized());
		String noExpiry = "Bearer " + Jwts.builder().subject("u1").issuer("auth-service")
				.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
		mvc.perform(get("/api/notifications").header("Authorization", noExpiry))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(service);
	}

	@Test
	void healthStaysPublic() throws Exception {
		mvc.perform(get("/api/notifications/health")).andExpect(status().isOk());
	}

	@Test
	void listsNotificationsOfTokenSubject() throws Exception {
		NotificationResponse item = new NotificationResponse("n1", NotificationType.ORDER_PLACED, "Đặt hàng thành công",
				"Đơn o1", "/orders/o1", Map.of("orderId", "o1"), false, null, Instant.parse("2026-11-05T10:00:00Z"));
		when(service.list("buyer-1", 0, 20, true)).thenReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

		mvc.perform(get("/api/notifications").param("unreadOnly", "true").header("Authorization", token("buyer-1")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value("n1"))
				.andExpect(jsonPath("$.items[0].type").value("ORDER_PLACED"))
				.andExpect(jsonPath("$.items[0].data.orderId").value("o1"))
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void rejectsOversizedPage() throws Exception {
		mvc.perform(get("/api/notifications").param("size", "101").header("Authorization", token("u1")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		verifyNoInteractions(service);
	}

	@Test
	void unreadCountAndMarkAllRead() throws Exception {
		when(service.unreadCount("u1")).thenReturn(3L);
		when(service.markAllRead("u1")).thenReturn(3L);

		mvc.perform(get("/api/notifications/unread-count").header("Authorization", token("u1")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(3));
		mvc.perform(patch("/api/notifications/read-all").header("Authorization", token("u1")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.updated").value(3));
	}

	@Test
	void markReadOfForeignNotificationIs404() throws Exception {
		when(service.markRead("u1", "n-of-someone-else"))
				.thenThrow(new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Không tìm thấy thông báo"));

		mvc.perform(patch("/api/notifications/n-of-someone-else/read").header("Authorization", token("u1")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
	}

	@Test
	void registersAndUnregistersDevice() throws Exception {
		mvc.perform(post("/api/notifications/devices").header("Authorization", token("u1"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"fcm-token-123\",\"platform\":\"ANDROID\"}"))
				.andExpect(status().isNoContent());
		verify(service).registerDevice("u1", "fcm-token-123", DeviceToken.Platform.ANDROID);

		mvc.perform(delete("/api/notifications/devices").header("Authorization", token("u1"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"fcm-token-123\"}"))
				.andExpect(status().isNoContent());
		verify(service).unregisterDevice("u1", "fcm-token-123");
	}

	@Test
	void invalidDeviceRequestIs400() throws Exception {
		mvc.perform(post("/api/notifications/devices").header("Authorization", token("u1"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"\",\"platform\":\"ANDROID\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fields.token").exists());
		mvc.perform(post("/api/notifications/devices").header("Authorization", token("u1"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"t\",\"platform\":\"SYMBIAN\"}"))
				.andExpect(status().isBadRequest());
		verify(service, never()).registerDevice(any(), any(), any());
	}
}
