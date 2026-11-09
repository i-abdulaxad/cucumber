package org.scenarioservice.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Contract {@code TestCase}: derived on read, never stored (D13). No content in {@link #toString()}.
 */
public record TestCaseResponse(UUID id, String type, UUID scenarioId, UUID exampleRowId, String name,
                               List<String> tags, int line, List<TestCaseStepResponse> steps) {

    @Override
    public String toString() {
        return "TestCaseResponse[id=" + id + ", type=" + type + "]";
    }
}
