package org.reportservice.exception;

/** The upload body is larger than 20,971,520 bytes (D9, AC-2.9); reading stopped at the limit. */
public class UploadTooLargeException extends ApiException {

    public UploadTooLargeException() {
        super(ErrorCode.UPLOAD_TOO_LARGE, "The upload is larger than 20 MiB.");
    }
}
