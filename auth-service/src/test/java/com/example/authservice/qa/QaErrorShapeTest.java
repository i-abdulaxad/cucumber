package com.example.authservice.qa;

import com.example.authservice.enums.InvitationStatus;
import com.example.authservice.enums.OrganizationRole;
import com.example.authservice.model.Organization;
import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.OrganizationRepository;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AcmeFixture.Acme;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static com.example.authservice.qa.QaSupport.as;
import static com.example.authservice.qa.QaSupport.json;
import static com.example.authservice.qa.QaSupport.seedInvitation;
import static com.example.authservice.qa.QaSupport.seedPendingInvitation;
import static com.example.authservice.support.ApiCalls.loginBody;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * qa D2 catalogue: one request for every row of the status and code table that a single request can produce
 * (CONFLICT needs a race, see QaConcurrencyTest; INTERNAL_ERROR is AC-1.3). Each answer must be a complete Problem with
 * the right status and code, no internals, the caller's valid request ID, and no X-Request-Id or CORS header.
 */
@AuthServiceIntegrationTest
class QaErrorShapeTest {

    private static final String REQUEST_ID = "qa-rid.1_A";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private InvitationRepository invitationRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    private Acme acme;

    @BeforeEach
    void seedAcme() {
        acme = fixture.seed();
    }

    enum Row {
        VALIDATION_FAILED(400), MALFORMED_REQUEST(400), UNAUTHENTICATED(401), INVALID_CREDENTIALS(401),
        FORBIDDEN(403), INVITATION_EMAIL_MISMATCH(403), NOT_FOUND(404), INVITATION_NOT_FOUND(404),
        MEMBER_NOT_FOUND(404), METHOD_NOT_ALLOWED(405), NOT_ACCEPTABLE(406), EMAIL_ALREADY_REGISTERED(409),
        ALREADY_MEMBER(409), INVITATION_PENDING(409), SEAT_LIMIT_REACHED(409), LAST_OWNER(409),
        INVITATION_NOT_PENDING(409), INVITATION_EXPIRED(410), INVITATION_REVOKED(410),
        INVITATION_ALREADY_ACCEPTED(410), UNSUPPORTED_MEDIA_TYPE(415), ROLE_CEILING(422), SELF_ROLE_CHANGE(422);

        final int status;

        Row(int status) {
            this.status = status;
        }
    }

