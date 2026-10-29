package org.reportservice.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RE3, D15 (read as a resource; the dev profile is never activated): {@code application-dev.yaml} holds exactly the
 * {@code .env} import, the dev URL and username, and the dev SQL level; no password, secret or key, and never
 * {@code show-sql}.
 */
class DevProfileFileTest {

    @Test
    @DisplayName("RE3: the dev file holds exactly the .env import, the URL, the username and org.hibernate.SQL DEBUG")
    void theDevFileHoldsExactlyTheApprovedSettings() throws Exception {
        assertThat(devSettings()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "spring.config.import", "optional:file:.env[.properties],optional:file:../.env[.properties]",
                "spring.datasource.url", "jdbc:postgresql://localhost:5432/report_service",
                "spring.datasource.username", "report_service",
                "logging.level.org.hibernate.SQL", "DEBUG"));
    }

    @Test
    @DisplayName("RE3: the dev file has no password, secret or key and doesn't turn show-sql on")
    void theDevFileHoldsNoSecret() throws Exception {
        assertThat(devSettings().keySet()).noneMatch(key -> key.toLowerCase(Locale.ROOT).contains("password"))
                .noneMatch(key -> key.toLowerCase(Locale.ROOT).contains("secret"))
                .noneMatch(key -> key.toLowerCase(Locale.ROOT).contains("key"));
        assertThat(String.valueOf(devSettings().get("spring.jpa.show-sql"))).isNotEqualToIgnoringCase("true");
    }

    private static Map<String, String> devSettings() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application-dev.yaml", new ClassPathResource("application-dev.yaml"));
        Map<String, String> settings = new LinkedHashMap<>();
        for (PropertySource<?> source : sources) {
            EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
            Arrays.stream(enumerable.getPropertyNames())
                    .forEach(name -> settings.put(name, String.valueOf(enumerable.getProperty(name))));
        }
        return settings;
    }
}
