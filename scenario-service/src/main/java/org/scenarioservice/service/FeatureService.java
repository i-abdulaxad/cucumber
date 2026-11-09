package org.scenarioservice.service;

import org.scenarioservice.dto.request.CreateFeatureRequest;
import org.scenarioservice.dto.request.SaveFeatureRequest;
import org.scenarioservice.dto.response.FeatureResponse;
import org.scenarioservice.dto.response.FeatureSummaryResponse;
import org.scenarioservice.dto.response.FeatureVersionResponse;
import org.scenarioservice.dto.response.FeatureVersionSummaryResponse;

import java.util.List;
import java.util.UUID;

/**
 * Feature use cases (US-1 to US-3). Each starts with the access check (D5), before any transaction (P13).
 */
public interface FeatureService {

    /**
     * The project's active or archived features, ordered by path ignoring case, then id (A5); with a tag, only those
     * whose current version uses it. {@code tagValues} are the raw values of the {@code tag} parameter (D19 #5).
     */
    List<FeatureSummaryResponse> list(UUID projectId, boolean archived, List<String> tagValues);

    FeatureResponse get(UUID projectId, UUID featureId);

    FeatureResponse create(UUID projectId, UUID userId, CreateFeatureRequest request);

    FeatureResponse save(UUID projectId, UUID featureId, UUID userId, SaveFeatureRequest request);

    /**
     * Archives the feature (D11); archiving an archived feature changes nothing.
     */
    FeatureResponse archive(UUID projectId, UUID featureId, UUID userId);

    /**
     * Restores the feature (D11) unless an active feature now has its path; restoring an active one changes nothing.
     */
    FeatureResponse restore(UUID projectId, UUID featureId, UUID userId);

    /**
     * The history, newest first (D9).
     */
    List<FeatureVersionSummaryResponse> versions(UUID projectId, UUID featureId);

    FeatureVersionResponse version(UUID projectId, UUID featureId, int version);
}
