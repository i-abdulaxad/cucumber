package org.reportservice.exception;

/** The run is completed or cancelled and the change isn't allowed (D8, AC-4.4, AC-4.5, AC-4.8). */
public class RunClosedException extends ApiException {

    public RunClosedException() {
        super(ErrorCode.RUN_CLOSED, "The run is closed.");
    }
}
