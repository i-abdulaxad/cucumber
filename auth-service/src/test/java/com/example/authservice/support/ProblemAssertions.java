package com.example.authservice.support;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;

import java.io.UnsupportedEncodingException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks "a Problem S / C" from the spec's glossary: status S, {@code Content-Type: application/problem+json},
 * a Problem body with {@code type} about:blank, the reason phrase as {@code title}, {@code status} S, a non-empty
 * {@code detail}, {@code instance} (the path without query string) and {@code code} C, and no internals.
 */
public final class ProblemAssertions {

    private static final String[] INTERNAL_MARKERS = {
            "Exception", "at com.example", "at org.", "at java.", "SELECT ", "INSERT ", "jdbc:", "localhost:"
    };

    private ProblemAssertions() {
    }

    /**
     * Asserts the Problem and returns its parsed body for further checks.
     */
    public static DocumentContext assertProblem(MvcResult result, int status, String code, String instance)
            throws UnsupportedEncodingException {
        assertThat(result.getResponse().getStatus()).as("status").isEqualTo(status);
        assertThat(result.getResponse().getContentType()).as("Content-Type").isEqualTo("application/problem+json");
        String json = result.getResponse().getContentAsString();
        DocumentContext body = JsonPath.parse(json);
        assertThat(body.read("$.type", String.class)).isEqualTo("about:blank");
        assertThat(body.read("$.title", String.class)).isEqualTo(HttpStatus.valueOf(status).getReasonPhrase());
        assertThat(body.read("$.status", Integer.class)).isEqualTo(status);
        assertThat(body.read("$.detail", String.class)).isNotBlank();
        assertThat(body.read("$.instance", String.class)).isEqualTo(instance);
        assertThat(body.read("$.code", String.class)).isEqualTo(code);
        assertThat(json).doesNotContain(INTERNAL_MARKERS);
        return body;
    }
}
