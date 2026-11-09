package org.scenarioservice.exception;

/**
 * No access to the project, a role that is too low, or an unknown project (D3, D4): 403, the same for each.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN, ApiExceptionHandler.FORBIDDEN_DETAIL);
    }
}
