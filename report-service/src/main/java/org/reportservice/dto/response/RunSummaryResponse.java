package org.reportservice.dto.response;

import org.reportservice.enums.ExecutionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** A run's summary, computed on read (D12). {@code passRate} has one decimal, or is null when nothing executed. */
public record RunSummaryResponse(int total, Map<ExecutionStatus, Integer> counts, int executed, BigDecimal passRate,
                                 Long durationMs, List<FeatureSummaryResponse> byFeature) {
}
