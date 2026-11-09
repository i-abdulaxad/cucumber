package org.scenarioservice.exception;

/**
 * No such version of the feature (D16): 404.
 */
public class VersionNotFoundException extends ApiException {

    public VersionNotFoundException() {
        super(ErrorCode.VERSION_NOT_FOUND, "The feature has no such version.");
    }
}
