package org.reportservice.exception;

/**
 * The CI upload has no usable upload token (D4, D5): missing, another scheme, unknown, malformed, revoked, expired,
 * or a user's JWT. Every case is the same 401, so nothing about tokens can be probed.
 */
public class UploadTokenInvalidException extends ApiException {

    public UploadTokenInvalidException() {
        super(ErrorCode.UPLOAD_TOKEN_INVALID, "A valid upload token is required.");
    }
}
