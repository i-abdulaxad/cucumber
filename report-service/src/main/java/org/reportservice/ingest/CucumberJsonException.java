package org.reportservice.ingest;

import java.util.List;

/**
 * Why an upload body can't become test cases (D9): not JSON at all ({@link Reason#MALFORMED}), JSON that breaks the
 * read subset ({@link Reason#INVALID}, with 1 to 20 {@link JsonProblem}s in document order), no scenario element
 * ({@link Reason#EMPTY}), or more than 5,000 test cases ({@link Reason#TOO_MANY_TEST_CASES}). The message never
 * contains content.
 */
public class CucumberJsonException extends RuntimeException {

    /**
     * The four outcomes; the upload service maps each to its Problem.
     */
    public enum Reason {
        MALFORMED,
        INVALID,
        EMPTY,
        TOO_MANY_TEST_CASES
    }

    private final Reason reason;
    private final transient List<JsonProblem> problems;

    public CucumberJsonException(Reason reason, List<JsonProblem> problems) {
        super("Cucumber JSON rejected: " + reason, null, false, false);
        this.reason = reason;
        this.problems = List.copyOf(problems);
    }

    public Reason reason() {
        return reason;
    }

    public List<JsonProblem> problems() {
        return problems;
    }
}
