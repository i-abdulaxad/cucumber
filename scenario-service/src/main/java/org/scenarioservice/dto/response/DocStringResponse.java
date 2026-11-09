package org.scenarioservice.dto.response;

/**
 * Contract {@code DocString}. No content in {@link #toString()}.
 */
public record DocStringResponse(String content, String mediaType) {

    @Override
    public String toString() {
        return "DocStringResponse[]";
    }
}
