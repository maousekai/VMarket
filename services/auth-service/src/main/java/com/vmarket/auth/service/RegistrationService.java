package com.vmarket.auth.service;

import java.util.List;
import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.auth.dto.AccountStatus;
import com.vmarket.auth.dto.RegisterRequest;
import com.vmarket.auth.dto.RegisterResponse;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FR-AUTH-01 — Đăng ký tài khoản.
 *
 * <p>Kiểm tra trùng email/username, hash mật khẩu bằng BCrypt, tạo user với vai
 * trò mặc định {@link RoleName#BUYER}. User mới ở trạng thái {@code PENDING}
 * ({@code email_verified = false}); gửi email kích hoạt là PBL6-45.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

	private final UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final UserRoleRepository userRoleRepository;
	private final PasswordEncoder passwordEncoder;

	private static final RoleName DEFAULT_ROLE = RoleName.BUYER;

	@Transactional
	public RegisterResponse register(RegisterRequest request) {
		String email = request.email().trim().toLowerCase(Locale.ROOT);
		String username = request.username();

		if (userRepository.existsByEmail(email)) {
			throw ApiException.conflict("EMAIL_ALREADY_EXISTS", "Email đã được sử dụng");
		}
		if (userRepository.existsByUsername(username)) {
			throw ApiException.conflict("USERNAME_ALREADY_EXISTS", "Username đã được sử dụng");
		}

		Role defaultRole = roleRepository.findByName(DEFAULT_ROLE)
				.orElseThrow(() -> new IllegalStateException(
						"Vai trò mặc định " + DEFAULT_ROLE + " chưa được seed (migration V1)"));

		User user = new User();
		user.setEmail(email);
		user.setUsername(username);
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		user.setEmailVerified(false);

		try {
			userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException ex) {
			// Race: 2 request cùng email/username cùng qua bước kiểm tra ở trên.
			// KHÔNG truy vấn lại DB ở đây — trên PostgreSQL transaction đã abort.
			throw duplicateConflict(ex);
		}

		userRoleRepository.save(new UserRole(user.getId(), defaultRole.getId()));

		log.info("Đăng ký user mới id={} username={}", user.getId(), username);
		return new RegisterResponse(
				user.getId(),
				user.getEmail(),
				user.getUsername(),
				AccountStatus.of(user.isEmailVerified()),
				List.of(defaultRole.getName().name()),
				user.getCreatedAt());
	}

	/** Suy ra trường bị trùng từ tên constraint trong exception (không chạm DB). */
	private ApiException duplicateConflict(DataIntegrityViolationException ex) {
		String constraint = constraintName(ex);
		if (constraint != null) {
			String c = constraint.toLowerCase(Locale.ROOT);
			if (c.contains("email")) {
				return ApiException.conflict("EMAIL_ALREADY_EXISTS", "Email đã được sử dụng");
			}
			if (c.contains("username")) {
				return ApiException.conflict("USERNAME_ALREADY_EXISTS", "Username đã được sử dụng");
			}
		}
		log.warn("Vi phạm ràng buộc khi đăng ký, không rõ trường (constraint={})", constraint, ex);
		return ApiException.conflict("REGISTRATION_CONFLICT", "Email hoặc username đã được sử dụng");
	}

	private static String constraintName(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof ConstraintViolationException cve) {
				return cve.getConstraintName();
			}
		}
		return null;
	}
}
