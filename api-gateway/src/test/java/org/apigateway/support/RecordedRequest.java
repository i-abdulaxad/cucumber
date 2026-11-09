package org.apigateway.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A request as received by a {@link StubServer}. Header names are case-insensitive.
 */
public record RecordedRequest(String method, String path, String query, Map<String, List<String>> headers,
                              String body) {

    public RecordedRequest {
        Map<String, List<String>> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> caseInsensitive.put(name, List.copyOf(values)));
        headers = caseInsensitive;
    }

    public List<String> headerValues(String name) {
        return headers.getOrDefault(name, List.of());
    }

    public Optional<String> header(String name) {
        return headerValues(name).stream().findFirst();
    }

    public boolean hasHeader(String name) {
        return headers.containsKey(name);
    }
}
