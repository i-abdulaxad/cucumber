package org.scenarioservice.support;

import com.jayway.jsonpath.JsonPath;
import org.scenarioservice.support.ScenarioFixture.User;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Requests to the feature endpoints as the spec writes them ({@code {checkout-features}} and so on). Bodies are built
 * with {@link #json}, which escapes every non-ASCII character, so control characters and lone surrogates reach the
 * server exactly as the ACs send them (as JSON escapes).
 */
public final class FeatureCalls {

    private FeatureCalls() {
    }

    public static String features(UUID projectId) {
        return "/api/projects/" + projectId + "/features";
    }

    public static MvcResult create(MockMvc mockMvc, User user, UUID projectId, String path, String source)
            throws Exception {
        return perform(mockMvc, user, post(features(projectId)).contentType(MediaType.APPLICATION_JSON)
                .content(json(fields("path", path, "source", source))));
    }

    public static MvcResult create(MockMvc mockMvc, User user, UUID projectId, Map<String, Object> body)
            throws Exception {
        return perform(mockMvc, user, post(features(projectId)).contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    /**
     * Creates a feature and returns its id; fails unless the answer is 201.
     */
    public static UUID created(MockMvc mockMvc, User user, UUID projectId, String path, String source)
            throws Exception {
        MvcResult result = create(mockMvc, user, projectId, path, source);
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("create answered " + result.getResponse().getStatus());
        }
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    public static MvcResult save(MockMvc mockMvc, User user, UUID projectId, UUID featureId,
                                 Map<String, Object> body) throws Exception {
        return perform(mockMvc, user, put(features(projectId) + "/" + featureId)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    public static MvcResult view(MockMvc mockMvc, User user, UUID projectId, UUID featureId) throws Exception {
        return perform(mockMvc, user, get(features(projectId) + "/" + featureId));
    }

    public static MvcResult list(MockMvc mockMvc, User user, UUID projectId) throws Exception {
        return perform(mockMvc, user, get(features(projectId)));
    }

    public static MvcResult perform(MockMvc mockMvc, User user, MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
    }

    /**
     * An ordered map from alternating names and values.
     */
    public static Map<String, Object> fields(Object... namesAndValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            fields.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return fields;
    }

    /**
     * A JSON object of these fields: strings are escaped (every character outside printable ASCII as
     * {@code \}{@code uXXXX}), {@link Raw} values are written as they are, and {@code null} is JSON null.
     */
    public static String json(Map<String, Object> fields) {
        StringBuilder json = new StringBuilder("{");
        fields.forEach((name, value) -> {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append(string(name)).append(':');
            if (value == null) {
                json.append("null");
            } else if (value instanceof Raw raw) {
                json.append(raw.json());
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value);
            } else {
                json.append(string(value.toString()));
            }
        });
        return json.append('}').toString();
    }

    private static String string(String text) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                escaped.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7E) {
                escaped.append(String.format("\\u%04X", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.append('"').toString();
    }

    /**
     * A JSON value written as is, for example {@code 1.5} or {@code "1"}.
     */
    public record Raw(String json) {
    }
}
