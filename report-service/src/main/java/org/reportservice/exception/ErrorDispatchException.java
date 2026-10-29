package org.reportservice.exception;

/**
 * What reached the container's error dispatch (platform-hardening D3): the status the container set, the path of the
 * original request as sent, and the exception that escaped, if any. {@link ApiExceptionHandler} renders it with the
 * status table; it carries no message of its own, so nothing from the request or the failure can reach the body.
 */
public class ErrorDispatchException extends RuntimeException {

    private final int status;
    private final String originalPath;

    public ErrorDispatchException(int status, String originalPath, Throwable escaped) {
        super(null, escaped, false, false);
        this.status = status;
        this.originalPath = originalPath;
    }

    public int status() {
        return status;
    }

    /** The original request's path as sent (raw), or {@code null} when the container didn't record one. */
    public String originalPath() {
        return originalPath;
    }
}
