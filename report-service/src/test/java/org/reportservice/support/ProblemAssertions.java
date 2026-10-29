package org.reportservice.support;

import com.jayway.jsonpath.JsonPath;
import org.springframework.test.web.servlet.MvcResult;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Assertions on the Problem format (D13, contract C3): {@code type}, {@code title}, {@code status} and so on. */
public final class ProblemAssertions {

    private ProblemAssertions() {
    }

    /**
     * The response is a Problem with this status and code: {@code type} {@code about:blank}, a {@code title}, the
     * {@code status}, a non-empty {@code detail}, {@code instance} equal to the request path, and no internals.
     */
    public static Map<String, Object> assertProblem(MvcResult result, int status, String code) {
        String body = body(result);
        assertThat(result.getResponse().getStatus()).as("status of " + body).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        Map<String, Object> problem = JsonPath.read(body, "$");
        assertThat(problem).containsEntry("type", "about:blank").containsEntry("status", status)
                .containsEntry("code", code).containsEntry("instance", result.getRequest().getRequestURI());
        assertThat((String) problem.get("title")).isNotBlank();
        assertThat((String) problem.get("detail")).isNotBlank();
        assertThat(body).doesNotContainIgnoringCase("exception").doesNotContain("org.", "java.", "\tat ")
                .doesNotContainIgnoringCase("select ").doesNotContainIgnoringCase("insert ")
                .doesNotContain("localhost", "127.0.0.1");
        return problem;
    }

    public static String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
