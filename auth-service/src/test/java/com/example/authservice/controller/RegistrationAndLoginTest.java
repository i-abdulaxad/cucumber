package com.example.authservice.controller;

import com.example.authservice.model.User;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AcmeFixture.Acme;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.TestJwt;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.example.authservice.support.ApiCalls.loginBody;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * US-2: registration and login with email normalisation (D12) and field length limits (T10).
 */
@AuthServiceIntegrationTest
class RegistrationAndLoginTest {

    private static final String REGISTER = "/api/auth/register";
    private static final String LOGIN = "/api/auth/login";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private UserRepository userRepository;

    private Acme acme;

    @BeforeEach
    void seedAcme() {
        acme = fixture.seed();
    }

    @Test
    @Tag("AC-2.1")
    @DisplayName("AC-2.1 Registration creates a pending account and never returns the password")
    void registrationCreatesAPendingAccount() throws Exception {
        // When
        MvcResult result = register("{\"email\":\"Fay@Example.com\",\"password\":\"Secret123!\",\"fullName\":\"Fay Lin\"}");

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String json = result.getResponse().getContentAsString();
        DocumentContext body = JsonPath.parse(json);
        assertThat(UUID.fromString(body.read("$.id", String.class))).isNotNull();
        assertThat(body.read("$.email", String.class)).isEqualTo("fay@example.com");
        assertThat(body.read("$.fullName", String.class)).isEqualTo("Fay Lin");
        assertThat(body.read("$.status", String.class)).isEqualTo("PENDING_VERIFICATION");
        assertThat(body.read("$.mfaEnabled", Boolean.class)).isFalse();
        assertThat(body.read("$.createdAt", String.class)).isNotBlank();
        Map<String, Object> members = body.read("$");
        assertThat(members.keySet()).noneSatisfy(name -> assertThat(name).containsIgnoringCase("password"));
        assertThat(json).doesNotContain("Secret123!");
        assertThat(userRepository.findByEmail("fay@example.com")).isPresent();
    }

    @ParameterizedTest(name = "AC-2.2 registering {0} again is a conflict")
    @ValueSource(strings = {"ana@example.com", "Ana@Example.COM"})
    @Tag("AC-2.2")
    void anAlreadyRegisteredEmailInAnyLetterCaseIsAConflict(String email) throws Exception {
        // When
        MvcResult result = register("{\"email\":\"" + email + "\",\"password\":\"Secret123!\",\"fullName\":\"Someone\"}");

        // Then
        assertProblem(result, 409, "EMAIL_ALREADY_REGISTERED", REGISTER);
        List<User> users = userRepository.findAll();
        assertThat(users).filteredOn(user -> user.getEmail().equalsIgnoreCase("ana@example.com"))
                .singleElement()
                .satisfies(user -> assertThat(user.getEmail()).isEqualTo("ana@example.com"));
    }

    @Test
    @Tag("AC-2.5")
    @DisplayName("AC-2.5 Login ignores the email's letter case")
    void loginIgnoresTheEmailsLetterCase() throws Exception {
        // When
        MvcResult result = mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("ANA@example.com", AcmeFixture.PASSWORD)))
                .andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
        String subject = Jwts.parser().verifyWith(TestJwt.key()).build().parseSignedClaims(token).getPayload()
                .getSubject();
        assertThat(subject).isEqualTo(acme.ana().getId().toString());
    }

    static Stream<Arguments> tooLongFields() {
        String validPassword = "Secret123!";
        String validName = "Fay Lin";
        String validEmail = "fay@example.com";
        return Stream.of(
                Arguments.of("password", "73 ASCII characters", validEmail, "a".repeat(73), validName),
                Arguments.of("fullName", "101 characters", validEmail, validPassword, "n".repeat(101)),
                Arguments.of("email", "a syntactically valid address of 255 characters", emailOf255Characters(),
                        validPassword, validName));
    }

    @ParameterizedTest(name = "AC-2.9 {0} is {1}")
    @MethodSource("tooLongFields")
    @Tag("AC-2.9")
    void fieldLengthLimitsAreValidationErrors(String field, String row, String email, String password,
                                              String fullName) throws Exception {
        // When
        MvcResult result = register("{\"email\":\"" + email + "\",\"password\":\"" + password
                + "\",\"fullName\":\"" + fullName + "\"}");

        // Then
        DocumentContext body = assertProblem(result, 400, "VALIDATION_FAILED", REGISTER);
        List<Map<String, String>> errors = body.read("$.errors");
        assertThat(errors).extracting(error -> error.get("field")).containsExactly(field);
        assertThat(userRepository.findByEmail(email.toLowerCase())).isEmpty();
    }

    /**
     * 64-character local part, "@", and a 190-character domain of labels of at most 63 characters.
     */
    private static String emailOf255Characters() {
        String domain = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(58) + ".com";
        String email = "a".repeat(64) + "@" + domain;
        assertThat(email).hasSize(255);
        return email;
    }

    private MvcResult register(String body) throws Exception {
        return mockMvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }
}
