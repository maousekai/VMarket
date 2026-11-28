package com.vmarket.delivery.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * Cau hinh test ghi de application.yml, nen kiem tra rieng gia tri mac dinh that ma `docker compose
 * up` dung (review PR 68 lan 3, muc 1.1).
 */
class DeliveryPropertiesTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private boolean valid(String url) {
        DeliveryProperties properties = new DeliveryProperties();
        properties.setInternalApiKey("k".repeat(40));
        properties.setWebSocketUrl(url);
        return validator.validate(properties).isEmpty();
    }

    @Test
    void acceptsRelativeAndAbsoluteTrackingUrls() {
        assertThat(valid("/api/delivery/ws")).isTrue();
        assertThat(valid("ws://localhost:8088/api/delivery/ws")).isTrue();
        assertThat(valid("wss://shop.example.com/api/delivery/ws")).isTrue();
        assertThat(valid("http://shop.example.com/api/delivery/ws")).isFalse();
        assertThat(valid("/api/delivery/ws?ticket=x")).isFalse();
        assertThat(valid("//evil.example.com/api/delivery/ws")).isFalse();
    }

    @Test
    void defaultApplicationConfigurationBindsAndValidates() throws Exception {
        var sources =
                new YamlPropertySourceLoader()
                        .load(
                                "main",
                                new FileSystemResource(
                                        ROOT.resolve("src/main/resources/application.yml")));
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addFirst);
        String url =
                Binder.get(environment)
                        .bind("app.delivery.web-socket-url", String.class)
                        .get();
        if (System.getenv("DELIVERY_WS_URL") == null) assertThat(url).isEqualTo("/api/delivery/ws");
        assertThat(valid(url)).as(url).isTrue();
    }

    @Test
    void dockerComposeDefaultIsValid() throws Exception {
        Path compose = ROOT.resolve("../../docker-compose.yml").normalize();
        Matcher m =
                Pattern.compile("DELIVERY_WS_URL:\\s*\\$\\{DELIVERY_WS_URL:-([^}]*)}")
                        .matcher(Files.readString(compose));
        assertThat(m.find()).isTrue();
        assertThat(valid(m.group(1))).as(m.group(1)).isTrue();
    }
}
