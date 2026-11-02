package org.reportservice.exception;

import org.reportservice.ingest.JsonProblem;

import java.util.List;

/** The uploaded Cucumber JSON breaks D9's read subset (AC-2.8): 422 with 1 to 20 {@code {pointer, message}}. */
public class CucumberJsonInvalidException extends ApiException {

    private final transient List<JsonProblem> problems;

    public CucumberJsonInvalidException(List<JsonProblem> problems) {
        super(ErrorCode.CUCUMBER_JSON_INVALID, "The Cucumber JSON is not valid.");
        this.problems = List.copyOf(problems);
    }

    public List<JsonProblem> problems() {
        return problems;
    }
}
