package org.scenarioservice.dto.response;

import java.util.List;

/**
 * Contract {@code TestCaseStep}: placeholders replaced. No content in {@link #toString()}.
 */
public record TestCaseStepResponse(String keyword, String text, List<List<String>> dataTable,
                                   DocStringResponse docString) {

    @Override
    public String toString() {
        return "TestCaseStepResponse[]";
    }
}
