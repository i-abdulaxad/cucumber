package org.reportservice.exception;

/**
 * A business or access error with a stable {@link ErrorCode} (D13). The detail is a fixed text that never contains
 * user content, a token or a value; {@link ApiExceptionHandler} turns it into a Problem.
 */
public abstract class ApiException extends RuntimeException {

    private final ErrorCode code;

    protected ApiException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public String detail() {
        return getMessage();
    }
}
