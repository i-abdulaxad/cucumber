package org.reportservice.exception;

/**
 * A request field breaks a rule that a service checks rather than the request record (for example "{@code expiresAt}
 * must be in the future", which needs the service clock): 400 VALIDATION_FAILED with one {@code errors} entry. The
 * reason never contains the value.
 */
public class InvalidFieldException extends ApiException {

    private final String field;
    private final String reason;

    public InvalidFieldException(String field, String reason) {
        super(ErrorCode.VALIDATION_FAILED, "The request has invalid fields.");
        this.field = field;
        this.reason = reason;
    }

    public String field() {
        return field;
    }

    public String reason() {
        return reason;
    }
}
