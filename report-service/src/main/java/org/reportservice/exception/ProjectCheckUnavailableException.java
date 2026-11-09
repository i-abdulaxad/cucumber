package org.reportservice.exception;

/**
 * auth-service's project check (C7) couldn't be completed (D5, AC-2.7): the upload fails closed with 503.
 */
public class ProjectCheckUnavailableException extends ApiException {

    public ProjectCheckUnavailableException() {
        super(ErrorCode.PROJECT_CHECK_UNAVAILABLE, "The project could not be checked. Try again later.");
    }
}
