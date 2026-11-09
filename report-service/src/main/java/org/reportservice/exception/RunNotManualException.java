package org.reportservice.exception;

/**
 * An automated run can't be recorded, completed or cancelled by hand (D8, AC-4.6).
 */
public class RunNotManualException extends ApiException {

    public RunNotManualException() {
        super(ErrorCode.RUN_NOT_MANUAL, "Automated runs can't be changed by hand.");
    }
}
