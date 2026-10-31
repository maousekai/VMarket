package com.vmarket.auth.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.vmarket.auth.config.DevAdminProperties;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Tạo tài khoản ADMIN mẫu cho dev/e2e bằng CODE, chỉ khi profile {@code dev} đang bật.
 *
 * <p>Thay cho migration {@code R__seed_dev_admin.sql}: migration đã chạy trên DB dev
 * sẽ còn trong {@code flyway_schema_history}, và khi deploy cùng DB đó bằng profile
 * prod thì Flyway báo "applied migration not resolved locally" rồi service không khởi
 * động. Code seeder không để lại dấu vết trong lịch sử Flyway; production không có
 * bean này nên không bao giờ tạo tài khoản.
 *
 * <p>Mật khẩu lấy từ {@code DEV_ADMIN_PASSWORD}. Để trống thì sinh mật khẩu ngẫu
 * nhiên và ghi ra log — không còn credential cố định trong repo. Seeder idempotent:
 * email hoặc username đã tồn tại (kể cả tạo tay) thì giữ nguyên, không nâng quyền.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevAdminSeeder implements ApplicationRunner {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRepository userRepository;
	private final RoleRepository roleRepository;
	private final UserRoleRepository userRoleRepository;
	private final PasswordEncoder passwordEncoder;
	private final DevAdminProperties properties;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		String email = properties.getEmail().trim().toLowerCase(Locale.ROOT);
		String username = properties.getUsername().trim();

		Optional<User> existing = userRepository.findByEmail(email);
		if (existing.isEmpty()) {
			existing = userRepository.findByUsername(username);
		}
		if (existing.isPresent()) {
			log.info("Tài khoản admin dev {} / {} đã tồn tại — giữ nguyên, không ghi đè",
					email, username);
			return;
		}

		Role adminRole = roleRepository.findByName(RoleName.ADMIN)
				.orElseThrow(() -> new IllegalStateException(
						"Vai trò ADMIN chưa được seed (migration V1)"));

		String configuredPassword = properties.getPassword();
		boolean generated = configuredPassword == null || configuredPassword.isBlank();
		String password = generated ? generatePassword() : configuredPassword;

		User user = new User();
		user.setEmail(email);
		user.setUsername(username);
		user.setPasswordHash(passwordEncoder.encode(password));
		user.setEmailVerified(true);
		userRepository.saveAndFlush(user);

		userRoleRepository.saveAndFlush(new UserRole(user.getId(), adminRole.getId()));

		if (generated) {
			log.warn("Đã tạo tài khoản ADMIN dev {} ({}). Mật khẩu ngẫu nhiên: {}",
					email, username, password);
		} else {
			log.info("Đã tạo tài khoản ADMIN dev {} ({}) từ DEV_ADMIN_PASSWORD", email, username);
		}
	}

	private static String generatePassword() {
		byte[] bytes = new byte[18];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
