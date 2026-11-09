package org.scenarioservice.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Contract {@code ExampleRow}. No content in {@link #toString()}.
 */
public record ExampleRowResponse(UUID id, int line, List<String> cells) {

    @Override
    public String toString() {
        return "ExampleRowResponse[id=" + id + "]";
    }
}
