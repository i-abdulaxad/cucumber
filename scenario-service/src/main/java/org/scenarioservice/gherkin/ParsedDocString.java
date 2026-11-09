package org.scenarioservice.gherkin;

/**
 * A doc string: its content, and its media type ({@code null} when none is written). No content in toString.
 */
public record ParsedDocString(String content, String mediaType) {

    @Override
    public String toString() {
        return "ParsedDocString[length=" + content.length() + "]";
    }
}
