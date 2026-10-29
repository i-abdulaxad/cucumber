package org.reportservice.exception;

/** The access check rejected the caller's token, for example for a suspended user (contract C4, AC-6.3): 401. */
public class UnauthenticatedException extends ApiException {

    public UnauthenticatedException() {
        super(ErrorCode.UNAUTHENTICATED, ApiExceptionHandler.UNAUTHENTICATED_DETAIL);
    }
}
