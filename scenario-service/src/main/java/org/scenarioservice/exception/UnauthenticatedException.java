package org.scenarioservice.exception;

/**
 * The access check rejected the caller's token, for example for a suspended user (D4, AC-7.4): 401.
 */
public class UnauthenticatedException extends ApiException {

    public UnauthenticatedException() {
        super(ErrorCode.UNAUTHENTICATED, ApiExceptionHandler.UNAUTHENTICATED_DETAIL);
    }
}
