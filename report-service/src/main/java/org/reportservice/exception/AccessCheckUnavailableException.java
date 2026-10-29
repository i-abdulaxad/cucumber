package org.reportservice.exception;

/** The access check couldn't be completed (D5): the service fails closed with 503. */
public class AccessCheckUnavailableException extends ApiException {

    public AccessCheckUnavailableException() {
        super(ErrorCode.ACCESS_CHECK_UNAVAILABLE, "Access to the project could not be checked. Try again later.");
    }
}
