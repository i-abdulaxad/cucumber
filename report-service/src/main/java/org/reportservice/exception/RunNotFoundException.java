package org.reportservice.exception;

/**
 * No run with this id in this project (D13, AC-4.7): a run of another project is the same 404.
 */
public class RunNotFoundException extends ApiException {

    public RunNotFoundException() {
        super(ErrorCode.RUN_NOT_FOUND, "The run does not exist in this project.");
    }
}
