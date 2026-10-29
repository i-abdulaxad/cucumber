package org.reportservice.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.RequestLog;
import org.reportservice.support.TestSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ReportsFixture.BILLING;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * AC-6.3 (T3's test first, D5, contract C4): the access check fails closed and is never cached. Dan reads Checkout
 * through the project-scoped probe path while the auth-service stub misbehaves; every failure is one WARN line naming
 * the failure type, with no token, URL or port.
 */
@ReportServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AccessCheckFailureMatrixTest {

    private static final String RUNS = "/api/projects/" + CHECKOUT + "/probe";

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    @AfterEach
    void resetTheStub() {
        stub.reset();
    }

    @ParameterizedTest(name = "AC-6.3 the C5 stub {0}: 503 ACCESS_CHECK_UNAVAILABLE")
    @ValueSource(strings = {"is not listening", "answers 500", "answers after 3 s", "answers role \"OWNER\"",
            "answers another projectId", "answers a body that isn't JSON", "answers without archived"})
    @Tag("AC-6.3")
    void anIncompleteAccessCheckFailsClosed(String behaviour, CapturedOutput output) throws Exception {
        // Given
        switch (behaviour) {
            case "is not listening" -> stub.stop();
            case "answers 500" -> stub.answer(500, "{}");
            case "answers after 3 s" -> stub.delay(Duration.ofSeconds(3));
            case "answers role \"OWNER\"" -> stub.answer(200, AuthServiceStub.accessBody(CHECKOUT, "OWNER"));
            case "answers another projectId" -> stub.answer(200, AuthServiceStub.accessBody(BILLING, "READER"));
            case "answers a body that isn't JSON" -> stub.answer(200, "<html>ok</html>");
            default -> stub.answer(200, AuthServiceStub.accessBody(CHECKOUT, "READER")
                    .replace(",\"archived\":false", ""));
        }
        int logStart = output.getAll().length();
        long start = System.nanoTime();

        // When
        MvcResult result = mockMvc.perform(get(RUNS).header(HttpHeaders.AUTHORIZATION, DAN.bearer())).andReturn();

        // Then
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertProblem(result, 503, "ACCESS_CHECK_UNAVAILABLE");
        RequestLog log = RequestLog.since(output, logStart);
        assertThat(log.entries()).filteredOn(line -> line.contains(" WARN ")
                        && line.contains("Access check unavailable")).singleElement();
        assertThat(log.count("ERROR")).isZero();
        assertThat(log.hasStackTraceLine()).isFalse();
        assertThat(log.lines()).noneMatch(line -> line.contains(DAN.token())
                || line.contains(String.valueOf(TestSettings.AUTH_SERVICE_PORT)) || line.contains("/internal/"));
    }

    @Test
    @Tag("AC-6.3")
    @DisplayName("AC-6.3 C5 answers 401: 401 UNAUTHENTICATED with WWW-Authenticate: Bearer")
    void aRejectedTokenIsUnauthenticated() throws Exception {
        stub.rejectToken(DAN.id());

        MvcResult result = mockMvc.perform(get(RUNS).header(HttpHeaders.AUTHORIZATION, DAN.bearer())).andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    @Test
    @Tag("AC-6.3")
    @DisplayName("AC-6.3 C5 answers 403: 403 FORBIDDEN")
    void aRefusedAccessIsForbidden() throws Exception {
        stub.role(DAN.id(), CHECKOUT, null);

        MvcResult result = mockMvc.perform(get(RUNS).header(HttpHeaders.AUTHORIZATION, DAN.bearer())).andReturn();

        assertProblem(result, 403, "FORBIDDEN");
    }

    @Test
    @Tag("AC-6.3")
    @DisplayName("AC-6.3 C5 answered READER, then 403 on the next request: 403 (never cached)")
    void answersAreNeverCached() throws Exception {
        MvcResult first = mockMvc.perform(get(RUNS).header(HttpHeaders.AUTHORIZATION, DAN.bearer())).andReturn();
        stub.role(DAN.id(), CHECKOUT, null);

        MvcResult second = mockMvc.perform(get(RUNS).header(HttpHeaders.AUTHORIZATION, DAN.bearer())).andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertProblem(second, 403, "FORBIDDEN");
        assertThat(stub.received()).hasSize(2);
    }
}
