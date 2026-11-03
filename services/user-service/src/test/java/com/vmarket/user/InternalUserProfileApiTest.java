package com.vmarket.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.vmarket.user.config.InternalApiProperties;
import com.vmarket.user.entity.UserProfile;
import com.vmarket.user.repository.UserProfileRepository;

@SpringBootTest
@AutoConfigureMockMvc
class InternalUserProfileApiTest {

	private static final String KEY = "test-only-internal-api-key-0123456789-0123456789";

	@Autowired MockMvc mvc;
	@Autowired UserProfileRepository profiles;
	@Autowired InternalApiProperties properties;

	@BeforeEach
	void clean() {
		profiles.deleteAll();
	}

	private UserProfile profile(String userId, String fullName, String avatarUrl) {
		UserProfile profile = new UserProfile();
		profile.setUserId(userId);
		profile.setFullName(fullName);
		profile.setAvatarUrl(avatarUrl);
		return profiles.saveAndFlush(profile);
	}

	@Test
	void returnsOnlyDisplayFieldsAndIgnoresUnknownIdsAndDuplicates() throws Exception {
		profile("u1", "Nguyễn Văn An", "https://cdn.test/an.jpg");

		mvc.perform(post("/internal/users/profiles")
						.header(InternalApiProperties.HEADER, KEY)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"userIds":["u1","u1","missing"]}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].userId").value("u1"))
				.andExpect(jsonPath("$[0].fullName").value("Nguyễn Văn An"))
				.andExpect(jsonPath("$[0].avatarUrl").value("https://cdn.test/an.jpg"))
				.andExpect(jsonPath("$[0].phone").doesNotExist())
				.andExpect(jsonPath("$[0].dateOfBirth").doesNotExist());
	}

	@Test
	void rejectsMissingOrWrongKey() throws Exception {
		mvc.perform(post("/internal/users/profiles")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userIds\":[\"u1\"]}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		mvc.perform(post("/internal/users/profiles")
						.header(InternalApiProperties.HEADER, "wrong-key")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userIds\":[\"u1\"]}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsEmptyAndOversizedBatches() throws Exception {
		mvc.perform(post("/internal/users/profiles")
						.header(InternalApiProperties.HEADER, properties.getApiKey())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userIds\":[]}"))
				.andExpect(status().isBadRequest());

		StringBuilder ids = new StringBuilder();
		for (int i = 0; i < 101; i++) {
			if (i > 0) ids.append(',');
			ids.append("\"u").append(i).append("\"");
		}
		mvc.perform(post("/internal/users/profiles")
						.header(InternalApiProperties.HEADER, properties.getApiKey())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userIds\":[" + ids + "]}"))
				.andExpect(status().isBadRequest());
	}
}
