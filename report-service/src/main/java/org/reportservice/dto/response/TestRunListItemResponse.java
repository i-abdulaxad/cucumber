package org.reportservice.dto.response;

import org.reportservice.enums.ExecutionStatus;
import org.reportservice.enums.TestRunSource;
import org.reportservice.enums.TestRunStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** A run in the list (contract C1 {@code TestRunListItem}, D12): {@code total}, {@code counts}, {@code passRate}. */
public record TestRunListItemResponse(UUID id, UUID projectId, String name, TestRunSource source, TestRunStatus status,
                                      EnvironmentResponse environment, BuildResponse build, String tagExpression,
                                      UUID createdBy, Instant startedAt, Instant finishedAt, Instant createdAt,
                                      Instant updatedAt, int total, Map<ExecutionStatus, Integer> counts,
                                      BigDecimal passRate) {

    @Override
    public String toString() {
        return "TestRunListItemResponse[id=" + id + ", status=" + status + "]";
    }
}
