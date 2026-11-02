package org.reportservice.exception;

/**
 * The CI upload's body isn't plain UTF-8 JSON (D9, BD-15): a charset other than UTF-8, or a {@code Content-Encoding}
 * other than none or {@code identity}; the body is never decoded.
 */
public class UnsupportedMediaTypeException extends ApiException {

    public UnsupportedMediaTypeException() {
        super(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "The upload must be application/json in UTF-8, without encoding.");
    }
}
