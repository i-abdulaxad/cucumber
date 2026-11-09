package org.scenarioservice.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resets scenario-service's data in the shared test database (D15 item 15): every row is deleted, children first.
 * No schema object is created or dropped. A test component in {@code support}, found by the application's component
 * scan in every test context, like {@link AdjustableClockConfiguration}.
 */
@Component
public class FeatureData {

    private final JdbcTemplate jdbc;

    public FeatureData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void reset() {
        jdbc.batchUpdate("DELETE FROM tag_assignments", "DELETE FROM example_rows", "DELETE FROM examples",
                "DELETE FROM steps", "DELETE FROM scenarios", "DELETE FROM rules", "DELETE FROM feature_versions",
                "DELETE FROM tags", "DELETE FROM features");
    }

    public long count(String table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    /**
     * "Nothing is stored": the number of features, versions and tags.
     */
    public String stored() {
        return count("features") + " features, " + count("feature_versions") + " versions, " + count("tags")
                + " tags";
    }
}
