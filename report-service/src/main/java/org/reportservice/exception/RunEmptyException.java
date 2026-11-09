package org.reportservice.exception;

/**
 * The selection leaves no test case (D10, AC-3.5).
 */
public class RunEmptyException extends ApiException {

    public RunEmptyException() {
        super(ErrorCode.RUN_EMPTY, "No test case matches the selection.");
    }
}
