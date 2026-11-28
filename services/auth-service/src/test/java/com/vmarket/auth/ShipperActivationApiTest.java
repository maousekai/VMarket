package com.vmarket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.vmarket.auth.config.InternalApiProperties;
import com.vmarket.auth.email.EmailMessage;
import com.vmarket.auth.email.EmailSender;
import com.vmarket.auth.entity.Role;
import com.vmarket.auth.entity.RoleName;
import com.vmarket.auth.entity.User;
import com.vmarket.auth.entity.UserRole;
import com.vmarket.auth.repository.PasswordResetTokenRepository;
import com.vmarket.auth.repository.RoleRepository;
import com.vmarket.auth.repository.UserRepository;
import com.vmarket.auth.repository.UserRoleRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

/**
 * Review PR 68 lan 2, muc 4.9 va muc 2: email kich hoat shipper, gui lai, trang thai theo lo,
 * danh sach admin. Khong @Transactional de email gui sau commit thuc su chay.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShipperActivationApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired UserRoleRepository userRoles;
    @Autowired PasswordResetTokenRepository resetTokens;
    @Autowired InternalApiProperties keys;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean EmailSender emailSender;

    private User admin;
    private final java.util.List<String> created = new java.util.ArrayList<>();

    /** Test khong chay trong transaction: tu don du lieu de khong lam ban test khac. */
    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        created.add(admin.getId());
        for (String id : created) {
            jdbc.update("delete from shipper_provisions where user_id=?", id);
            jdbc.update("delete from password_reset_token where user_id=?", id);
            jdbc.update("delete from user_roles where user_id=?", id);
            jdbc.update("delete from users where id=?", id);
        }
        created.clear();
    }

    @BeforeEach
    void seed() {
        for (RoleName name : java.util.List.of(RoleName.ADMIN, RoleName.SHIPPER)) {
            if (roles.findByName(name).isEmpty()) {
                Role role = new Role();
                role.setName(name);
                roles.saveAndFlush(role);
            }
        }
        jdbc.execute(
                "create table if not exists shipper_provisions(provision_key varchar(64) primary key,"
                        + " user_id varchar(26) not null unique references users(id))");
        admin = new User();
        admin.setEmail(UUID.randomUUID() + "@example.com");
        admin.setUsername("admin-" + UUID.randomUUID().toString().substring(0, 8));
        admin.setEmailVerified(true);
        users.saveAndFlush(admin);
        userRoles.saveAndFlush(
                new UserRole(admin.getId(), roles.findByName(RoleName.ADMIN).orElseThrow().getId()));
    }

    private String createShipper() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var result =
                mvc.perform(
                                post("/internal/shippers")
                                        .header("X-Internal-Api-Key", keys.getApiKey())
                                        .header("X-Actor-Id", admin.getId())
                                        .header("Idempotency-Key", "activation-" + suffix)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"email\":\"ship-" + suffix + "@example.com\","
                                                        + "\"username\":\"ship-" + suffix + "\","
                                                        + "\"password\":\"Abcd1234@\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).path("userId").asString();
        created.add(id);
        return id;
    }

    @Test
    void creatingAShipperSendsAnActivationEmailWithACode() throws Exception {
        String id = createShipper();
        var message = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(message.capture());
        assertThat(message.getValue().subject()).contains("Kích hoạt");
        assertThat(message.getValue().to()).isEqualTo(users.findById(id).orElseThrow().getEmail());
        assertThat(resetTokens.findFirstByUserIdOrderByCreatedAtDesc(id)).isPresent();
    }

    @Test
    void resendIsRateLimitedAndRefusedOnceActivated() throws Exception {
        String id = createShipper();
        clearInvocations(emailSender);
        // Vua gui luc tao: lan gui lai ngay lap tuc bi gioi han tan suat.
        mvc.perform(
                        post("/internal/shippers/{id}/activation", id)
                                .header("X-Internal-Api-Key", keys.getApiKey())
                                .header("X-Actor-Id", admin.getId()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("ACTIVATION_TOO_SOON"));
        verify(emailSender, never()).send(any());
        jdbc.update(
                "update password_reset_token set created_at=? where user_id=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)),
                id);
        mvc.perform(
                        post("/internal/shippers/{id}/activation", id)
                                .header("X-Internal-Api-Key", keys.getApiKey())
                                .header("X-Actor-Id", admin.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));
        verify(emailSender, times(1)).send(any());
        var user = users.findById(id).orElseThrow();
        user.setEmailVerified(true);
        users.saveAndFlush(user);
        mvc.perform(
                        post("/internal/shippers/{id}/activation", id)
                                .header("X-Internal-Api-Key", keys.getApiKey())
                                .header("X-Actor-Id", admin.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_ACTIVATED"));
    }

    @Test
    void batchStatusSeparatesPendingActiveSuspendedAndUnknown() throws Exception {
        String pending = createShipper();
        String active = createShipper();
        String suspended = createShipper();
        var a = users.findById(active).orElseThrow();
        a.setEmailVerified(true);
        users.saveAndFlush(a);
        var s = users.findById(suspended).orElseThrow();
        s.setEmailVerified(true);
        s.setSuspendedAt(java.time.Instant.now());
        users.saveAndFlush(s);
        mvc.perform(
                        post("/internal/shippers/status")
                                .header("X-Internal-Api-Key", keys.getApiKey())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"ids\":[\"" + pending + "\",\"" + active + "\",\""
                                                + suspended + "\",\"missing\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statuses." + pending).value("PENDING_ACTIVATION"))
                .andExpect(jsonPath("$.statuses." + active).value("ACTIVE"))
                .andExpect(jsonPath("$.statuses." + suspended).value("SUSPENDED"))
                .andExpect(jsonPath("$.statuses.missing").value("NOT_FOUND"));
        mvc.perform(
                        post("/internal/shippers/status")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ids\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminDirectoryListsActiveAdmins() throws Exception {
        mvc.perform(get("/internal/admins").header("X-Internal-Api-Key", keys.getApiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ids").isArray())
                .andExpect(
                        jsonPath("$.ids").value(org.hamcrest.Matchers.hasItem(admin.getId())));
    }
}
