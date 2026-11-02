package org.reportservice.dto.request;

import java.util.List;

/**
 * {@code POST /api/projects/{p}/runs} (D10). The planning service checks every rule (400 VALIDATION_FAILED with one
 * entry per field, before the access check); a JSON number or boolean where a string belongs is 400 MALFORMED_REQUEST
 * (strict JSON). {@code featureIds} are kept as sent, so whitespace or upper case is a validation error, not a
 * binding one.
 */
public record PlanRunRequest(String name, List<String> featureIds, String tag, String environment, String build) {
}
