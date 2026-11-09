package org.scenarioservice.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contract {@code FeatureSummary}: a listed feature, without text or structure (AC-2.2). No content in toString.
 */
public record FeatureSummaryResponse(UUID id, UUID projectId, String path, String name, String language,
                                     int currentVersion, List<String> tags, int testCaseCount, UUID createdBy,
                                     Instant createdAt, UUID updatedBy, Instant updatedAt, Instant archivedAt) {

    @Override
    public String toString() {
        return "FeatureSummaryResponse[id=" + id + ", currentVersion=" + currentVersion + "]";
    }
}
