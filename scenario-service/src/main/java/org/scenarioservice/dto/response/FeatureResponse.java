package org.scenarioservice.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contract {@code Feature}: the current text and its structure. No content in {@link #toString()} (P16).
 */
public record FeatureResponse(UUID id, UUID projectId, String path, String name, String description, String language,
                              int currentVersion, String source, List<String> tags,
                              List<FeatureChildResponse> children, UUID createdBy, Instant createdAt, UUID updatedBy,
                              Instant updatedAt, Instant archivedAt) {

    @Override
    public String toString() {
        return "FeatureResponse[id=" + id + ", currentVersion=" + currentVersion + "]";
    }
}
