package org.reportservice.dto.response;

import org.reportservice.enums.ExecutionStatus;

import java.util.Map;

/**
 * One feature of a run summary (D12): counts for all 7 statuses.
 */
public record FeatureSummaryResponse(String featurePath, String featureName, int total,
                                     Map<ExecutionStatus, Integer> counts) {
}
