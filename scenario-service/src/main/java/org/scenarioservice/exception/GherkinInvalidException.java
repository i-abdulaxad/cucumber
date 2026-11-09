package org.scenarioservice.exception;

import java.util.List;

/**
 * The text isn't valid Gherkin (D8): 422 with the Problem member {@code parseErrors}. The errors quote the text, so
 * they are user content: they stay out of the message and are never logged (D17).
 */
public class GherkinInvalidException extends ApiException {

    private final transient List<ParseErrorEntry> parseErrors;

    public GherkinInvalidException(List<ParseErrorEntry> parseErrors) {
        super(ErrorCode.GHERKIN_INVALID, "The text is not valid Gherkin.");
        this.parseErrors = List.copyOf(parseErrors);
    }

    public List<ParseErrorEntry> parseErrors() {
        return parseErrors;
    }

    /**
     * One {@code parseErrors} entry: 1-based line and column, and the parser's message.
     */
    public record ParseErrorEntry(int line, int column, String message) {

        @Override
        public String toString() {
            return "ParseErrorEntry[line=" + line + ", column=" + column + "]";
        }
    }
}
