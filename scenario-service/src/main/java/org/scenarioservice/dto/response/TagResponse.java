package org.scenarioservice.dto.response;

/**
 * Contract {@code Tag}: exactly these two members (AC-5.6). No content in {@link #toString()}.
 */
public record TagResponse(String name, long featureCount) {

    @Override
    public String toString() {
        return "TagResponse[featureCount=" + featureCount + "]";
    }
}
