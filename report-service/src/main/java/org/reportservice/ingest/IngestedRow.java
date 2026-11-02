package org.reportservice.ingest;

import org.reportservice.enums.ExecutionStatus;
import org.reportservice.enums.StepResultKind;

import java.util.List;

/**
 * One row of an uploaded test case (D9): a step, or a hook around the test case or a step. Hooks have no keyword or
 * text. {@code durationMs} is floor(ns / 1,000,000), null without a duration. {@link #toString()} shows no text.
 */
public record IngestedRow(StepResultKind kind, String keyword, String text, List<List<String>> dataTable,
                          String docString, ExecutionStatus status, Long durationMs, Long durationNanos,
                          String errorMessage) {

    @Override
    public String toString() {
        return "IngestedRow[kind=" + kind + ", status=" + status + "]";
    }
}
