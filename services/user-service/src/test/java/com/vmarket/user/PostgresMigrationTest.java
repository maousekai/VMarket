package com.vmarket.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;

import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PBL6-59: service khởi động trên một PostgreSQL RỖNG chỉ bằng migration Flyway, Hibernate
 * chỉ {@code validate} (không tự sinh/sửa bảng). Mọi file migration trên classpath (kể cả
 * {@code db/vendor/postgresql}) phải được áp dụng, không còn migration nào treo.
 *
 * <p>Máy dev thiếu Docker có thể bỏ qua test. CI luôn phải khởi động PostgreSQL.
 */
@SpringBootTest
@EnabledIf("dockerTestsEnabled")
@Testcontainers
class PostgresMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static boolean dockerTestsEnabled() {
        return Boolean.parseBoolean(System.getenv("CI")) || DockerClientFactory.instance().isDockerAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;

    @Test
    void emptyDatabaseIsBuiltByMigrationsOnly() throws Exception {
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        var info = flyway.info();
        assertThat(info.pending()).isEmpty();
        assertThat(info.applied())
                .isNotEmpty()
                .allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));

        long scripts = 0;
        var resolver = new PathMatchingResourcePatternResolver();
        for (var location : flyway.getConfiguration().getLocations()) {
            String path = location.getPath().replace("{vendor}", "postgresql");
            // SQL và migration Java đều tính; bỏ lớp lồng ($).
            for (String pattern : new String[] {"/V*__*.sql", "/V*__*.class"}) {
                scripts += Arrays.stream(resolver.getResources("classpath*:" + path + pattern))
                        .filter(r -> !r.getFilename().contains("$"))
                        .count();
            }
        }
        long versioned = Arrays.stream(info.applied())
                .filter(m -> m.getVersion() != null && !m.getType().isSynthetic())
                .count();
        assertThat(versioned)
                .as("mọi migration V*__* trên classpath đều đã chạy trên PostgreSQL")
                .isEqualTo(scripts);
    }

    /** V3 (partial index, chỉ có trên PostgreSQL) thật sự chạy và chặn hai địa chỉ mặc định. */
    @Test
    void v3RejectsSecondDefaultAddressForSameUser() {
        assertThat(flyway.info().applied())
                .anySatisfy(m -> assertThat(m.getVersion().getVersion()).isEqualTo("3"));
        String insert = """
                insert into addresses (id, user_id, recipient_name, phone, province, ward,
                                       street_address, is_default)
                values (?, 'user-v3', 'A', '0900000000', 'DN', 'W', 'S', ?)
                """;
        jdbc.update(insert, "addr-1", true);
        jdbc.update(insert, "addr-2", false);
        jdbc.update(insert, "addr-3", false);
        assertThatThrownBy(() -> jdbc.update(insert, "addr-4", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_addresses_one_default_per_user");
    }
}
