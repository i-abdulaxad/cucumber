package com.example.authservice.controller;

import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.example.authservice.support.ApiCalls.loginBody;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-2.10: BCrypt only reads the first 72 bytes, so a login password longer than 72 UTF-8 bytes is answered
 * 401 INVALID_CREDENTIALS, with the same detail as AC-2.4 and no validation error. Only the exact 72-byte password
 * logs in.
 */
@AuthServiceIntegrationTest
class LongLoginPasswordTest {

    private static final String A72 = "a72@example.com";
    private static final String EURO72 = "euro72@example.com";
    private static final String SEVENTY_TWO_A = "A".repeat(72);
    /**
     * 24 euro signs are 72 UTF-8 bytes.
     */
    private static final String TWENTY_FOUR_EUROS = "€".repeat(24);
    private static final String LOGIN = "/api/auth/login";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @BeforeEach
    void registerA72() throws Exception {
        fixture.reset();
        register(A72, SEVENTY_TWO_A);
    }

    @Test
    @Tag("AC-2.10")
    @DisplayName("AC-2.10 row 1: the exact 72-byte password logs in")
    void theExact72BytePasswordLogsIn() throws Exception {
        // When
        MvcResult result = login(A72, SEVENTY_TWO_A);

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @Tag("AC-2.10")
    @DisplayName("AC-2.10 row 2: 72 x \"A\" + \"Z\" is INVALID_CREDENTIALS with the AC-2.4 detail")
    void the72BytePasswordWithASuffixIsInvalidCredentials() throws Exception {
        assertInvalidCredentialsLikeAWrongPassword(A72, SEVENTY_TWO_A + "Z");
    }

    @Test
    @Tag("AC-2.10")
    @DisplayName("AC-2.10 row 3: 24 x \"€\" + \"X\" against the 72 x \"A\" account is INVALID_CREDENTIALS with the "
            + "AC-2.4 detail")
    void aMultiByteOverlongPasswordIsInvalidCredentials() throws Exception {
        assertInvalidCredentialsLikeAWrongPassword(A72, TWENTY_FOUR_EUROS + "X");
    }

    /**
     * Row 3 as written passes even without the fix, because its first 72 bytes differ from the stored password.
     * This test proves the multi-byte case: the account's own 72-byte password, plus a suffix.
     */
    @Test
    @Tag("AC-2.10")
    @DisplayName("AC-2.10 (strengthened row 3) an account whose password is 24 x \"€\" rejects 24 x \"€\" + \"X\"")
    void aMultiBytePasswordWithASuffixIsInvalidCredentials() throws Exception {
        // Given
        register(EURO72, TWENTY_FOUR_EUROS);
        assertThat(login(EURO72, TWENTY_FOUR_EUROS).getResponse().getStatus()).as("exact password").isEqualTo(200);

        // Then
        assertInvalidCredentialsLikeAWrongPassword(EURO72, TWENTY_FOUR_EUROS + "X");
    }

    private void assertInvalidCredentialsLikeAWrongPassword(String email, String password) throws Exception {
        // Given: the AC-2.4 answer for a plainly wrong password
        DocumentContext wrongPassword = assertProblem(login(email, "Wrong123!"), 401, "INVALID_CREDENTIALS", LOGIN);

        // When
        MvcResult result = login(email, password);

        // Then
        DocumentContext problem = assertProblem(result, 401, "INVALID_CREDENTIALS", LOGIN);
        assertThat(problem.read("$.detail", String.class)).isEqualTo(wrongPassword.read("$.detail", String.class));
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"errors\"");
    }

    private void register(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"fullName\":\"E\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("registration of " + email).isEqualTo(201);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andReturn();
    }
}
