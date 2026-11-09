package com.example.authservice.qa;

import com.example.authservice.enums.UserStatus;
import com.example.authservice.model.User;
import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AcmeFixture.Acme;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static com.example.authservice.qa.QaSupport.as;
import static com.example.authservice.qa.QaSupport.json;
import static com.example.authservice.support.ApiCalls.loginBody;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * qa input edge cases: multi-byte passwords at the 72-byte BCrypt limit, email normalisation with non-ASCII letters,
 * boundaries, mass assignment, malformed JSON and wrong content types on every body-carrying operation.
 */
@AuthServiceIntegrationTest
class QaInputEdgeCasesTest {

    private static final String REGISTER = "/api/auth/register";
    private static final String LOGIN = "/api/auth/login";
    /**
     * 3 bytes in UTF-8.
     */
    private static final String EURO = "€";
    /**
     * 4 bytes in UTF-8, 2 UTF-16 code units.
     */
    private static final String EMOJI = "😀";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    private Acme acme;

    @BeforeEach
    void seedAcme() {
        acme = fixture.seed();
    }

    @ParameterizedTest(name = "AC-2.9 (qa) a password of {0} registers and logs in")
    @CsvSource({"24 euro signs (72 bytes), EURO, 24", "18 emoji (72 bytes), EMOJI, 18",
            "72 ASCII characters, A, 72"})
    @Tag("AC-2.9")
    void aPasswordOfExactly72BytesWorks(String row, String unit, int count) throws Exception {
        // Given
        String password = character(unit).repeat(count);

        // When
        MvcResult registered = register("mb@example.com", password, "Multi Byte");
        MvcResult loggedIn = login("mb@example.com", password);

        // Then
        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        assertThat(loggedIn.getResponse().getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "AC-2.9 (qa) a password of {0} is a validation error, not a 500")
    @CsvSource({"25 euro signs (75 bytes; 25 characters), EURO, 25", "19 emoji (76 bytes; 38 UTF-16 units), EMOJI, 19",
            "71 ASCII characters and one euro sign (74 bytes), MIXED, 1"})
    @Tag("AC-2.9")
    void aPasswordOverThe72ByteLimitIsAValidationError(String row, String unit, int count) throws Exception {
        // Given
        String password = unit.equals("MIXED") ? "a".repeat(71) + EURO : character(unit).repeat(count);

        // When
        MvcResult result = register("mb@example.com", password, "Multi Byte");

        // Then
        DocumentContext problem = assertProblem(result, 400, "VALIDATION_FAILED", REGISTER);
        assertThat(problem.read("$.errors[*].field", List.class)).containsExactly("password");
        assertThat(userRepository.findByEmail("mb@example.com")).isEmpty();
    }

    @Test
    @Tag("AC-6.6")
    @DisplayName("qa: the anonymous accept body has the same 72-byte password limit")
    void theAcceptPasswordHasTheSameByteLimit() throws Exception {
        // Given
        String token = JsonPath.read(mockMvc.perform(json(as(post(invitations()), acme.ben()),
                        "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\"}")).andReturn().getResponse().getContentAsString(),
                "$.token");
        String path = "/api/auth/invitations/" + token + "/accept";

        // When
        MvcResult result = mockMvc.perform(json(post(path),
                "{\"password\":\"" + EURO.repeat(25) + "\",\"fullName\":\"Fay Lin\"}")).andReturn();

        // Then
        assertProblem(result, 400, "VALIDATION_FAILED", path);
        assertThat(userRepository.findByEmail("fay@example.com")).isEmpty();
    }

    @Test
    @Tag("AC-2.4")
    @DisplayName("qa: a 200 000-character password at login is a plain INVALID_CREDENTIALS, not a 500")
    void aHugeLoginPasswordIsInvalidCredentials() throws Exception {
        assertProblem(login("ana@example.com", "x".repeat(200_000)), 401, "INVALID_CREDENTIALS", LOGIN);
    }

    @Test
    @Tag("AC-2.4")
    @DisplayName("qa: the correct password with one character appended is rejected (password shorter than 72 bytes)")
    void thePasswordWithASuffixIsRejected() throws Exception {
        assertProblem(login("ana@example.com", AcmeFixture.PASSWORD + "X"), 401, "INVALID_CREDENTIALS", LOGIN);
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("qa D12: a non-ASCII upper-case email is lower-cased, logs in in any case, and can't register twice")
    void nonAsciiEmailsAreNormalised() throws Exception {
        // When
        MvcResult registered = register("ÉLODIE@Example.com", "Secret123!", "Élodie Roux");

        // Then
        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        assertThat(JsonPath.<String>read(registered.getResponse().getContentAsString(), "$.email"))
                .isEqualTo("élodie@example.com");
        assertThat(login("élodie@example.com", "Secret123!").getResponse().getStatus()).isEqualTo(200);
        assertThat(login("ÉLODIE@EXAMPLE.COM", "Secret123!").getResponse().getStatus()).isEqualTo(200);
        assertProblem(register("Élodie@example.com", "Secret123!", "Someone"), 409, "EMAIL_ALREADY_REGISTERED",
                REGISTER);
    }

    @ParameterizedTest(name = "AC-5.5 (qa D12) inviting the member {0} is ALREADY_MEMBER")
    @ValueSource(strings = {"CAI@EXAMPLE.COM", "cai@Example.Com"})
    @Tag("AC-5.5")
    void anyLetterCaseOfAMembersEmailIsAlreadyMember(String email) throws Exception {
        assertProblem(mockMvc.perform(json(as(post(invitations()), acme.ben()),
                        "{\"email\":\"" + email + "\",\"role\":\"READER\"}")).andReturn(), 409, "ALREADY_MEMBER",
                invitations());
    }

    @Test
    @Tag("AC-2.1")
    @DisplayName("qa mass assignment: registration ignores id, status, mfaEnabled and passwordHash in the body")
    void registrationIgnoresServerOwnedFields() throws Exception {
        // When
        MvcResult result = mockMvc.perform(json(post(REGISTER), "{\"email\":\"zed@example.com\","
                + "\"password\":\"Secret123!\",\"fullName\":\"Zed\",\"id\":\"" + acme.ana().getId() + "\","
                + "\"status\":\"ACTIVE\",\"mfaEnabled\":true,\"passwordHash\":\"$2a$10$qa\"}")).andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        User zed = userRepository.findByEmail("zed@example.com").orElseThrow();
        assertThat(zed.getId()).isNotEqualTo(acme.ana().getId());
        assertThat(zed.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(zed.isMfaEnabled()).isFalse();
        assertThat(zed.getPasswordHash()).isNotEqualTo("$2a$10$qa");
        assertThat(userRepository.findById(acme.ana().getId()).orElseThrow().getEmail()).isEqualTo("ana@example.com");
    }

    @Test
    @Tag("AC-4.1")
    @DisplayName("qa mass assignment: creating an organization ignores plan, seatLimit and id in the body")
    void organizationCreationIgnoresServerOwnedFields() throws Exception {
        // When
        MvcResult result = mockMvc.perform(json(as(post("/api/orgs"), acme.eve()), "{\"name\":\"Globex QA\","
                        + "\"plan\":\"ENTERPRISE\",\"seatLimit\":1000,\"id\":\"" + acme.orgId() + "\",\"myRole\":\"READER\"}"))
                .andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        DocumentContext body = JsonPath.parse(result.getResponse().getContentAsString());
        assertThat(body.read("$.plan", String.class)).isEqualTo("FREE");
        assertThat(body.read("$.seatLimit", Integer.class)).isEqualTo(5);
        assertThat(body.read("$.id", String.class)).isNotEqualTo(acme.orgId().toString());
        assertThat(body.read("$.myRole", String.class)).isEqualTo("OWNER");
    }

    @Test
    @Tag("AC-5.1")
    @DisplayName("qa mass assignment: creating an invitation ignores token, status and expiresAt in the body")
    void invitationCreationIgnoresServerOwnedFields() throws Exception {
        // When
        MvcResult result = mockMvc.perform(json(as(post(invitations()), acme.ben()),
                "{\"email\":\"fay@example.com\",\"role\":\"MEMBER\",\"token\":\"chosen-by-QA\",\"status\":\"ACCEPTED\","
                        + "\"expiresAt\":\"2999-01-01T00:00:00\"}")).andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(invitationRepository.findByToken("chosen-by-QA")).isEmpty();
        DocumentContext body = JsonPath.parse(result.getResponse().getContentAsString());
        assertThat(body.read("$.status", String.class)).isEqualTo("PENDING");
        assertThat(body.read("$.expiresAt", String.class)).doesNotStartWith("2999");
    }

    @ParameterizedTest(name = "AC-2.9 / AC-4.2 (qa boundary) {0}: {2}")
    @CsvSource({
            "fullName of 100 characters, REGISTER_NAME_100, 201",
            "email of 254 characters, REGISTER_EMAIL_254, 201",
            "organization name of 100 characters, ORG_NAME_100, 201",
            "invitation email of 255 characters, INVITE_EMAIL_255, 400"
    })
    @Tag("AC-2.9")
    @Tag("AC-4.2")
    void fieldBoundaries(String row, String scenario, int status) throws Exception {
        // When
        MvcResult result = switch (scenario) {
            case "REGISTER_NAME_100" -> register("long@example.com", "Secret123!", "n".repeat(100));
            case "REGISTER_EMAIL_254" -> register(email(254), "Secret123!", "Long Email");
            case "ORG_NAME_100" -> mockMvc.perform(json(as(post("/api/orgs"), acme.eve()),
                    "{\"name\":\"" + "o".repeat(100) + "\"}")).andReturn();
            default -> mockMvc.perform(json(as(post(invitations()), acme.ben()),
                    "{\"email\":\"" + email(255) + "\",\"role\":\"READER\"}")).andReturn();
        };

        // Then
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        if (status == 400) {
            assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"VALIDATION_FAILED\"");
        }
    }

    @ParameterizedTest(name = "AC-1.2 (qa) unreadable body {0} on POST /api/orgs is MALFORMED_REQUEST")
    @ValueSource(strings = {"", "null", "[]", "{", "{\"name\":{}}", "{\"name\":[\"x\"]}", "DEEP_ARRAY", "DEEP_OBJECT"})
    @Tag("AC-1.2")
    void anUnreadableBodyIsMalformedRequest(String body) throws Exception {
        // Given
        String content = switch (body) {
            case "DEEP_ARRAY" -> "[".repeat(5_000) + "]".repeat(5_000);
            case "DEEP_OBJECT" -> "{\"name\":".repeat(3_000) + "1" + "}".repeat(3_000);
            default -> body;
        };

        // When
        MvcResult result = mockMvc.perform(json(as(post("/api/orgs"), acme.eve()), content)).andReturn();

        // Then
        assertProblem(result, 400, "MALFORMED_REQUEST", "/api/orgs");
    }

    @Test
    @Tag("AC-1.2")
    @DisplayName("qa: a body that isn't valid UTF-8 is MALFORMED_REQUEST")
    void invalidUtf8IsMalformedRequest() throws Exception {
        MvcResult result = mockMvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
                .content(new byte[]{'{', '"', 'e', '"', ':', '"', (byte) 0xC3, (byte) 0x28, '"', '}'})).andReturn();

        assertProblem(result, 400, "MALFORMED_REQUEST", REGISTER);
    }

    @ParameterizedTest(name = "AC-1.6 (qa) {0} {1} with Content-Type {2} is a 415 Problem")
    @CsvSource({
            "POST,  /api/auth/register,                     text/plain",
            "POST,  /api/auth/register,                     application/xml",
            "POST,  /api/auth/register,                     application/x-www-form-urlencoded",
            "POST,  /api/auth/register,                     NONE",
            "POST,  /api/auth/login,                        application/xml",
            "POST,  /api/auth/invitations/inv-7Kq2/accept,  text/plain",
            "POST,  /api/orgs,                              text/plain",
            "POST,  /api/orgs/{acme}/invitations,           application/xml",
            "PATCH, /api/orgs/{acme}/members/{cai},         text/plain"
    })
    @Tag("AC-1.6")
    void aWrongContentTypeIs415(String method, String pathTemplate, String contentType) throws Exception {
        // Given
        String path = pathTemplate.replace("{acme}", acme.orgId().toString())
                .replace("{cai}", acme.cai().getId().toString());
        MockHttpServletRequestBuilder request = as(request(HttpMethod.valueOf(method), path),
                path.startsWith("/api/orgs") ? acme.ana() : null).content("{\"email\":\"x\"}");
        if (!contentType.equals("NONE")) {
            request.contentType(contentType);
        }

        // When
        MvcResult result = mockMvc.perform(request).andReturn();

        // Then
        assertProblem(result, 415, "UNSUPPORTED_MEDIA_TYPE", path);
    }

    @ParameterizedTest(name = "AC-4.6 (qa) a malformed UUID in {0} is MALFORMED_REQUEST")
    @CsvSource({
            "the invitation id, DELETE, /api/orgs/{acme}/invitations/not-a-uuid",
            "the member id,     DELETE, /api/orgs/{acme}/members/not-a-uuid",
            "the member id,     PATCH,  /api/orgs/{acme}/members/123",
            "the organization id, GET,  /api/orgs/00000000-0000-0000-0000/members"
    })
    @Tag("AC-4.6")
    void aMalformedUuidIsMalformedRequest(String where, String method, String pathTemplate) throws Exception {
        // Given
        String path = pathTemplate.replace("{acme}", acme.orgId().toString());
        MockHttpServletRequestBuilder request = method.equals("PATCH")
                ? json(as(patch(path), acme.ana()), "{\"role\":\"READER\"}")
                : as(method.equals("GET") ? get(path) : delete(path), acme.ana());

        // When / Then
        assertProblem(mockMvc.perform(request).andReturn(), 400, "MALFORMED_REQUEST", path);
    }

    @Test
    @Tag("AC-1.6")
    @DisplayName("qa D2: asking for XML gets a 406 NOT_ACCEPTABLE Problem")
    void anUnacceptableMediaTypeIs406() throws Exception {
        MvcResult result = mockMvc.perform(as(get("/api/orgs"), acme.ana()).accept(MediaType.APPLICATION_XML))
                .andReturn();

        assertProblem(result, 406, "NOT_ACCEPTABLE", "/api/orgs");
    }

    @Test
    @Tag("AC-1.1")
    @DisplayName("qa: a JSON number where the email string belongs is a malformed request")
    void aNumberForTheEmailIsMalformed() throws Exception {
        MvcResult result = mockMvc.perform(json(post(REGISTER),
                "{\"email\":123,\"password\":\"Secret123!\",\"fullName\":\"Zed\"}")).andReturn();

        assertProblem(result, 400, "MALFORMED_REQUEST", REGISTER);
    }

    private static String character(String unit) {
        return switch (unit) {
            case "EURO" -> EURO;
            case "EMOJI" -> EMOJI;
            default -> unit;
        };
    }

    /**
     * A syntactically valid address of exactly {@code length} characters: 64-character local part, labels of 63.
     */
    private static String email(int length) {
        String local = "a".repeat(64);
        int domainLength = length - local.length() - 1;
        int lastLabel = domainLength - 64 - 64 - 4;
        String domain = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(lastLabel) + ".com";
        String email = local + "@" + domain;
        assertThat(email).hasSize(length);
        return email;
    }

    private MvcResult register(String email, String password, String fullName) throws Exception {
        return mockMvc.perform(json(post(REGISTER), "{\"email\":\"" + email + "\",\"password\":\"" + password
                + "\",\"fullName\":\"" + fullName + "\"}")).andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(json(post(LOGIN), loginBody(email, password))).andReturn();
    }

    private String invitations() {
        return "/api/orgs/" + acme.orgId() + "/invitations";
    }
}
