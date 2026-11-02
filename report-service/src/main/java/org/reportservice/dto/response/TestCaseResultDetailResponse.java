package org.reportservice.dto.response;

import org.reportservice.enums.ExecutionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A result with its steps and hooks in execution order (contract C1 {@code TestCaseResultDetail}).
 * {@link #toString()} shows no name, tag, comment or step (D17).
 */
public record TestCaseResultDetailResponse(UUID id, UUID testRunId, int position, UUID featureId,
                                           Integer featureVersion, UUID scenarioId, UUID exampleRowId,
                                           String featurePath, String featureName, String name, List<String> tags,
                                           Integer line, ExecutionStatus status, int attempt, UUID executedBy,
                                           String comment, Instant finishedAt, Long durationMs, Instant createdAt,
                                           Instant updatedAt, List<StepResultResponse> steps) {

    @Override
    public String toString() {
        return "TestCaseResultDetailResponse[id=" + id + ", status=" + status + "]";
    }
}
