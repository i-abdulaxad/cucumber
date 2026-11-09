package org.scenarioservice.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Contract {@code Rule}; {@code kind} is always {@code RULE}. No content in {@link #toString()}.
 */
public record RuleResponse(UUID id, String kind, String keyword, String name, String description, int line,
                           List<String> tags, List<ScenarioResponse> children) implements FeatureChildResponse {

    public static final String KIND = "RULE";

    @Override
    public String toString() {
        return "RuleResponse[id=" + id + "]";
    }
}
