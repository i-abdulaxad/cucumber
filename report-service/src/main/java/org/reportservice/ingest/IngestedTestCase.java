package org.reportservice.ingest;

import org.reportservice.enums.ExecutionStatus;

import java.util.List;

/**
 * One uploaded test case (D9): an unlinked snapshot of a {@code scenario} element, with the background steps before
 * its own, its status (the worst row) and its duration (floor of the summed row nanoseconds, or null). {@link
 * #toString()} shows no names, tags or texts.
 */
public record IngestedTestCase(String featurePath, String featureName, String name, List<String> tags, Integer line,
                               ExecutionStatus status, Long durationMs, List<IngestedRow> rows) {

    @Override
    public String toString() {
        return "IngestedTestCase[status=" + status + ", rows=" + rows.size() + "]";
    }
}
