package org.scenarioservice.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Contract {@code Scenario}: a Background, Scenario or Scenario Outline. No content in {@link #toString()}.
 */
public record ScenarioResponse(UUID id, String kind, String keyword, String name, String description, int line,
                               List<String> tags, List<StepResponse> steps, List<ExamplesResponse> examples)
        implements FeatureChildResponse {

    @Override
    public String toString() {
        return "ScenarioResponse[id=" + id + ", kind=" + kind + "]";
    }
}
