package com.example.authservice.security;

import com.example.authservice.enums.UserStatus;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AcmeFixture.Acme;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.function.Function;
import java.util.stream.Stream;

import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.tokenOf;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * US-3: auth-service accepts only tokens the gateway would accept and answers anything else with 401 (direct calls).
 */
@AuthServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class BearerAuthenticationTest {

    private static final String ORGS = "/api/orgs";
    private static final String ANA = "ana@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    private Acme acme;

    @BeforeEach
    void seedAcme() {
        acme = fixture.seed();
    }

    static Stream<Arguments> invalidCredentials() {
        return Stream.of(
                Arguments.of("header missing", (Function<Acme, String>) acme -> null),
                Arguments.of("wrong scheme", (Function<Acme, String>) acme -> "Basic YW5hOlNlY3JldDEyMyE="),
                Arguments.of("empty bearer value", (Function<Acme, String>) acme -> "Bearer "),
                Arguments.of("not a JWT", (Function<Acme, String>) acme -> "Bearer not-a-jwt"),
                Arguments.of("signed with another secret", (Function<Acme, String>) acme ->
                        bearer(TestJwt.signedWithAnotherSecret(acme.ana().getId(), ANA))),
                Arguments.of("expired", (Function<Acme, String>) acme ->
                        bearer(TestJwt.expired(acme.ana().getId(), ANA))),
                Arguments.of("unsigned", (Function<Acme, String>) acme ->
                        bearer(TestJwt.unsigned(acme.ana().getId(), ANA))),
                Arguments.of("HS256 with the shared secret", (Function<Acme, String>) acme ->
                        bearer(TestJwt.signedWithHs256(acme.ana().getId(), ANA))),
                Arguments.of("payload altered after signing", (Function<Acme, String>) acme ->
                        bearer(TestJwt.withAlteredSubject(acme.ana().getId(), ANA, acme.ben().getId()))),
                Arguments.of("subject is not a UUID", (Function<Acme, String>) acme ->
                        bearer(TestJwt.withSubject("ana", ANA))),
                Arguments.of("no subject", (Function<Acme, String>) acme -> bearer(TestJwt.withoutSubject(ANA))));
    }

    @ParameterizedTest(name = "AC-3.1 {0}")
    @MethodSource("invalidCredentials")
    @Tag("AC-3.1")
    void aProtectedRequestWithoutValidCredentialsIsA401Problem(String row, Function<Acme, String> authorization)
            throws Exception {
        // Given
        MockHttpServletRequestBuilder request = get(ORGS);
        String header = authorization.apply(acme);
        if (header != null) {
            request.header(HttpHeaders.AUTHORIZATION, header);
        }

        // When
        MvcResult result = mockMvc.perform(request).andReturn();

        // Then
        assertProblem(result, 401, "UNAUTHENTICATED", ORGS);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("AC-3.2 A valid token authenticates the request")
    void aValidTokenAuthenticatesTheRequest() throws Exception {
        // When
        MvcResult result = mockMvc.perform(get(ORGS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(mockMvc, ANA))).andReturn();

        // Then
        assertAuthenticated(result);
    }

    @Test
    @Tag("AC-3.3")
    @DisplayName("AC-3.3 The bearer scheme is matched case-insensitively")
    void theBearerSchemeIsMatchedCaseInsensitively() throws Exception {
        // When
        MvcResult result = mockMvc.perform(get(ORGS)
                .header(HttpHeaders.AUTHORIZATION, "bearer " + tokenOf(mockMvc, ANA))).andReturn();

        // Then
        assertAuthenticated(result);
    }

    @Test
    @Tag("AC-3.4")
    @DisplayName("AC-3.4 A suspended user's existing token stops working")
    void aSuspendedUsersExistingTokenStopsWorking() throws Exception {
        // Given
        String token = tokenOf(mockMvc, "cai@example.com");
        fixture.setStatus(acme.cai(), UserStatus.SUSPENDED);

        // When
        MvcResult result = mockMvc.perform(get(ORGS).header(HttpHeaders.AUTHORIZATION, bearer(token))).andReturn();

        // Then
        assertProblem(result, 401, "UNAUTHENTICATED", ORGS);
    }

    @Test
    @Tag("AC-3.5")
    @DisplayName("AC-3.5 A valid token for a user that no longer exists is rejected")
    void aValidTokenForAUserThatNoLongerExistsIsRejected() throws Exception {
        // When
        MvcResult result = mockMvc.perform(get(ORGS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.valid(AcmeFixture.UNKNOWN_ID, "gone@example.com"))))
                .andReturn();

        // Then
        assertProblem(result, 401, "UNAUTHENTICATED", ORGS);
    }

    @Test
    @Tag("AC-3.8")
    @DisplayName("AC-3.8 Rejected tokens are never logged")
    void rejectedTokensAreNeverLogged(CapturedOutput output) throws Exception {
        // Given
        int logLengthBefore = output.getAll().length();

        // When
        MvcResult result = mockMvc.perform(get(ORGS).header(HttpHeaders.AUTHORIZATION,
                "Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJ4In0.tampered-sig-QA")).andReturn();

        // Then
        assertProblem(result, 401, "UNAUTHENTICATED", ORGS);
        String requestLog = output.getAll().substring(logLengthBefore);
        assertThat(output.getAll()).doesNotContain("tampered-sig-QA");
        assertThat(requestLog).doesNotContainPattern("(?m)^\\S+\\s+ERROR\\s").doesNotContain("\tat ");
    }

    @Test
    @Tag("AC-3.1")
    @DisplayName("AC-3.1 Tokens with subject problems are rejected without ERROR logs or stack traces")
    void tokensWithSubjectProblemsAreRejectedQuietly(CapturedOutput output) throws Exception {
        // Given
        int logLengthBefore = output.getAll().length();

        // When
        mockMvc.perform(get(ORGS).header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.withSubject("ana", ANA))));
        mockMvc.perform(get(ORGS).header(HttpHeaders.AUTHORIZATION, bearer(TestJwt.withoutSubject(ANA))));

        // Then
        String requestLog = output.getAll().substring(logLengthBefore);
        assertThat(requestLog).doesNotContainPattern("(?m)^\\S+\\s+ERROR\\s").doesNotContain("\tat ");
    }

    /**
     * An anonymous request gets 401 before reaching Spring MVC; an authenticated one reaches it and gets 200.
     */
    private static void assertAuthenticated(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("UNAUTHENTICATED");
    }
}
