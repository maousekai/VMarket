package com.vmarket.auth;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import com.vmarket.auth.config.InternalApiProperties;
import com.vmarket.auth.entity.*;
import com.vmarket.auth.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class ShipperAccountApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired UserRoleRepository userRoles;
    @Autowired InternalApiProperties keys;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private User admin;
    private final String key="shipper-test-"+UUID.randomUUID();
    @BeforeEach void seed() {
        for (RoleName name : java.util.List.of(RoleName.ADMIN, RoleName.SHIPPER)) {
            if (roles.findByName(name).isEmpty()) {
                Role role = new Role(); role.setName(name); roles.saveAndFlush(role);
            }
        }
        // Existing Auth tests use Hibernate create-drop; provision schema is exercised separately by Flyway checks.
        jdbc.execute("create table if not exists shipper_provisions(provision_key varchar(64) primary key, user_id varchar(26) not null unique references users(id))");
        admin=new User(); admin.setEmail(UUID.randomUUID()+"@example.com"); admin.setUsername("admin-"+UUID.randomUUID().toString().substring(0,8)); admin.setEmailVerified(true);
        users.saveAndFlush(admin);
        userRoles.saveAndFlush(new UserRole(admin.getId(),roles.findByName(RoleName.ADMIN).orElseThrow().getId()));
    }
    private String request() { return "{\"email\":\"ship-"+key+"@example.com\",\"username\":\"ship-"+key.substring(key.length()-8)+"\",\"password\":\"Abcd1234@\"}"; }
    @Test void internalKeyAndLiveAdminRequiredForProvisioning() throws Exception {
        mvc.perform(post("/internal/shippers").header("X-Actor-Id",admin.getId()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/shippers").header("X-Internal-Api-Key",keys.getApiKey()).header("X-Actor-Id","unknown").header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from shipper_provisions where provision_key=?",Integer.class,key)).isZero();
    }
    @Test void shipperRolePendingEmailIdempotencyAndSuspension() throws Exception {
        var result=mvc.perform(post("/internal/shippers").header("X-Internal-Api-Key",keys.getApiKey()).header("X-Actor-Id",admin.getId()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isOk()).andReturn();
        String id=json.readTree(result.getResponse().getContentAsString()).path("userId").asString();
        assertThat(userRoles.existsByUserIdAndRoleId(id,roles.findByName(RoleName.SHIPPER).orElseThrow().getId())).isTrue();
        assertThat(users.findById(id).orElseThrow().isEmailVerified()).isFalse();
        mvc.perform(post("/internal/shippers").header("X-Internal-Api-Key",keys.getApiKey()).header("X-Actor-Id",admin.getId()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(id));
        mvc.perform(get("/internal/shippers/{id}/status",id).header("X-Internal-Api-Key",keys.getApiKey())).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        var user=users.findById(id).orElseThrow(); user.setEmailVerified(true); users.saveAndFlush(user);
        mvc.perform(get("/internal/shippers/{id}/status",id).header("X-Internal-Api-Key",keys.getApiKey())).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        user.setSuspendedAt(java.time.Instant.now()); users.saveAndFlush(user);
        mvc.perform(get("/internal/shippers/{id}/status",id).header("X-Internal-Api-Key",keys.getApiKey())).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }
}
