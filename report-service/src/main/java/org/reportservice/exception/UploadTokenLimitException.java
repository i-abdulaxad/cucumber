package org.reportservice.exception;

/** The project already has 10 active upload tokens (D4, AC-1.5). */
public class UploadTokenLimitException extends ApiException {

    public UploadTokenLimitException() {
        super(ErrorCode.UPLOAD_TOKEN_LIMIT, "The project already has 10 active upload tokens. Revoke one first.");
    }
}
