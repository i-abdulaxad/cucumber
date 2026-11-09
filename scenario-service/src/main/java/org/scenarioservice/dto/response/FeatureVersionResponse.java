package org.scenarioservice.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * Contract {@code FeatureVersion}: one version with its text. No content in {@link #toString()}.
 */
public record FeatureVersionResponse(int version, String message, UUID createdBy, Instant createdAt, String source) {

    @Override
    public String toString() {
        return "FeatureVersionResponse[version=" + version + "]";
    }
}
