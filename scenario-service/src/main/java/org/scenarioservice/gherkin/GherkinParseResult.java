package org.scenarioservice.gherkin;

import java.util.List;

/**
 * The outcome of parsing a text: valid Gherkin with a Feature, or the parse errors (D8).
 */
public sealed interface GherkinParseResult {

    record Valid(ParsedFeature feature) implements GherkinParseResult {
    }

    /**
     * At least one error, sorted by line, then column.
     */
    record Invalid(List<GherkinParseError> errors) implements GherkinParseResult {
    }
}
