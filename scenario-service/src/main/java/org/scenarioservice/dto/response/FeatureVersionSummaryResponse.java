package org.scenarioservice.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Contract {@code FeatureVersionSummary}: one history entry. No content in {@link #toString()}.
 */
public record FeatureVersionSummaryResponse(int version, String message, UUID createdBy, Instant createdAt) {

    @Override
    public String toString() {
        return "FeatureVersionSummaryResponse[version=" + version + "]";
    }
}
