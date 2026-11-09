package com.example.authservice.qa;

import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.OrgMembershipRepository;
import com.example.authservice.repository.OrganizationRepository;
import com.example.authservice.repository.ProjectMembershipRepository;
import com.example.authservice.repository.ProjectRepository;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.ProjectCalls;
import com.example.authservice.support.ProjectFixture;
import com.example.authservice.support.ProjectFixture.Projects;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static com.example.authservice.support.ProjectCalls.project;
import static com.example.authservice.support.ProjectCalls.projects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * qa, phase 4 US-5 to US-7 (D11, D12, D13; adversarial): strict input beyond the spec's example rows. UUID path forms
 * that aren't canonical; JSON numbers and booleans in every string field, and null in every string field; code-point
 * lengths with supplementary characters, combining marks and untrimmed spaces; every Unicode-blank code point; every
 * control character; lone surrogates in every position; and request bodies whose bytes aren't valid UTF-8. Nothing is
 * ever stored and no answer is a 5xx. Background: the Projects fixture, and Ben has invited kit@example.com to Acme.
 * <p>
 * Build time (FU-21): almost every row is rejected and stores nothing, so the fixture is seeded once and reused by the
 * next test of this class while the database still has the state the fixture left. After each test the state is
 * compared with that snapshot, and any difference (a row that stored something on purpose, or a failure) makes the
 * next test seed afresh. JUnit runs the tests of one class one after another, so no other class sees the reuse.
 */
@AuthServiceIntegrationTest
@Import(ProjectFixture.class)
class QaStrictInputTest {

    private static final String EMOJI = "\uD83D\uDE00";
    /**
     * U+10400 DESERET CAPITAL LETTER LONG I: one code point, two UTF-16 units.
     */
    private static final String DESERET = "\uD801\uDC00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectFixture fixture;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrgMembershipRepository orgMembershipRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMembershipRepository projectMembershipRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    private static Projects projects;
    private static ProjectCalls calls;
    private static String kitToken;
    private static List<Object> seededState;

    @BeforeEach
    void seedProjectsAndInviteKitUnlessUnchanged() throws Exception {
        if (seededState != null && state().equals(seededState)) {
            return;
        }
        projects = fixture.seed();
        calls = new ProjectCalls(mockMvc);
        MvcResult invited = calls.as("ben", post("/api/orgs/" + projects.acme().orgId() + "/invitations"),
                "{\"email\":\"kit@example.com\",\"role\":\"MEMBER\"}");
        assertThat(invited.getResponse().getStatus()).isEqualTo(201);
        kitToken = JsonPath.read(invited.getResponse().getContentAsString(), "$.token");
        seededState = state();
    }

    @AfterEach
    void forgetTheSeedIfAnythingChanged() {
        if (!state().equals(seededState)) {
            seededState = null;
        }
    }