    @ParameterizedTest(name = "D2 (qa) {0} is a complete Problem with the request ID")
    @EnumSource(Row.class)
    @Tag("AC-1.7")
    void everyErrorCodeIsACompleteProblem(Row row) throws Exception {
        // Given
        Scenario scenario = scenario(row);

        // When
        MvcResult result = mockMvc.perform(scenario.request().header("X-Request-Id", REQUEST_ID)
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")).andReturn();

        // Then
        DocumentContext problem = assertProblem(result, row.status, row.name(), scenario.path());
        assertThat(problem.read("$.requestId", String.class)).isEqualTo(REQUEST_ID);
        Map<String, Object> members = problem.read("$");
        assertThat(members.keySet()).isSubsetOf("type", "title", "status", "detail", "instance", "code", "requestId",
                "errors");
        assertThat(result.getResponse().getHeaderNames()).doesNotContain("X-Request-Id")
                .noneSatisfy(name -> assertThat(name).startsWithIgnoringCase("Access-Control-"));
        if (row.status == 401) {
            assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        }
    }

    private record Scenario(String path, MockHttpServletRequestBuilder request) {
    }

    private Scenario scenario(Row row) {
        String orgs = "/api/orgs/" + acme.orgId();
        String invitations = orgs + "/invitations";
        String members = orgs + "/members/";
        Organization acmeOrg = organizationRepository.findById(acme.orgId()).orElseThrow();
        return switch (row) {
            case VALIDATION_FAILED -> new Scenario("/api/auth/register",
                    json(post("/api/auth/register"), "{\"email\":\"x\",\"password\":\"p\",\"fullName\":\"\"}"));
            case MALFORMED_REQUEST -> new Scenario("/api/auth/login", json(post("/api/auth/login"), "{"));
            case UNAUTHENTICATED -> new Scenario("/api/orgs", get("/api/orgs"));
            case INVALID_CREDENTIALS -> new Scenario("/api/auth/login",
                    json(post("/api/auth/login"), loginBody("ana@example.com", "Wrong123!")));
            case FORBIDDEN -> new Scenario(invitations, as(get(invitations), acme.dan()));
            case INVITATION_EMAIL_MISMATCH -> {
                seedPendingInvitation(invitationRepository, acmeOrg, acme.ben(), "fay@example.com",
                        OrganizationRole.MEMBER, "inv-shape-QA");
                yield new Scenario(accept("inv-shape-QA"), as(post(accept("inv-shape-QA")), acme.eve()));
            }
            case NOT_FOUND -> new Scenario(orgs + "/qa-nothing", as(get(orgs + "/qa-nothing"), acme.ana()));
            case INVITATION_NOT_FOUND ->
                    new Scenario(accept("inv-none-QA"), as(post(accept("inv-none-QA")), acme.eve()));
            case MEMBER_NOT_FOUND -> new Scenario(members + acme.eve().getId(),
                    as(delete(members + acme.eve().getId()), acme.ana()));
            case METHOD_NOT_ALLOWED -> new Scenario(orgs, as(put(orgs), acme.ana()));
            case NOT_ACCEPTABLE -> new Scenario("/api/orgs",
                    as(get("/api/orgs"), acme.ana()).accept(MediaType.APPLICATION_XML));
            case EMAIL_ALREADY_REGISTERED -> new Scenario("/api/auth/register", json(post("/api/auth/register"),
                    "{\"email\":\"ANA@example.com\",\"password\":\"Secret123!\",\"fullName\":\"Ana\"}"));
            case ALREADY_MEMBER -> new Scenario(invitations, json(as(post(invitations), acme.ana()),
                    "{\"email\":\"dan@example.com\",\"role\":\"READER\"}"));
            case INVITATION_PENDING -> {
                seedPendingInvitation(invitationRepository, acmeOrg, acme.ben(), "fay@example.com",
                        OrganizationRole.MEMBER, "inv-shape-QA");
                yield new Scenario(invitations, json(as(post(invitations), acme.ana()),
                        "{\"email\":\"fay@example.com\",\"role\":\"READER\"}"));
            }
            case SEAT_LIMIT_REACHED -> {
                seedPendingInvitation(invitationRepository, acmeOrg, acme.ben(), "fay@example.com",
                        OrganizationRole.MEMBER, "inv-shape-QA");
                yield new Scenario(invitations, json(as(post(invitations), acme.ana()),
                        "{\"email\":\"gus@example.com\",\"role\":\"READER\"}"));
            }
            case LAST_OWNER -> new Scenario(members + acme.ana().getId(),
                    as(delete(members + acme.ana().getId()), acme.ana()));
            case INVITATION_NOT_PENDING -> {
                var accepted = seedInvitation(invitationRepository, acmeOrg, acme.ben(), "fay@example.com",
                        OrganizationRole.MEMBER, InvitationStatus.ACCEPTED, Instant.now().plus(7, ChronoUnit.DAYS),
                        "inv-shape-QA");
                yield new Scenario(invitations + "/" + accepted.getId(),
                        as(delete(invitations + "/" + accepted.getId()), acme.ben()));
            }
            case INVITATION_EXPIRED ->
                    gone(acmeOrg, InvitationStatus.PENDING, Instant.now().minus(1, ChronoUnit.MINUTES));
            case INVITATION_REVOKED -> gone(acmeOrg, InvitationStatus.REVOKED, Instant.now().plus(1, ChronoUnit.DAYS));
            case INVITATION_ALREADY_ACCEPTED ->
                    gone(acmeOrg, InvitationStatus.ACCEPTED, Instant.now().plus(1, ChronoUnit.DAYS));
            case UNSUPPORTED_MEDIA_TYPE -> new Scenario("/api/auth/register",
                    post("/api/auth/register").contentType(MediaType.TEXT_PLAIN).content("x"));
            case ROLE_CEILING -> new Scenario(members + acme.ana().getId(),
                    json(as(patch(members + acme.ana().getId()), acme.ben()), "{\"role\":\"READER\"}"));
            case SELF_ROLE_CHANGE -> new Scenario(members + acme.cai().getId(),
                    json(as(patch(members + acme.cai().getId()), acme.cai()), "{\"role\":\"ADMIN\"}"));
        };
    }

    private Scenario gone(Organization acmeOrg, InvitationStatus status, Instant expiresAt) {
        seedInvitation(invitationRepository, acmeOrg, acme.ben(), "fay@example.com", OrganizationRole.MEMBER, status,
                expiresAt, "inv-shape-QA");
        return new Scenario(accept("inv-shape-QA"), json(post(accept("inv-shape-QA")),
                "{\"password\":\"Secret123!\",\"fullName\":\"Fay Lin\"}"));
    }

    private static String accept(String token) {
        return "/api/auth/invitations/" + token + "/accept";
    }
}
