package com.example.authservice.support;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The dev profile's settings, read from {@code application-dev.yaml} as a resource (G13, E85). No test activates the
 * {@code dev} profile, because that profile imports the developer's gitignored {@code .env}; tests that need a dev
 * setting read it from the file here instead.
 */
public final class DevProfileFile {

    private static final String FILE = "application-dev.yaml";

    private DevProfileFile() {
    }

    /**
     * The property sources of {@code application-dev.yaml}; its {@code spring.config.import} is not processed.
     */
    public static List<PropertySource<?>> load() {
        try {
            return new YamlPropertySourceLoader().load(FILE, new ClassPathResource(FILE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The level that the dev profile sets for {@code logger}.
     */
    public static LogLevel level(String logger) {
        String key = "logging.level." + logger;
        Object level = load().stream().map(source -> source.getProperty(key)).filter(Objects::nonNull).findFirst()
                .orElseThrow(() -> new IllegalStateException(FILE + " sets no " + key));
        return LogLevel.valueOf(level.toString().trim().toUpperCase(Locale.ROOT));
    }
}
