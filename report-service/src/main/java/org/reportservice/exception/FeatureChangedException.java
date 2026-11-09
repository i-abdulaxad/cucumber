package org.reportservice.exception;

/**
 * A selected feature was saved while the run was planned (D6, AC-3.6); nothing is stored.
 */
public class FeatureChangedException extends ApiException {

    public FeatureChangedException() {
        super(ErrorCode.FEATURE_CHANGED, "A selected feature changed while the run was planned. Plan it again.");
    }
}