    // --- US-7: canonical UUIDs ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "qa AC-7.1 {0} {1} with {2} is malformed")
    @CsvSource(delimiter = '|', value = {
            "ana | GET    | /api/orgs/{x}                              | braces",
            "ana | GET    | /api/orgs/{x}                              | urn",
            "ana | GET    | /api/orgs/{x}                              | no hyphens",
            "ana | GET    | /api/orgs/{x}                              | plus sign",
            "ana | GET    | /api/orgs/{x}                              | 37 characters",
            "ana | GET    | /api/orgs/{x}                              | 35 characters",
            "ana | GET    | /api/orgs/{x}                              | hyphens moved",
            "ana | GET    | /api/orgs/{x}                              | non-hex digit",
            "ana | GET    | /api/orgs/{x}                              | full-width digit",
            "ana | GET    | {acme-projects}/{x}                        | braces",
            "ana | GET    | {acme-projects}/{x}                        | no hyphens",
            "hal | GET    | /internal/projects/{x}/access              | urn",
            "hal | GET    | /internal/projects/{x}/access              | hyphens moved",
            "ana | DELETE | /api/orgs/{acme}/members/{x}               | no hyphens",
            "ana | DELETE | /api/orgs/{acme}/invitations/{x}           | braces",
            "cai | DELETE | {acme-projects}/{checkout}/members/{x}     | no hyphens",
            "ana | GET    | /api/orgs/{x}                              | leading space",
            "ana | GET    | /api/orgs/{x}                              | trailing space",
            "ana | GET    | /api/orgs/{x}                              | leading tab",
            "ana | GET    | /api/orgs/{x}                              | trailing tab",
            "ana | GET    | /api/orgs/{x}                              | trailing no-break space",
            "ana | GET    | {acme-projects}/{x}                        | trailing space",
            "hal | GET    | /internal/projects/{x}/access              | leading space",
            "hal | GET    | /internal/projects/{x}/access              | trailing tab",
            "ana | DELETE | /api/orgs/{acme}/members/{x}               | leading space",
            "cai | DELETE | {acme-projects}/{checkout}/members/{x}     | trailing space"})
    @Tag("AC-7.1")
    void nonCanonicalUuidFormsAreMalformed(String actor, String method, String template, String form)
            throws Exception {
        // Given: the form is applied to an existing id, so a lenient parser would find the resource
        UUID target = template.startsWith("/api/orgs/{x}") ? projects.acme().orgId()
                : template.contains("/members/") ? projects.hal().getId()
                : template.contains("/invitations/") ? UUID.randomUUID()
                : projects.checkout();
        String id = switch (form) {
            case "braces" -> "%7B" + target + "%7D";
            case "urn" -> "urn:uuid:" + target;
            case "no hyphens" -> target.toString().replace("-", "");
            case "plus sign" -> "+" + target;
            case "37 characters" -> target + "0";
            case "35 characters" -> target.toString().substring(1);
            case "hyphens moved" -> target.toString().replace("-", "").replaceFirst(
                    "(.{9})(.{3})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5");
            case "non-hex digit" -> "g" + target.toString().substring(1);
            case "full-width digit" -> "%EF%BC%91" + target.toString().substring(1);
            case "leading space" -> "%20" + target;
            case "trailing space" -> target + "%20";
            case "leading tab" -> "%09" + target;
            case "trailing tab" -> target + "%09";
            case "trailing no-break space" -> target + "%C2%A0";
            default -> throw new IllegalArgumentException(form);
        };
        String path = template.replace("{acme-projects}", projects(projects.acme().orgId()))
                .replace("{acme}", projects.acme().orgId().toString())
                .replace("{checkout}", projects.checkout().toString())
                .replace("{x}", id);
        List<Object> before = state();

        // When
        URI uri = URI.create(path);
        MvcResult result = calls.as(actor, method.equals("GET") ? get(uri) : delete(uri));

        // Then
        assertProblem(result, 400, "MALFORMED_REQUEST", path);
        assertThat(state()).as("nothing was stored").isEqualTo(before);
    }

