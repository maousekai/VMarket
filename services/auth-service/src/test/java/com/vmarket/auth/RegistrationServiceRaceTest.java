package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.vmarket.auth.dto.RegisterRequest;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;
import com.vmarket.auth.service.RegistrationService;

/**
 * Kiểm tra nhánh xử lý va chạm (2 request cùng email/username qua được pre-check,
 * DB ném vi phạm ràng buộc UNIQUE). Nhánh này không đi qua test tích hợp trên H2
 * vì tên constraint H2 khác PostgreSQL — nên test bằng mock thuần, không cần DB.
 */
class RegistrationServiceRaceTest {

	private final UserRepository userRepository = mock(UserRepository.class);
	private final RoleRepository roleRepository = mock(RoleRepository.class);
	private final UserRoleRepository userRoleRepository = mock(UserRoleRepository.class);
	private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

	private final RegistrationService service = new RegistrationService(
			userRepository, roleRepository, userRoleRepository, passwordEncoder);

	private static final RegisterRequest REQUEST =
			new RegisterRequest("an@example.com", "an.nguyen", "Abcd1234@");

	private void arrangePreChecksPass() {
		when(userRepository.existsByEmail(any())).thenReturn(false);
		when(userRepository.existsByUsername(any())).thenReturn(false);
		Role buyer = new Role();
		buyer.setName(RoleName.BUYER);
		when(roleRepository.findByName(RoleName.BUYER)).thenReturn(Optional.of(buyer));
		when(passwordEncoder.encode(any())).thenReturn("$2a$hash");
	}

	private void arrangeSaveFailsWithConstraint(String constraintName) {
		ConstraintViolationException cve = mock(ConstraintViolationException.class);
		when(cve.getConstraintName()).thenReturn(constraintName);
		when(userRepository.saveAndFlush(any(User.class)))
				.thenThrow(new DataIntegrityViolationException("unique violation", cve));
	}

	@Test
	void race_emailConstraint_maps_to_EMAIL_ALREADY_EXISTS() {
		arrangePreChecksPass();
		arrangeSaveFailsWithConstraint("users_email_key");

		assertThatThrownBy(() -> service.register(REQUEST))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo("EMAIL_ALREADY_EXISTS"));
	}

	@Test
	void race_usernameConstraint_maps_to_USERNAME_ALREADY_EXISTS() {
		arrangePreChecksPass();
		arrangeSaveFailsWithConstraint("users_username_key");

		assertThatThrownBy(() -> service.register(REQUEST))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo("USERNAME_ALREADY_EXISTS"));
	}

	@Test
	void race_unknownConstraint_falls_back_to_REGISTRATION_CONFLICT() {
		arrangePreChecksPass();
		arrangeSaveFailsWithConstraint("some_other_fk");

		assertThatThrownBy(() -> service.register(REQUEST))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo("REGISTRATION_CONFLICT"));
	}
}
