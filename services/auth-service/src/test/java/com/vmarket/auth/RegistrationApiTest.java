package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RegistrationApiTest {

	@Autowired MockMvc mockMvc;
	@Autowired RoleRepository roleRepository;
	@Autowired UserRepository userRepository;
	@Autowired UserRoleRepository userRoleRepository;
	@Autowired PasswordEncoder passwordEncoder;

	@BeforeEach
	void seedBuyerRole() {
		if (roleRepository.findByName(RoleName.BUYER).isEmpty()) {
			Role buyer = new Role();
			buyer.setName(RoleName.BUYER);
			roleRepository.save(buyer);
		}
	}

	private static final String VALID_BODY = """
			{"email":"An.Nguyen@Example.com","username":"an.nguyen","password":"Abcd1234@"}
			""";

	@Test
	void register_success_returns201_pending_buyer_hashedPassword() throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.email").value("an.nguyen@example.com")) // chuẩn hoá lowercase
				.andExpect(jsonPath("$.username").value("an.nguyen"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.roles[0]").value("BUYER"));

		User saved = userRepository.findByEmail("an.nguyen@example.com").orElseThrow();
		assertThat(saved.getPasswordHash()).isNotEqualTo("Abcd1234@");
		assertThat(passwordEncoder.matches("Abcd1234@", saved.getPasswordHash())).isTrue();
		assertThat(saved.isEmailVerified()).isFalse();
		assertThat(userRoleRepository.findByUserId(saved.getId())).hasSize(1);
	}

	@Test
	void register_duplicateEmail_returns409() throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isCreated());

		String other = """
				{"email":"an.nguyen@example.com","username":"khac.nguyen","password":"Abcd1234@"}
				""";
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(other))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));
	}

	@Test
	void register_duplicateUsername_returns409() throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isCreated());

		String other = """
				{"email":"khac@example.com","username":"an.nguyen","password":"Abcd1234@"}
				""";
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(other))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("USERNAME_ALREADY_EXISTS"));
	}

	@Test
	void register_weakPassword_returns400_validationError() throws Exception {
		String body = """
				{"email":"weak@example.com","username":"weak.user","password":"abcdefgh"}
				""";
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.error.details[?(@.field == 'password')]").exists());
	}

	@Test
	void register_invalidUsername_returns400() throws Exception {
		String body = """
				{"email":"bad@example.com","username":"bad user!","password":"Abcd1234@"}
				""";
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void register_malformedJson_returns400_notServerError() throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{ not json "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
	}
}
