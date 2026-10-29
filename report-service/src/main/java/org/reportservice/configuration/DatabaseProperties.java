package org.reportservice.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The database settings as bound (D14, D15): a plain record, not {@code @Validated}, so that Boot's bind-failure report
 * can never print a value. {@link DatabaseSettingsCheck} checks them after binding.
 */
@ConfigurationProperties(prefix = "spring.datasource")
public record DatabaseProperties(String url, String username, String password) {

    @Override
    public String toString() {
        return "DatabaseProperties[url=<redacted>, username=<redacted>, password=<redacted>]";
    }
}
