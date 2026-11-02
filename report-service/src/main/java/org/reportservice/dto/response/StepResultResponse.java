package org.reportservice.dto.response;

import org.reportservice.enums.ExecutionStatus;
import org.reportservice.enums.StepResultKind;

import java.util.List;

/** A step or hook of a result (contract C1 {@code StepResult}). {@link #toString()} shows no text (D17). */
public record StepResultResponse(int position, StepResultKind kind, String keyword, String text,
                                 List<List<String>> dataTable, String docString, ExecutionStatus status,
                                 Long durationMs, String errorMessage) {

    @Override
    public String toString() {
        return "StepResultResponse[position=" + position + ", kind=" + kind + ", status=" + status + "]";
    }
}
