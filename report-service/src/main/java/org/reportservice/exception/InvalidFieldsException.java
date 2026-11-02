package org.reportservice.exception;

import java.util.Map;

/**
 * Request fields (query parameters) that break rules a service checks (D9 query parameters, AC-2.10): 400
 * VALIDATION_FAILED with one {@code errors} entry per field. Reasons never contain the value.
 */
public class InvalidFieldsException extends ApiException {

    private final transient Map<String, String> fields;

    public InvalidFieldsException(Map<String, String> fields) {
        super(ErrorCode.VALIDATION_FAILED, "The request has invalid fields.");
        this.fields = Map.copyOf(fields);
    }

    /** Field name to reason. */
    public Map<String, String> fields() {
        return fields;
    }
}
