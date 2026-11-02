package org.reportservice.dto.request;

import java.io.InputStream;

/**
 * The body of a CI upload as received (D9, BD-15): its declared content type, content encoding and length (each null
 * when the header is absent), and the unread stream. The upload service checks the headers, then reads the stream
 * bounded. {@link #toString()} shows no content.
 */
public record UploadBody(String contentType, String contentEncoding, Long contentLength, InputStream stream) {

    @Override
    public String toString() {
        return "UploadBody[contentLength=" + contentLength + "]";
    }
}
