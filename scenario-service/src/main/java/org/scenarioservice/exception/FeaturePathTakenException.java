package org.scenarioservice.exception;

/**
 * Another active feature of the project has the path, ignoring case by ICU root lower case (D10): 409.
 */
public class FeaturePathTakenException extends ApiException {

    public FeaturePathTakenException() {
        super(ErrorCode.FEATURE_PATH_TAKEN, "Another active feature in this project already has this path.");
    }
}
