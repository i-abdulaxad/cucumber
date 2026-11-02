package org.reportservice.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.CiSample;
import org.reportservice.support.ReportData;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.ReportsFixture;
import org.reportservice.support.Uploads;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * BD-15, BD-16 and BD-18 on the CI upload (D9, D13): UTF-8 JSON only and never decoded (415), a declared length over
 * the limit is 413 before the body is read, a valid {@code X-Request-Id} reaches C7, and C7's {@code projectId} is
 * compared as a UUID.
 */
@ReportServiceIntegrationTest
class UploadHeadersTest {

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String t1;

    @BeforeEach
    void resetData() {
        stub.reset();
        ReportsFixture.reset();
        ReportData.reset(jdbc);
        t1 = Uploads.activeToken(jdbc, CHECKOUT);
    }

    @ParameterizedTest(name = "BD-15 Content-Type {0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "application/json                       | 201",
            "application/json;charset=utf-8         | 201",
            "application/json; charset=UTF-8        | 201",
            "application/json;charset=\"utf-8\"       | 201",
            "application/json;charset=ISO-8859-1    | 415",
            "application/json; charset=utf-16       | 415",
            "text/plain                             | 415"})
    @Tag("AC-2.9")
    void onlyUtf8JsonIsAccepted(String contentType, int status) throws Exception {
        MvcResult result = mockMvc.perform(upload().header(HttpHeaders.CONTENT_TYPE, contentType.trim())).andReturn();

        assertStatus(result, status);
    }

    @ParameterizedTest(name = "BD-15 Content-Encoding {0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "identity        | 201",
            "IDENTITY        | 201",
            "gzip            | 415",
            "deflate         | 415",
            "br              | 415",
            "gzip, identity  | 415"})
    @Tag("AC-2.9")
    void noContentCodingButIdentity(String encoding, int status) throws Exception {
        MvcResult result = mockMvc.perform(upload().header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.CONTENT_ENCODING, encoding.trim())).andReturn();

        assertStatus(result, status);
    }

    @Test
    @Tag("AC-2.9")
    @DisplayName("BD-15 A declared Content-Length of 20,971,521 is 413 before the (small) body is read")
    void aDeclaredLengthOverTheLimitIsRejectedUnread() throws Exception {
        MvcResult result = mockMvc.perform(upload().header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.CONTENT_LENGTH, "20971521")).andReturn();

        assertProblem(result, 413, "UPLOAD_TOO_LARGE");
        assertThat(Uploads.runs(jdbc)).isZero();
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @Tag("AC-2.9")
    @DisplayName("D13: a text/plain upload without a token is 401 first, then the media type")
    void authenticationComesFirst() throws Exception {
        MvcResult result = mockMvc.perform(post(Uploads.PATH).header(HttpHeaders.CONTENT_TYPE, "text/plain")
                .content("x")).andReturn();

        assertProblem(result, 401, "UPLOAD_TOKEN_INVALID");
    }

    @ParameterizedTest(name = "BD-16 X-Request-Id {0} reaches C7 as {1}")
    @CsvSource({"up-77, up-77", "'bad id!', a generated UUID"})
    @Tag("AC-2.7")
    void aValidRequestIdIsForwardedToC7(String requestId, String forwarded) throws Exception {
        MvcResult result = mockMvc.perform(upload().header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("X-Request-Id", requestId)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(stub.received()).singleElement().satisfies(call -> {
            if (forwarded.equals("up-77")) {
                assertThat(call.requestId()).isEqualTo("up-77");
            } else {
                assertThat(UUID.fromString(call.requestId())).isNotNull();
            }
        });
    }

    @Test
    @Tag("AC-2.7")
    @DisplayName("BD-18 C7 answering the token's project id in upper case is the same project: 201")
    void anUpperCaseProjectIdFromC7IsTheSameProject() throws Exception {
        stub.answer(200, AuthServiceStub.statusBody(CHECKOUT)
                .replace(CHECKOUT.toString(), CHECKOUT.toString().toUpperCase(Locale.ROOT)));

        MvcResult result = mockMvc.perform(upload().header(HttpHeaders.CONTENT_TYPE, "application/json")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private MockHttpServletRequestBuilder upload() {
        return post(Uploads.PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + t1)
                .content(CiSample.JSON.getBytes(StandardCharsets.UTF_8));
    }

    private void assertStatus(MvcResult result, int status) throws Exception {
        if (status == 201) {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
        } else {
            assertProblem(result, status, "UNSUPPORTED_MEDIA_TYPE");
            assertThat(Uploads.runs(jdbc)).isZero();
            assertThat(stub.received()).isEmpty();
        }
    }
}
