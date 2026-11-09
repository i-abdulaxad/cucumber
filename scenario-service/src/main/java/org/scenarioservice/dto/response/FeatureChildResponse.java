package org.scenarioservice.dto.response;

/**
 * Contract {@code FeatureChild}: a Scenario (Background, Scenario, Outline) or a Rule, told apart by {@code kind}.
 */
public sealed interface FeatureChildResponse permits ScenarioResponse, RuleResponse {

    String kind();
}
