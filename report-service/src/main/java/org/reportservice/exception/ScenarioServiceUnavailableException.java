package org.reportservice.exception;

/**
 * scenario-service couldn't be read (D6, AC-3.7): planning fails closed with 503.
 */
public class ScenarioServiceUnavailableException extends ApiException {

    public ScenarioServiceUnavailableException() {
        super(ErrorCode.SCENARIO_SERVICE_UNAVAILABLE, "The features could not be read. Try again later.");
    }
}
