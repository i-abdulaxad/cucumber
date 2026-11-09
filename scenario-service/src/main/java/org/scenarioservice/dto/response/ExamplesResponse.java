package org.scenarioservice.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Contract {@code Examples}; {@code header} and {@code rows} are {@code []} without a table.
 */
public record ExamplesResponse(UUID id, String keyword, String name, String description, int line, List<String> tags,
                               List<String> header, List<ExampleRowResponse> rows) {

    @Override
    public String toString() {
        return "ExamplesResponse[id=" + id + "]";
    }
}
