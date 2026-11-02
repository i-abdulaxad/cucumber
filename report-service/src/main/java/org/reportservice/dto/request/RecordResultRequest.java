package org.reportservice.dto.request;

/**
 * {@code PUT .../results/{resultId}} (D8): {@code status} is PASSED, FAILED, SKIPPED or UNKNOWN; {@code comment} is
 * optional (null clears it). The recording service checks both (400 VALIDATION_FAILED); a JSON number where a string
 * belongs is 400 MALFORMED_REQUEST (strict JSON). {@link #toString()} never shows the comment (D17).
 */
public record RecordResultRequest(String status, String comment) {

    @Override
    public String toString() {
        return "RecordResultRequest[status=" + status + "]";
    }
}
