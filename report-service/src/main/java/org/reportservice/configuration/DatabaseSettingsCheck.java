package org.reportservice.configuration;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Stops start-up when a database setting is missing (D15, database pattern item 4 as proven by auth-service): a value
 * is rejected when it's null, blank, or exactly one unresolved {@code ${NAME}} (an environment variable that isn't
 * set). Every rejected setting is named in one failure, by its variable, and no value is ever part of the message.
 * The {@code DataSource} depends on this check ({@link DataSourceDependsOnDatabaseProperties}), so no connection is
 * attempted first.
 */
@Component
public class DatabaseSettingsCheck {

    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\$\\{[^${}]+}");

    public DatabaseSettingsCheck(DatabaseProperties settings) {
        List<String> missing = new ArrayList<>();
        check(settings.url(), "spring.datasource.url must be set through the REPORT_DB_URL environment variable",
                missing);
        check(settings.username(),
                "spring.datasource.username must be set through the REPORT_DB_USERNAME environment variable",
                missing);
        check(settings.password(),
                "spring.datasource.password must be set through the REPORT_DB_PASSWORD environment variable",
                missing);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(String.join("; ", missing));
        }
    }

    private static void check(String value, String message, List<String> missing) {
        if (value == null || value.isBlank() || UNRESOLVED_PLACEHOLDER.matcher(value).matches()) {
            missing.add(message);
        }
    }
}
