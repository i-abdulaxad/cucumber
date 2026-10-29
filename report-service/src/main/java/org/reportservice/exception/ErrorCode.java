package org.reportservice.exception;

import org.springframework.http.HttpStatus;

/**
 * The stable error codes of contract C1 (D13), each with its HTTP status. T3 holds the codes it raises and those
 * copied from scenario-service's advice; later tasks add theirs (constants only).
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    UPLOAD_TOKEN_INVALID(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    UPLOAD_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
    PROJECT_ARCHIVED(HttpStatus.CONFLICT),
    UPLOAD_TOKEN_LIMIT(HttpStatus.CONFLICT),
    CONFLICT(HttpStatus.CONFLICT),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    ACCESS_CHECK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