    @Test
    @Tag("AC-7.2")
    @DisplayName("qa AC-7.2: mixed-case canonical ids identify the resource and are answered in lower case")
    void mixedCaseCanonicalIdsIdentifyTheResource() throws Exception {
        StringBuilder mixed = new StringBuilder();
        String id = projects.checkout().toString();
        for (int i = 0; i < id.length(); i++) {
            mixed.append(i % 2 == 0 ? Character.toUpperCase(id.charAt(i)) : id.charAt(i));
        }

        MvcResult result = calls.as("hal", get("/internal/projects/" + mixed + "/access"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.projectId")).isEqualTo(id);
    }

    // --- US-6: strict JSON types and null ------------------------------------------------------------------------

    @ParameterizedTest(name = "qa AC-6.3 {0} {1} = {2} is malformed")
    @CsvSource(delimiter = '|', value = {
            "anonymous | REGISTER | email    | 1.0", "anonymous | REGISTER | password | false",
            "anonymous | REGISTER | fullName | 0", "anonymous | LOGIN | email | true",
            "ana       | ORGS     | name     | 1.5", "ana | ORGS | name | 1e3", "ana | ORGS | name | -0",
            "ana       | ORGS     | name     | 99999999999999999999999999",
            "ana       | PROJECTS | name     | 0.0", "cai | RENAME | name | -1",
            "ben       | INVITE   | email    | 1e2", "anonymous | ACCEPT | fullName | true"})
    @Tag("AC-6.3")
    void numbersAndBooleansAreMalformedInEveryStringField(String actor, String request, String field, String json)
            throws Exception {
        List<Object> before = state();

        MvcResult result = perform(actor, request, body(request, field, json));

        assertProblem(result, 400, "MALFORMED_REQUEST", path(request));
        assertThat(state()).as("nothing was stored").isEqualTo(before);
    }

    @ParameterizedTest(name = "qa AC-6.4 {0} {1} = null is a validation error for {1}")
    @CsvSource(delimiter = '|', value = {
            "anonymous | REGISTER | email", "anonymous | REGISTER | password", "anonymous | REGISTER | fullName",
            "anonymous | LOGIN    | email", "anonymous | LOGIN    | password", "ana | PROJECTS | name",
            "cai       | RENAME   | name", "ben | INVITE | email", "anonymous | ACCEPT | password",
            "anonymous | ACCEPT   | fullName"})
    @Tag("AC-6.4")
    void nullIsAValidationErrorInEveryStringField(String actor, String request, String field) throws Exception {
        List<Object> before = state();

        MvcResult result = perform(actor, request, body(request, field, "null"));

        DocumentContext problem = assertProblem(result, 400, "VALIDATION_FAILED", path(request));
        assertThat(problem.read("$.errors[*].field", List.class)).contains(field);
        assertThat(state()).as("nothing was stored").isEqualTo(before);
    }

    // --- US-6: code-point lengths --------------------------------------------------------------------------------

    @ParameterizedTest(name = "qa AC-6.2 {0} {1} = {2}: {3}")
    @CsvSource(delimiter = '|', value = {
            "ana       | ORGS     | name     | 100 Deseret letters          | 201",
            "ana       | ORGS     | name     | 101 Deseret letters          | 400",
            "ana       | PROJECTS | name     | e and 99 combining accents   | 201",
            "ana       | PROJECTS | name     | 2 spaces and 99 letters      | 400",
            "cai       | RENAME   | name     | 100 emoji                    | 200",
            "cai       | RENAME   | name     | 101 emoji                    | 400",
            "anonymous | ACCEPT   | fullName | 100 emoji                    | 201",
            "anonymous | ACCEPT   | fullName | 101 emoji                    | 400"})
    @Tag("AC-6.2")
    void nameLimitsCountCodePointsInEveryNameField(String actor, String request, String field, String value,
                                                   int status) throws Exception {
        // Given
        String name = switch (value) {
            case "100 Deseret letters" -> DESERET.repeat(100);
            case "101 Deseret letters" -> DESERET.repeat(101);
            case "e and 99 combining accents" -> "e" + "\u0301".repeat(99);
            case "2 spaces and 99 letters" -> "  " + "a".repeat(99);
            case "100 emoji" -> EMOJI.repeat(100);
            case "101 emoji" -> EMOJI.repeat(101);
            default -> throw new IllegalArgumentException(value);
        };
        List<Object> before = state();

        // When
        MvcResult result = perform(actor, request, body(request, field, json(name)));

        // Then
        if (status == 400) {
            DocumentContext problem = assertProblem(result, 400, "VALIDATION_FAILED", path(request));
            assertThat(problem.read("$.errors[*].field", List.class)).containsExactly(field);
            assertThat(state()).as("nothing was stored").isEqualTo(before);
            return;
        }
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        String stored = switch (request) {
            case "ORGS" -> organizationRepository.findById(UUID.fromString(JsonPath.read(
                    result.getResponse().getContentAsString(), "$.id"))).orElseThrow().getName();
            case "PROJECTS", "RENAME" -> projectRepository.findById(UUID.fromString(JsonPath.read(
                    result.getResponse().getContentAsString(), "$.id"))).orElseThrow().getName();
            default -> userRepository.findByEmail("kit@example.com").orElseThrow().getFullName();
        };
        assertThat(stored).isEqualTo(name);
    }

    // --- US-6 AC-6.1: every Unicode-blank code point -------------------------------------------------------------

    @Test
    @Tag("AC-6.1")
    @DisplayName("qa AC-6.1: an organization name made of any single Unicode-blank code point, or of a mix of them, "
            + "is a validation error")
    void everyUnicodeBlankCodePointIsBlank() throws Exception {
        List<String> blanks = new ArrayList<>();
        for (int c = 0; c <= 0xFFFF; c++) {
            if ((Character.isWhitespace(c) || Character.isSpaceChar(c)) && !Character.isISOControl(c)) {
                blanks.add(Character.toString(c));
            }
        }
        for (int c : new int[]{0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF}) {
            blanks.add(Character.toString(c));
        }
        blanks.add("\u00A0\u200B\u3000\u202F\uFEFF");
        assertThat(blanks).hasSizeGreaterThan(20);
        long organizations = organizationRepository.count();

        SoftAssertions softly = new SoftAssertions();
        for (String blank : blanks) {
            MvcResult result = calls.as("ana", post("/api/orgs"), "{\"name\":" + json(blank) + "}");
            softly.assertThat(result.getResponse().getStatus()).as(codePoints(blank)).isEqualTo(400);
            softly.assertThat(result.getResponse().getContentAsString()).as(codePoints(blank))
                    .contains("\"VALIDATION_FAILED\"", "\"field\":\"name\"");
        }
        softly.assertAll();
        assertThat(organizationRepository.count()).isEqualTo(organizations);
    }

    // --- US-5: every control character, lone surrogates ----------------------------------------------------------

    @Test
    @Tag("AC-5.1")
    @DisplayName("qa AC-5.1: every C0 and C1 control character inside an organization name and a full name is a "
            + "validation error")
    void everyControlCharacterIsRejected() throws Exception {
        List<Object> before = state();
        SoftAssertions softly = new SoftAssertions();
        for (int c = 0; c <= 0x9F; c++) {
            if (!Character.isISOControl(c)) {
                continue;
            }
            String name = "Org" + (char) c + "QA";
            MvcResult org = calls.as("ana", post("/api/orgs"), "{\"name\":" + json(name) + "}");
            softly.assertThat(org.getResponse().getStatus()).as("organization name with " + codePoints(name))
                    .isEqualTo(400);
            MvcResult register = perform("anonymous", "REGISTER", body("REGISTER", "fullName", json(name)));
            softly.assertThat(register.getResponse().getContentAsString())
                    .as("full name with " + codePoints(name)).contains("\"field\":\"fullName\"");
        }
        softly.assertAll();
        assertThat(state()).as("nothing was stored").isEqualTo(before);
    }

    @ParameterizedTest(name = "qa AC-5.1/5.2 project name {0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "U+001F then Checkout | 409", "Checkout then U+0009 U+000A | 409",
            "U+007F then Checkout | 400", "U+0085 then Checkout | 400", "Checkout then U+009F | 400"})
    @Tag("AC-5.2")
    void onlyWhatTrimRemovesIsIgnoredInProjectNames(String value, int status) throws Exception {
        // Given: String.trim() removes code points up to U+0020 only, so DEL and C1 controls stay in the stored value
        String name = switch (value) {
            case "U+001F then Checkout" -> "\u001FCheckout";
            case "Checkout then U+0009 U+000A" -> "Checkout\t\n";
            case "U+007F then Checkout" -> "\u007FCheckout";
            case "U+0085 then Checkout" -> "\u0085Checkout";
            case "Checkout then U+009F" -> "Checkout\u009F";
            default -> throw new IllegalArgumentException(value);
        };
        String acmeProjects = projects(projects.acme().orgId());
        List<Object> before = state();

        // When
        MvcResult result = calls.as("ana", post(acmeProjects), "{\"name\":" + json(name) + "}");

        // Then
        assertProblem(result, status, status == 409 ? "PROJECT_NAME_TAKEN" : "VALIDATION_FAILED", acmeProjects);
        assertThat(state()).isEqualTo(before);
    }

    @ParameterizedTest(name = "qa AC-5.1 {0} {1} with {2} is a validation error")
    @CsvSource(delimiter = '|', value = {
            "ana       | ORGS     | name     | a lone high surrogate at the end",
            "ana       | ORGS     | name     | a lone low surrogate at the start",
            "ana       | ORGS     | name     | a reversed pair",
            "ana       | ORGS     | name     | two high surrogates",
            "ana       | PROJECTS | name     | a lone high surrogate at the end",
            "cai       | RENAME   | name     | a reversed pair",
            "anonymous | REGISTER | fullName | two high surrogates",
            "anonymous | ACCEPT   | fullName | a lone low surrogate at the start",
            "anonymous | REGISTER | email    | a lone high surrogate in the local part",
            "ben       | INVITE   | email    | a reversed pair in the local part",
            "anonymous | LOGIN    | email    | a lone low surrogate in the local part",
            "anonymous | REGISTER | email    | a C1 control in the local part",
            "ben       | INVITE   | email    | a C1 control in the local part"})
    @Tag("AC-5.1")
    void loneSurrogatesAreRejectedInEveryPosition(String actor, String request, String field, String value)
            throws Exception {
        // Given
        String text = switch (value) {
            case "a lone high surrogate at the end" -> "Name QA\uD83D";
            case "a lone low surrogate at the start" -> "\uDE00Name QA";
            case "a reversed pair" -> "Name\uDE00\uD83DQA";
            case "two high surrogates" -> "Name\uD83D\uD83DQA";
            case "a lone high surrogate in the local part" -> "fay\uD83D@example.com";
            case "a reversed pair in the local part" -> "fay\uDE00\uD83D@example.com";
            case "a lone low surrogate in the local part" -> "ana\uDE00@example.com";
            case "a C1 control in the local part" -> "fay\u0085@example.com";
            default -> throw new IllegalArgumentException(value);
        };
        List<Object> before = state();

        // When
        MvcResult result = perform(actor, request, body(request, field, json(text)));

        // Then
        DocumentContext problem = assertProblem(result, 400, "VALIDATION_FAILED", path(request));
        assertThat(problem.read("$.errors[*].field", List.class)).contains(field);
        assertThat(state()).as("nothing was stored").isEqualTo(before);
    }

    @ParameterizedTest(name = "qa US-5: a body with {0} is a 400 and stores nothing")
    @CsvSource(delimiter = '|', value = {
            "an overlong NUL (C0 80)         | C080", "an encoded lone surrogate (ED A0 80) | EDA080",
            "a CESU-8 pair (ED A0 BD ED B8 80) | EDA0BDEDB880", "a raw NUL byte | 00",
            "an invalid byte (FF)            | FF", "a truncated sequence (E2 82) | E282"})
    @Tag("AC-5.1")
    void bodiesThatArentValidUtf8AreRejected(String description, String hex) throws Exception {
        // Given
        byte[] prefix = "{\"name\":\"Org".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "QA\"}".getBytes(StandardCharsets.UTF_8);
        byte[] middle = new byte[hex.length() / 2];
        for (int i = 0; i < middle.length; i++) {
            middle[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        byte[] body = new byte[prefix.length + middle.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(middle, 0, body, prefix.length, middle.length);
        System.arraycopy(suffix, 0, body, prefix.length + middle.length, suffix.length);
        List<Object> before = state();

        // When
        MvcResult result = mockMvc.perform(post("/api/orgs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(calls.tokenOf("ana")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();

        // Then
        assertThat(result.getResponse().getStatus()).as(description).isEqualTo(400);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code"))
                .isIn("MALFORMED_REQUEST", "VALIDATION_FAILED");
        assertThat(state()).as("nothing was stored").isEqualTo(before);
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private MvcResult perform(String actor, String request, String body) throws Exception {
        MockHttpServletRequestBuilder builder = request.equals("RENAME") ? patch(path(request)) : post(path(request));
        if (actor.equals("anonymous")) {
            return mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        }
        return calls.as(actor, builder, body);
    }

    /**
     * A valid body for the request, in which {@code field} is the raw JSON value.
     */
    private static String body(String request, String field, String json) {
        String email = field.equals("email") ? json : "\"fay@example.com\"";
        String password = field.equals("password") ? json : "\"Secret123!\"";
        String fullName = field.equals("fullName") ? json : "\"Fay Lowe\"";
        return switch (request) {
            case "REGISTER" -> "{\"email\":" + email + ",\"password\":" + password + ",\"fullName\":" + fullName + "}";
            case "LOGIN" -> "{\"email\":" + (field.equals("email") ? json : "\"ana@example.com\"")
                    + ",\"password\":" + password + "}";
            case "ORGS", "PROJECTS", "RENAME" -> "{\"name\":" + json + "}";
            case "INVITE" -> "{\"email\":" + email + ",\"role\":\"MEMBER\"}";
            case "ACCEPT" -> "{\"password\":" + password + ",\"fullName\":" + fullName + "}";
            default -> throw new IllegalArgumentException(request);
        };
    }

    private String path(String request) {
        return switch (request) {
            case "REGISTER" -> "/api/auth/register";
            case "LOGIN" -> "/api/auth/login";
            case "ORGS" -> "/api/orgs";
            case "PROJECTS" -> projects(projects.acme().orgId());
            case "RENAME" -> project(projects.acme().orgId(), projects.checkout());
            case "INVITE" -> "/api/orgs/" + projects.acme().orgId() + "/invitations";
            case "ACCEPT" -> "/api/auth/invitations/" + kitToken + "/accept";
            default -> throw new IllegalArgumentException(request);
        };
    }

    /**
     * The text as a JSON string literal, with every non-ASCII or control UTF-16 unit escaped.
     */
    private static String json(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7E) {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String codePoints(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(c -> out.append(String.format(Locale.ROOT, "U+%04X ", c)));
        return out.toString().trim();
    }

    private List<Object> state() {
        return List.of(userRepository.count(), organizationRepository.count(), orgMembershipRepository.count(),
                projectRepository.count(), projectMembershipRepository.count(), invitationRepository.count(),
                projectRepository.findById(projects.checkout()).orElseThrow().getName(),
                jdbc.queryForObject("SELECT count(*) FROM invitations WHERE status = 'PENDING'", Long.class));
    }
}
