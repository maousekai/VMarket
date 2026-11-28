package com.vmarket.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;

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
        var loader = new YamlPropertySourceLoader();
        for (Path profile : profiles) {
            for (var document : loader.load(profile.toString(), new FileSystemResource(profile))) {
                var sources = new MutablePropertySources();
                sources.addLast(document);
                var config = new PropertySourcesPropertyResolver(sources);
                // Profile overrides may inherit ddl-auto from application.yml.
                if (config.containsProperty("spring.jpa.hibernate.ddl-auto")) {
                    assertThat(config.getProperty("spring.jpa.hibernate.ddl-auto"))
                            .as("%s ddl-auto", document.getName()).isEqualTo("validate");
                }
                assertThat(config.getProperty("spring.flyway.enabled", "true"))
                        .as("%s flyway.enabled", document.getName()).isEqualToIgnoringCase("true");
            }
        }
    }
}
