package org.scenarioservice.gherkin;

/**
 * A child of the Feature: a Rule, or a Background, Scenario or Scenario Outline.
 */
public sealed interface ParsedChild permits ParsedRule, ParsedScenario {

    int line();
}
