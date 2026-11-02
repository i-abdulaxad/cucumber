package org.reportservice.service.impl;

import org.reportservice.dto.response.FeatureSummaryResponse;
import org.reportservice.dto.response.RunSummaryResponse;
import org.reportservice.enums.ExecutionStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The run summary of D12 from a run's results: counts for all 7 statuses, {@code executed} (total minus UNKNOWN),
 * {@code passRate} (PASSED / executed × 100, half-up to one decimal, null when executed is 0), {@code durationMs} (sum
 * of the non-null durations, or null) and {@code byFeature} in order of first appearance.
 */
final class RunSummaries {

    private RunSummaries() {
    }

    /** One result, as the summary needs it. */
    record Item(String featurePath, String featureName, ExecutionStatus status, Long durationMs) {
    }

    static RunSummaryResponse summarize(List<Item> items) {
        Map<ExecutionStatus, Integer> counts = zeroCounts();
        Map<String, Feature> features = new LinkedHashMap<>();
        Long duration = null;
        for (Item item : items) {
            counts.merge(item.status(), 1, Integer::sum);
            features.computeIfAbsent(item.featurePath(), path -> new Feature(path, item.featureName())).add(item);
            if (item.durationMs() != null) {
                duration = (duration == null ? 0 : duration) + item.durationMs();
            }
        }
        int executed = items.size() - counts.get(ExecutionStatus.UNKNOWN);
        BigDecimal passRate = executed == 0 ? null : BigDecimal.valueOf(counts.get(ExecutionStatus.PASSED) * 100L)
                .divide(BigDecimal.valueOf(executed), 1, RoundingMode.HALF_UP);
        List<FeatureSummaryResponse> byFeature = new ArrayList<>();
        features.values().forEach(feature -> byFeature.add(feature.response()));
        return new RunSummaryResponse(items.size(), counts, executed, passRate, duration, byFeature);
    }

    /** All 7 statuses, in contract order, at 0. */
    static Map<ExecutionStatus, Integer> zeroCounts() {
        Map<ExecutionStatus, Integer> counts = new EnumMap<>(ExecutionStatus.class);
        for (ExecutionStatus status : ExecutionStatus.values()) {
            counts.put(status, 0);
        }
        return counts;
    }

    private static final class Feature {

        private final String path;
        private final String name;
        private final Map<ExecutionStatus, Integer> counts = zeroCounts();
        private int total;

        Feature(String path, String name) {
            this.path = path;
            this.name = name;
        }

        void add(Item item) {
            total++;
            counts.merge(item.status(), 1, Integer::sum);
        }

        FeatureSummaryResponse response() {
            return new FeatureSummaryResponse(path, name, total, counts);
        }
    }
}
