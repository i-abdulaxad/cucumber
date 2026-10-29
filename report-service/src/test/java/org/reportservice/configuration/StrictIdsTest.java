package org.reportservice.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * AC-6.5 (standards §3, contract C1): path ids are lower-case canonical UUIDs, matched exactly as sent; anything else
 * is 400 MALFORMED_REQUEST, before the access check.
 */
@ReportServiceIntegrationTest
class StrictIdsTest {

    private static final UUID RUN = UUID.fromString("9e1c3a5b-7d9f-4b1d-8f3a-5c7e9a1b3d50");

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetTheStub() {
        stub.reset();
    }

    @ParameterizedTest(name = "AC-6.5 GET {0}: 400 MALFORMED_REQUEST, no C5 call")
    @ValueSource(strings = {"{checkout}%20/runs", "{runs}/{run id in upper case}", "{runs}/123",
            "{checkout in upper case}/runs", "{runs}/%09{run id}"})
    @Tag("AC-6.5")
    void onlyLowerCaseCanonicalIdsAreAccepted(String row) throws Exception {
        String runs = "/api/projects/" + CHECKOUT + "/probe";
        String path = switch (row) {
            case "{checkout}%20/runs" -> "/api/projects/" + CHECKOUT + "%20/probe";
            case "{runs}/{run id in upper case}" -> runs + "/" + RUN.toString().toUpperCase(Locale.ROOT);
            case "{runs}/123" -> runs + "/123";
            case "{checkout in upper case}/runs" ->
                    "/api/projects/" + CHECKOUT.toString().toUpperCase(Locale.ROOT) + "/probe";
            default -> runs + "/%09" + RUN;
        };

        MvcResult result = mockMvc.perform(get(URI.create(path)).header(HttpHeaders.AUTHORIZATION, DAN.bearer()))
                .andReturn();

        assertProblem(result, 400, "MALFORMED_REQUEST");
        assertThat(stub.received()).isEmpty();
    }

    @ParameterizedTest(name = "AC-6.5 the lower-case id {0} is accepted")
    @ValueSource(strings = {"9e1c3a5b-7d9f-4b1d-8f3a-5c7e9a1b3d50"})
    @Tag("AC-6.5")
    void aLowerCaseCanonicalIdIsAccepted(String runId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/" + CHECKOUT + "/probe/" + runId)
                .header(HttpHeaders.AUTHORIZATION, DAN.bearer())).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }
}
