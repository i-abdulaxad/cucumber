package org.reportservice.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CI uploads in tests (US-2): {@code POST /api/ci/runs} with a token and a body, and the spec's tokens T1–T3.
 */
public final class Uploads {

    public static final String PATH = "/api/ci/runs";

    private Uploads() {
    }

    /**
     * Uploads {@code body} as JSON with {@code Authorization: Bearer <token>} and the raw query (or "" for none).
     */
    public static MvcResult upload(MockMvc mockMvc, String token, String query, String body) throws Exception {
        // A URI keeps the query exactly as written (a String would be encoded once more as a URI template)
        MockHttpServletRequestBuilder request = post(URI.create(PATH + (query.isEmpty() ? "" : "?" + query)))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    /**
     * {@code {T1}}: an active Checkout token created by Cai; returns its text.
     */
    public static String activeToken(JdbcTemplate jdbc, UUID projectId) {
        String token = ReportData.randomToken();
        ReportData.token(jdbc, projectId, "T1", token, ReportsFixture.CAI.id(), Instant.now(), null, null);
        return token;
    }

    public static int runs(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM test_runs", Integer.class);
    }
}
