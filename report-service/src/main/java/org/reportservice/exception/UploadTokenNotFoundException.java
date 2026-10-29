package org.reportservice.exception;

/** No upload token with this id in this project (D13, AC-1.8): a token of another project is the same 404. */
public class UploadTokenNotFoundException extends ApiException {

    public UploadTokenNotFoundException() {
        super(ErrorCode.UPLOAD_TOKEN_NOT_FOUND, "The upload token does not exist in this project.");
    }
}
