package org.reportservice.exception;

/** The upload has no scenario element (D9, AC-2.8). */
public class UploadEmptyException extends ApiException {

    public UploadEmptyException() {
        super(ErrorCode.UPLOAD_EMPTY, "The upload has no scenario.");
    }
}
