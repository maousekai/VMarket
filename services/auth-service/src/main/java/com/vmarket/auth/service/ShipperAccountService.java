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
    private final PasswordResetTokenIssuer activationCodes;
    private final com.vmarket.auth.email.EmailSender emailSender;
    private final com.vmarket.auth.config.AuthPasswordResetProperties resetProps;

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
        // Email kich hoat: shipper dat mat khau rieng bang ma nay (= xac thuc email).
        sendActivation(user, java.time.Instant.now(), false);
        return Map.of("userId", user.getId());
    }

    /** Admin gui lai email kich hoat cho shipper chua xac thuc email. */
    @Transactional
    public Map<String, Object> resendActivation(String actor, String id) {
        admin.get(actor, actor);
        User user = users.findById(id)
                .orElseThrow(() -> new ApiException("SHIPPER_NOT_FOUND", HttpStatus.NOT_FOUND, "Không có shipper"));
        if (users.countActiveWithRole(id, RoleName.SHIPPER) == 0)
            throw new ApiException("SHIPPER_NOT_FOUND", HttpStatus.NOT_FOUND, "Không có shipper");
        if (user.isEmailVerified())
            throw ApiException.conflict("ALREADY_ACTIVATED", "Shipper đã kích hoạt tài khoản");
        sendActivation(user, java.time.Instant.now(), true);
        return Map.of("sent", true);
    }

    /**
     * Phat ma (dung chung co che ma dat lai mat khau, co gioi han tan suat) va gui email SAU khi
     * transaction commit: loi gui mail khong lam mat tai khoan vua tao.
     */
    private void sendActivation(User user, java.time.Instant now, boolean failWhenTooSoon) {
        var code = activationCodes.issue(user.getId(), now);
        if (code.isEmpty()) {
            if (failWhenTooSoon)
                throw new ApiException("ACTIVATION_TOO_SOON", HttpStatus.TOO_MANY_REQUESTS,
                        "Vừa gửi email kích hoạt, hãy thử lại sau ít phút");
            return;
        }
        var message = com.vmarket.auth.email.ShipperActivationEmailContent.build(
                user.getEmail(), code.get(), resetProps.getTtl());
        Runnable send = () -> {
            try {
                emailSender.send(message);
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(ShipperAccountService.class)
                        .warn("Không gửi được email kích hoạt shipper userId={}", user.getId());
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            send.run();
                        }
                    });
        } else send.run();
    }

    /**
     * Trang thai nhieu shipper trong MOT truy van (review PR 68: khong goi tung dong):
     * PENDING_ACTIVATION (chua xac thuc email), ACTIVE, SUSPENDED (bi khoa / mat vai tro), NOT_FOUND.
     */
    @Transactional(readOnly = true)
    public Map<String, String> statuses(java.util.Collection<String> ids) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        var found = new java.util.HashMap<String, User>();
        users.findAllById(ids).forEach(u -> found.put(u.getId(), u));
        for (String id : ids) {
            User user = found.get(id);
            if (user == null) result.put(id, "NOT_FOUND");
            else if (user.isSuspended() || users.countActiveWithRole(id, RoleName.SHIPPER) == 0)
                result.put(id, "SUSPENDED");
            else if (!user.isEmailVerified()) result.put(id, "PENDING_ACTIVATION");
            else result.put(id, "ACTIVE");
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Boolean> status(String id) {
        var user = users.findById(id).orElseThrow(() -> new ApiException("SHIPPER_NOT_FOUND", HttpStatus.NOT_FOUND, "Không có shipper"));
        boolean active = user.isEmailVerified() && !user.isSuspended()
                && users.countActiveWithRole(id, RoleName.SHIPPER) > 0;
        return Map.of("active", active);
    }
}
