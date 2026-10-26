package com.vmarket.auth.service;

import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.vmarket.auth.dto.RegisterRequest;
import com.vmarket.auth.entity.*;
import com.vmarket.auth.exception.ApiException;
import com.vmarket.auth.repository.*;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ShipperAccountService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final AccountAdminService admin;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;

    @Transactional
    public Map<String, String> create(String actor, String key, RegisterRequest request) {
        // Revalidate the administrator against Auth's database, not a stale JWT role.
        admin.get(actor, actor);
        var previous = jdbc.queryForList("select user_id from shipper_provisions where provision_key = ?", String.class, key);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String username = request.username().trim();
        if (!previous.isEmpty()) {
            User existing = users.findById(previous.get(0)).orElseThrow();
            if (!existing.getEmail().equals(email) || !existing.getUsername().equals(username)) {
                throw ApiException.conflict("PROVISION_KEY_REUSED", "Khoá tạo tài khoản đã được dùng cho người khác");
            }
            return Map.of("userId", existing.getId());
        }
        if (users.existsByEmail(email) || users.existsByUsername(username)) {
            throw ApiException.conflict("ACCOUNT_EXISTS", "Email hoặc username đã được sử dụng");
        }
        var role = roles.findByName(RoleName.SHIPPER).orElseThrow();
        User user = new User();
        user.setEmail(email);
        user.setUsername(username);
        user.setPasswordHash(passwords.encode(request.password()));
        // Activation uses the existing email OTP flow. Admin cannot assert ownership of email.
        user.setEmailVerified(false);
        try {
            users.saveAndFlush(user);
            userRoles.saveAndFlush(new UserRole(user.getId(), role.getId()));
            jdbc.update("insert into shipper_provisions(provision_key, user_id) values (?, ?)", key, user.getId());
        } catch (org.springframework.dao.DataIntegrityViolationException concurrent) {
            // PostgreSQL has aborted this transaction; caller retries the same key in a new one.
            throw ApiException.conflict("PROVISION_CONFLICT", "Tài khoản hoặc khoá đang được tạo, hãy thử lại cùng khoá");
        }
        return Map.of("userId", user.getId());
    }

    @Transactional(readOnly = true)
    public Map<String, Boolean> status(String id) {
        var user = users.findById(id).orElseThrow(() -> new ApiException("SHIPPER_NOT_FOUND", HttpStatus.NOT_FOUND, "Không có shipper"));
        boolean active = user.isEmailVerified() && !user.isSuspended()
                && users.countActiveWithRole(id, RoleName.SHIPPER) > 0;
        return Map.of("active", active);
    }
}
