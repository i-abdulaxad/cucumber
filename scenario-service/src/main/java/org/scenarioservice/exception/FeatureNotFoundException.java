package org.scenarioservice.exception;

/**
 * No such feature in this project, also for a feature of another project (D16, AC-2.4): 404.
 */
public class FeatureNotFoundException extends ApiException {

    public FeatureNotFoundException() {
        super(ErrorCode.FEATURE_NOT_FOUND, "The feature does not exist in this project.");
    }
}
