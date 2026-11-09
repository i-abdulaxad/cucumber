package org.reportservice.exception;

/**
 * No result with this id in this run (AC-4.7).
 */
public class ResultNotFoundException extends ApiException {

    public ResultNotFoundException() {
        super(ErrorCode.RESULT_NOT_FOUND, "The result does not exist in this run.");
    }
}
