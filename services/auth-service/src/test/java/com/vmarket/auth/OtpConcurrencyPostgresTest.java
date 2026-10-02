package com.vmarket.auth;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link OtpRaceScenarios} trên PostgreSQL THẬT (Testcontainers, schema từ Flyway):
 * hành vi khoá ({@code SELECT ... FOR UPDATE}, {@code INSERT ... ON CONFLICT}) và
 * transaction bị abort sau vi phạm UNIQUE là của Postgres, không phải giả lập H2.
 * Máy không có Docker tự bỏ qua (giống {@link PostgresFlywayIntegrationTest}).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class OtpConcurrencyPostgresTest extends OtpRaceScenarios {

	@Container
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
					.withDatabaseName("auth_it")
					.withUsername("auth_it")
					.withPassword("auth_it");

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		// Ghi de driver H2 cua src/test/resources/application.yml (xem PostgresFlywayIntegrationTest).
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.flyway.enabled", () -> "true");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
	}
}
