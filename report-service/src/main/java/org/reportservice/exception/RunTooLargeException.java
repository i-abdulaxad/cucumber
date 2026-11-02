package org.reportservice.exception;

/** The selection has more than 2,000 test cases (D10, AC-3.5). */
public class RunTooLargeException extends ApiException {

    public RunTooLargeException() {
        super(ErrorCode.RUN_TOO_LARGE, "The run would have more than 2,000 test cases.");
    }
}
