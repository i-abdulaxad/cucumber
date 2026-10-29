package org.reportservice.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.model.UploadToken;
import org.reportservice.repository.UploadTokenRepository;
import org.reportservice.support.AuthServiceStub;
import org.reportservice.support.ReportServiceIntegrationTest;
import org.reportservice.support.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reportservice.support.ProblemAssertions.assertProblem;
import static org.reportservice.support.ReportsFixture.CAI;
import static org.reportservice.support.ReportsFixture.CHECKOUT;
import static org.reportservice.support.ReportsFixture.DAN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * AC-6.4 and AC-2.5 (D5): on user paths only a valid JWT authenticates, the scheme in any letter case; anything else
 * is 401 UNAUTHENTICATED with {@code WWW-Authenticate: Bearer}, and C5 is not called. An active upload token is no
 * user credential.
 */
@ReportServiceIntegrationTest
class BearerAuthenticationTest {

    private static final String RUNS = "/api/projects/" + CHECKOUT + "/probe";

    private final AuthServiceStub stub = AuthServiceStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UploadTokenRepository tokens;

    @BeforeEach
    void resetTheStub() {
        stub.reset();
    }

    @ParameterizedTest(name = "AC-6.4 GET '{runs}' with {0}: 401 UNAUTHENTICATED, no C5 call")
    @ValueSource(strings = {"(none)", "an expired token of Dan", "an HS256 token of Dan",
            "a token of Dan with \"sub\" in upper case", "a token signed with another secret", "a token without exp",
            "alg none", "Basic credentials", "Bearer without a token"})
    @Tag("AC-6.4")
    void onlyValidJwtsAuthenticateUserPaths(String authorization) throws Exception {
        String email = DAN.email();
        String header = switch (authorization) {
            case "(none)" -> null;
            case "an expired token of Dan" -> "Bearer " + TestTokens.expired(DAN.id(), email);
            case "an HS256 token of Dan" -> "Bearer " + TestTokens.signedWithHs256(DAN.id(), email);
            case "a token of Dan with \"sub\" in upper case" ->
                    "Bearer " + TestTokens.withSubject(DAN.id().toString().toUpperCase(), email);
            case "a token signed with another secret" ->
                    "Bearer " + TestTokens.signedWithAnotherSecret(DAN.id(), email);
            case "a token without exp" -> "Bearer " + TestTokens.withoutExpiration(DAN.id(), email);
            case "alg none" -> "Bearer " + TestTokens.unsigned(DAN.id(), email);
            case "Basic credentials" -> "Basic ZGFuOnNlY3JldA==";
            default -> "Bearer ";
        };
        MockHttpServletRequestBuilder request = get(RUNS);
        if (header != null) {
            request.header(HttpHeaders.AUTHORIZATION, header);
        }

        MvcResult result = mockMvc.perform(request).andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @Tag("AC-2.5")
    @DisplayName("AC-2.5 An active upload token of Checkout on GET {runs}: 401 UNAUTHENTICATED, no C5 call")
    void anUploadTokenIsNotAUserCredential() throws Exception {
        String token = activeToken();

        MvcResult result = mockMvc.perform(get(RUNS).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(stub.received()).isEmpty();
    }

    @ParameterizedTest(name = "D5 the scheme \"{0}\" is accepted")
    @ValueSource(strings = {"Bearer", "bearer", "BEARER"})
    @DisplayName("D5 The bearer scheme is matched in any letter case and the token forwarded as Bearer")
    void theBearerSchemeIsMatchedInAnyLetterCase(String scheme) throws Exception {
        String token = DAN.token();

        MvcResult result = mockMvc.perform(get(RUNS).header(HttpHeaders.AUTHORIZATION, scheme + " " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stub.received()).singleElement()
                .satisfies(received -> assertThat(received.authorization()).isEqualTo("Bearer " + token));
    }

    /** Stores an active upload token of Checkout the way D4 describes, and returns its text. */
    private String activeToken() throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = "rct_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        UploadToken row = new UploadToken();
        row.setId(UUID.randomUUID());
        row.setProjectId(CHECKOUT);
        row.setName("AC-2.5");
        row.setTokenHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII))));
        row.setTokenPrefix(token.substring(0, 12));
        row.setCreatedBy(CAI.id());
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        tokens.save(row);
        return token;
    }
}
