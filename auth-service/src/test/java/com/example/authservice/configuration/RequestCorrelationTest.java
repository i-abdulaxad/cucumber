package com.example.authservice.configuration;

import com.example.authservice.aop.RequestLog;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AcmeFixture.Acme;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.loginBody;
import static com.example.authservice.support.ApiCalls.tokenOf;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-9 (D17 item 2): every log line of a request carries its request ID and, once authenticated, its user ID, and
 * both are cleared after the request. Levels are changed through {@link LoggingSystem} for one test only.
 * <p>
 * AC-9.7 and AC-9.8 send requests that never reach a service (the guard or the entry point answers first), so they
 * also put Spring Security's {@code FilterChainProxy} at DEBUG: its "Securing ..." lines give the request a log to
 * check. Those lines name the method and path, never a header.
 */
@AuthServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RequestCorrelationTest {

    private static final String ASPECT_LOGGER = "com.example.authservice.aop";
    private static final String FILTER_CHAIN_LOGGER = "org.springframework.security.web.FilterChainProxy";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern LOGGED_REQUEST_ID = Pattern.compile("requestId=(\\S+?)[\\s\\]]");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private LoggingSystem loggingSystem;

    @Autowired
    private DelegatingFilterProxyRegistrationBean securityFilterChainRegistration;

    private final List<String> changedLoggers = new ArrayList<>();

    private Acme acme;

    @BeforeEach
    void seedAcme() {
        acme = fixture.seed();
    }

    @AfterEach
    void restoreLevels() {
        changedLoggers.forEach(logger -> loggingSystem.setLogLevel(logger, null));
        changedLoggers.clear();
    }

    @Test
    @Tag("AC-9.6")
    @DisplayName("AC-9.6 Every log line of Ana's request carries its request ID and Ana's user id")
    void everyLineOfAnAuthenticatedRequestCarriesItsRequestIdAndUserId(CapturedOutput output) throws Exception {
        // Given
        String token = tokenOf(mockMvc, "ana@example.com");
        atDebug(ASPECT_LOGGER);
        int start = output.getAll().length();

        // When
        mockMvc.perform(get("/api/orgs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header(REQUEST_ID_HEADER, "req-96"))
                .andReturn();

        // Then
        List<String> lines = RequestLog.since(output, start).lines();
        assertThat(lines).as("the log of the request").isNotEmpty();
        assertThat(lines).allSatisfy(line -> assertThat(line).contains("req-96", acme.ana().getId().toString()));
    }

    @Test
    @Tag("AC-9.6")
    @DisplayName("AC-9.6 Every log line of an anonymous login carries its request ID and no user id")
    void everyLineOfAnAnonymousRequestCarriesItsRequestIdAndNoUserId(CapturedOutput output) throws Exception {
        // Given
        atDebug(ASPECT_LOGGER);
        int start = output.getAll().length();

        // When
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header(REQUEST_ID_HEADER, "req-96")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ana@example.com", AcmeFixture.PASSWORD)))
                .andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        List<String> lines = RequestLog.since(output, start).lines();
        assertThat(lines).as("the log of the request").isNotEmpty();
        assertThat(lines).allSatisfy(line -> assertThat(line).contains("req-96")
                .doesNotContain(acme.ana().getId().toString()));
    }

    static Stream<Arguments> missingOrInvalidRequestIds() {
        return Stream.of(
                Arguments.of("without X-Request-Id", null),
                Arguments.of("with X-Request-Id: bad id QA", "bad id QA"),
                Arguments.of("with an X-Request-Id of 129 \"a\"s", "a".repeat(129)));
    }

    @ParameterizedTest(name = "AC-9.7 a missing or invalid request ID is replaced in the logs: {0}")
    @MethodSource("missingOrInvalidRequestIds")
    @Tag("AC-9.7")
    void aMissingOrInvalidRequestIdIsReplacedInTheLogsAndNeverEchoed(String row, String header,
                                                                     CapturedOutput output) throws Exception {
        // Given
        String token = tokenOf(mockMvc, "ana@example.com");
        atDebug(ASPECT_LOGGER);
        atDebug(FILTER_CHAIN_LOGGER);
        String path = "/api/orgs/" + AcmeFixture.UNKNOWN_ID;
        MockHttpServletRequestBuilder request = get(path).header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (header != null) {
            request.header(REQUEST_ID_HEADER, header);
        }
        int start = output.getAll().length();

        // When
        MvcResult result = mockMvc.perform(request).andReturn();

        // Then
        DocumentContext problem = assertProblem(result, 403, "FORBIDDEN", path);
        assertThat(problem.read("$", Map.class)).doesNotContainKey("requestId");
        List<String> lines = RequestLog.since(output, start).lines();
        assertThat(lines).as("the log of the request").isNotEmpty();
        List<String> loggedIds = lines.stream().map(RequestCorrelationTest::loggedRequestId).toList();
        assertThat(loggedIds).as("the request ID of every line").doesNotContainNull().containsOnly(loggedIds.get(0));
        assertThat(UUID.fromString(loggedIds.get(0)).toString()).isEqualTo(loggedIds.get(0));
        if (header != null) {
            assertThat(lines).noneMatch(line -> line.contains(header));
        }
    }

    @Test
    @Tag("AC-9.8")
    @DisplayName("AC-9.8 Request correlation is cleared after each request")
    void requestCorrelationIsClearedAfterEachRequest(CapturedOutput output) throws Exception {
        // Given
        String token = tokenOf(mockMvc, "ana@example.com");
        atDebug(ASPECT_LOGGER);
        atDebug(FILTER_CHAIN_LOGGER);
        mockMvc.perform(get("/api/orgs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header(REQUEST_ID_HEADER, "req-98"))
                .andReturn();

        // When the request has completed
        // Then
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("userId")).isNull();

        // And a following anonymous request
        int start = output.getAll().length();
        MvcResult anonymous = mockMvc.perform(get("/api/orgs")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
        List<String> lines = RequestLog.since(output, start).lines();
        assertThat(lines).as("the log of the following request").isNotEmpty();
        assertThat(lines).noneMatch(line -> line.contains("req-98") || line.contains(acme.ana().getId().toString()));
    }

    @Test
    @DisplayName("D17 the request-ID filter runs before the Spring Security filter chain, the user-ID filter after it")
    void theMdcFiltersRunAroundTheSecurityFilterChain() {
        int securityFilterChain = securityFilterChainRegistration.getOrder();

        assertThat(securityFilterChainRegistration.getFilterName()).isEqualTo("springSecurityFilterChain");
        assertThat(OrderUtils.getOrder(RequestIdMdcFilter.class)).isLessThan(securityFilterChain);
        assertThat(OrderUtils.getOrder(UserIdMdcFilter.class)).isGreaterThan(securityFilterChain);
    }

    private void atDebug(String logger) {
        loggingSystem.setLogLevel(logger, LogLevel.DEBUG);
        changedLoggers.add(logger);
    }

    private static String loggedRequestId(String line) {
        Matcher matcher = LOGGED_REQUEST_ID.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }
}
