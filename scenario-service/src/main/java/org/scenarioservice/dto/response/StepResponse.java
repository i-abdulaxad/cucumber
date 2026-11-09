package org.scenarioservice.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Contract {@code Step}: at most one of {@code dataTable} and {@code docString}, the other {@code null}.
 */
public record StepResponse(UUID id, String keyword, String keywordType, String text, int line,
                           List<List<String>> dataTable, DocStringResponse docString) {

    @Override
    public String toString() {
        return "StepResponse[id=" + id + "]";
    }
}
