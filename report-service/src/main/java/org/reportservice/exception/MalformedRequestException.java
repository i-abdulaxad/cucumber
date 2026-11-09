package org.reportservice.exception;

/**
 * The request body isn't JSON (D9, AC-2.8).
 */
public class MalformedRequestException extends ApiException {

    public MalformedRequestException() {
        super(ErrorCode.MALFORMED_REQUEST, "The request could not be read.");
    }
}
