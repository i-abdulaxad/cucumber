package org.reportservice.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * D5 item 1 (T3's half of AC-2.4): {@code /api/ci/**} is its own chain. Without an upload token, and with a user's
 * JWT, every request there is 401 UPLOAD_TOKEN_INVALID with {@code WWW-Authenticate: Bearer}, and C5 is never called.
 * The upload token authenticator itself comes with the upload endpoint (T7).
 */
@ReportServiceIntegrationTest
class CiPathAuthenticationTest {

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetTheStub() {
        stub.reset();
    }

    @ParameterizedTest(name = "D5 {0} {1} with {2}: 401 UPLOAD_TOKEN_INVALID")
    @CsvSource({
            "POST, /api/ci/runs, (none)",
            "POST, /api/ci/runs, the JWT of Cai",
            "POST, /api/ci/runs, Basic",
            "GET, /api/ci/runs, the JWT of Cai",
            "POST, /api/ci/runs/x, (none)"})
    @Tag("AC-2.4")
    void theCiPathsAcceptNoUserCredential(String method, String path, String authorization) throws Exception {
        MockHttpServletRequestBuilder call = request(HttpMethod.valueOf(method), path)
                .contentType(MediaType.APPLICATION_JSON).content("[]");
        switch (authorization) {
            case "the JWT of Cai" -> call.header(HttpHeaders.AUTHORIZATION, CAI.bearer());
            case "Basic" -> call.header(HttpHeaders.AUTHORIZATION, "Basic Y2k6c2VjcmV0");
            default -> {
            }
        }

        MvcResult result = mockMvc.perform(call).andReturn();

        assertProblem(result, 401, "UPLOAD_TOKEN_INVALID");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(stub.received()).isEmpty();
    }
}
