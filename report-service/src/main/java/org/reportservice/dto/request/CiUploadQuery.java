package org.reportservice.dto.request;

/**
 * The query parameters of {@code POST /api/ci/runs} (D9), as received; the upload service checks them after the body
 * size (D13). {@link #toString()} never shows a value (D17: query values are never logged).
 */
public record CiUploadQuery(String name, String environment, String build, String branch, String commit,
                            String ciUrl) {

    @Override
    public String toString() {
        return "CiUploadQuery[<redacted>]";
    }
}
