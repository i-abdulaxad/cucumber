package org.reportservice.exception;

/** No access to the project, a role that is too low, or an unknown project (D7): 403, the same for each. */
public class ForbiddenException extends ApiException {

    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN, ApiExceptionHandler.FORBIDDEN_DETAIL);
    }
}
