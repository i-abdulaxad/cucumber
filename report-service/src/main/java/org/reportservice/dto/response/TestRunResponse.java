package org.reportservice.dto.response;

import org.reportservice.enums.TestRunSource;
import org.reportservice.enums.TestRunStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A run with its summary (contract C1 {@code TestRun}). {@link #toString()} shows only ids and states, so framework
 * logging of the response shows no name, path or query value (D17).
 */
public record TestRunResponse(UUID id, UUID projectId, String name, TestRunSource source, TestRunStatus status,
                              EnvironmentResponse environment, BuildResponse build, String tagExpression,
                              UUID createdBy, Instant startedAt, Instant finishedAt, Instant createdAt,
                              Instant updatedAt, RunSummaryResponse summary) {

    @Override
    public String toString() {
        return "TestRunResponse[id=" + id + ", source=" + source + ", status=" + status + "]";
    }
}
