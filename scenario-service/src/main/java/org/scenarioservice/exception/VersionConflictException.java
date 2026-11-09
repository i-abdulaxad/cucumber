package org.scenarioservice.exception;

/**
 * A save based on an outdated version (D9): 409 with the Problem member {@code currentVersion}.
 */
public class VersionConflictException extends ApiException {

    private final int currentVersion;

    public VersionConflictException(int currentVersion) {
        super(ErrorCode.VERSION_CONFLICT, "The feature has changed since this version. Reload it and save again.");
        this.currentVersion = currentVersion;
    }

    public int currentVersion() {
        return currentVersion;
    }
}
