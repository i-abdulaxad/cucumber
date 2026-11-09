package org.scenarioservice.exception;

/**
 * A save on an archived feature (D11): 409.
 */
public class FeatureArchivedException extends ApiException {

    public FeatureArchivedException() {
        super(ErrorCode.FEATURE_ARCHIVED, "The feature is archived. Restore it first.");
    }
}
