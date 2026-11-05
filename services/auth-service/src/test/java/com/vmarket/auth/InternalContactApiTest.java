package com.vmarket.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.auth.config.InternalApiProperties;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.repository.UserRepository;

/** API nội bộ tra email người nhận cho notification-service (PBL6-23). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InternalContactApiTest {

	@Autowired
	private MockMvc mvc;
	@Autowired
	private UserRepository users;
	@Autowired
	private InternalApiProperties keys;

	@Test
	void returnsContactWithInternalKey() throws Exception {
		User user = new User();
		user.setEmail("contact-" + UUID.randomUUID() + "@example.com");
		user.setUsername("contact-" + UUID.randomUUID().toString().substring(0, 8));
		user.setEmailVerified(true);
		users.saveAndFlush(user);

		mvc.perform(get("/internal/users/{id}/contact", user.getId()).header("X-Internal-Api-Key", keys.getApiKey()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(user.getId()))
				.andExpect(jsonPath("$.email").value(user.getEmail()))
				.andExpect(jsonPath("$.emailVerified").value(true))
				.andExpect(jsonPath("$.suspended").value(false))
				.andExpect(jsonPath("$.roles").doesNotExist());
	}

	@Test
	void rejectsMissingKeyAndUnknownUser() throws Exception {
		mvc.perform(get("/internal/users/{id}/contact", "01JBQ9YDX7K3M8N5P2R4T6V8BB"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/users/{id}/contact", "01JBQ9YDX7K3M8N5P2R4T6V8BB")
				.header("X-Internal-Api-Key", keys.getApiKey()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}
}
