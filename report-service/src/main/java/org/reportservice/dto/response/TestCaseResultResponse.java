package org.reportservice.dto.response;

import org.reportservice.enums.ExecutionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A test case result without its steps (contract C1 {@code TestCaseResult}). {@link #toString()} shows no name, tag or
 * comment (D17).
 */
public record TestCaseResultResponse(UUID id, UUID testRunId, int position, UUID featureId, Integer featureVersion,
                                     UUID scenarioId, UUID exampleRowId, String featurePath, String featureName,
                                     String name, List<String> tags, Integer line, ExecutionStatus status, int attempt,
                                     UUID executedBy, String comment, Instant finishedAt, Long durationMs,
                                     Instant createdAt, Instant updatedAt) {

    @Override
    public String toString() {
        return "TestCaseResultResponse[id=" + id + ", status=" + status + "]";
    }
}
