package com.vmarket.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * PBL6-59: MỌI profile (application*.yml của src/main) để Flyway quản schema — Hibernate chỉ
 * được {@code validate}, không {@code update}/{@code create}, và Flyway không bị tắt.
 */
class SchemaManagementConfigTest {

    @Test
    void everyProfileValidatesAndKeepsFlywayOn() throws IOException {
        List<Path> profiles;
        try (Stream<Path> files = Files.list(Path.of("src/main/resources"))) {
            profiles = files.filter(p -> p.getFileName().toString().matches("application.*\\.ya?ml"))
                    .toList();
        }
        assertThat(profiles).isNotEmpty();
        for (Path profile : profiles) {
            for (String line : Files.readAllLines(profile)) {
                String setting = line.replaceAll("#.*", "").trim();
                if (setting.startsWith("ddl-auto:")) {
                    assertThat(setting).as(profile + ": " + line).isEqualTo("ddl-auto: validate");
                }
            }
            assertThat(Files.readString(profile))
                    .as(profile + " không được tắt Flyway")
                    .doesNotContainPattern("(?m)^\\s*flyway:\\s*\\n\\s*enabled:\\s*false");
        }
    }
}
