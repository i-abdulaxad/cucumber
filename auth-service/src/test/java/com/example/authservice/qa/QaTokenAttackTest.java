package com.example.authservice.qa;

import com.example.authservice.enums.OrganizationRole;
import com.example.authservice.enums.UserStatus;
import com.example.authservice.model.Organization;
import com.example.authservice.repository.InvitationRepository;
import com.example.authservice.repository.OrgMembershipRepository;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.support.AcmeFixture;
import com.example.authservice.support.AcmeFixture.Acme;
import com.example.authservice.support.AuthServiceIntegrationTest;
import com.example.authservice.support.TestJwt;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static com.example.authservice.qa.QaSupport.base64Url;
import static com.example.authservice.qa.QaSupport.seedPendingInvitation;
import static com.example.authservice.qa.QaSupport.signedRaw;
import static com.example.authservice.support.ApiCalls.bearer;
import static com.example.authservice.support.ApiCalls.loginBody;
import static com.example.authservice.support.ApiCalls.tokenOf;
import static com.example.authservice.support.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * qa adversarial authentication checks (US-3, direct calls): forged, malformed and unusual tokens beyond the AC-3.1
 * rows, the Bearer scheme letter case, and tokens of deleted, re-registered or suspended users.
 */
@AuthServiceIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class QaTokenAttackTest {

    private static final String ORGS = "/api/orgs";
    private static final String ERROR_ENTRY = "(?m)^\\S+\\s+ERROR\\s";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AcmeFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgMembershipRepository orgMembershipRepository;

    @Autowired
    private InvitationRepository invitationRepository;

    private Acme acme;

    @BeforeEach
    void seedAcme() {
        acme = fixture.seed();
    }

    private static long inOneHour() {
        return Instant.now().plus(Duration.ofHours(1)).getEpochSecond();
    }

    /**
     * Each row builds an Authorization header value from Ana's id and a valid login token of Ana.
     */
    static Stream<Arguments> forgedAuthorizations() {
        return Stream.of(
                row("HS384 with the shared secret", (id, token) -> bearer(Jwts.builder().subject(id)
                        .expiration(Date.from(Instant.now().plusSeconds(3600)))
                        .signWith(TestJwt.key(), Jwts.SIG.HS384).compact())),
                row("alg None (mixed case), no signature", (id, token) ->
                        bearer(base64Url("{\"alg\":\"None\"}") + "." + token.split("\\.")[1] + ".")),
                row("alg NONE, no signature", (id, token) ->
                        bearer(base64Url("{\"alg\":\"NONE\"}") + "." + token.split("\\.")[1] + ".")),
                row("alg none with the original signature kept", (id, token) ->
                        bearer(base64Url("{\"alg\":\"none\"}") + "." + token.split("\\.")[1] + "."
                                + token.split("\\.")[2])),
                row("header alg is an array", (id, token) ->
                        bearer(signedRaw("{\"alg\":[\"HS512\"]}", "{\"sub\":\"" + id + "\",\"exp\":" + inOneHour() + "}"))),
                row("unknown critical header", (id, token) ->
                        bearer(signedRaw("{\"alg\":\"HS512\",\"crit\":[\"qa\"],\"qa\":1}",
                                "{\"sub\":\"" + id + "\",\"exp\":" + inOneHour() + "}"))),
                row("unencoded payload (b64 false)", (id, token) ->
                        bearer(signedRaw("{\"alg\":\"HS512\",\"b64\":false,\"crit\":[\"b64\"]}",
                                "{\"sub\":\"" + id + "\",\"exp\":" + inOneHour() + "}"))),
                row("unknown zip algorithm", (id, token) ->
                        bearer(signedRaw("{\"alg\":\"HS512\",\"zip\":\"QA\"}",
                                "{\"sub\":\"" + id + "\",\"exp\":" + inOneHour() + "}"))),
                row("exp is a string", (id, token) ->
                        bearer(signedRaw("{\"alg\":\"HS512\"}", "{\"sub\":\"" + id + "\",\"exp\":\"tomorrow\"}"))),
                row("exp missing", (id, token) ->
                        bearer(signedRaw("{\"alg\":\"HS512\"}", "{\"sub\":\"" + id + "\"}"))),
                row("sub is a number", (id, token) ->
                        bearer(signedRaw("{\"alg\":\"HS512\"}", "{\"sub\":123,\"exp\":" + inOneHour() + "}"))),
                row("sub is an array", (id, token) ->
                        bearer(signedRaw("{\"alg\":\"HS512\"}", "{\"sub\":[\"" + id + "\"],\"exp\":" + inOneHour() + "}"))),
                row("payload is a JSON array", (id, token) -> bearer(signedRaw("{\"alg\":\"HS512\"}", "[1]"))),
                row("payload is not JSON", (id, token) -> bearer(signedRaw("{\"alg\":\"HS512\"}", "hello"))),
                row("header is not JSON", (id, token) ->
                        bearer(base64Url("xx") + "." + token.split("\\.")[1] + "." + token.split("\\.")[2])),
                row("nbf in the future", (id, token) -> bearer(Jwts.builder().subject(id)
                        .notBefore(Date.from(Instant.now().plusSeconds(600)))
                        .expiration(Date.from(Instant.now().plusSeconds(3600)))
                        .signWith(TestJwt.key(), Jwts.SIG.HS512).compact())),
                row("five dot-separated parts", (id, token) -> bearer(token + ".a.b")),
                row("signature truncated by one character", (id, token) ->
                        bearer(token.substring(0, token.length() - 1))),
                row("100 KB of garbage", (id, token) -> bearer("a".repeat(100_000))),
                row("two spaces after Bearer", (id, token) -> "Bearer  " + token),
                row("no space after Bearer", (id, token) -> "Bearer" + token),
                row("a tab after Bearer", (id, token) -> "Bearer\t" + token),
                row("a trailing space", (id, token) -> "Bearer " + token + " "),
                row("scheme Token", (id, token) -> "Token " + token),
                row("Bearer twice", (id, token) -> "Bearer Bearer " + token));
    }

    private static Arguments row(String name, BiFunction<String, String, String> header) {
        return Arguments.of(name, header);
    }

    @ParameterizedTest(name = "AC-3.1 (qa) {0} is a 401 Problem without an ERROR log")
    @MethodSource("forgedAuthorizations")
    @Tag("AC-3.1")
    void aForgedOrMalformedTokenIsA401Problem(String row, BiFunction<String, String, String> header,
                                              CapturedOutput output) throws Exception {
        // Given
        String authorization = header.apply(acme.ana().getId().toString(), tokenOf(mockMvc, "ana@example.com"));
        int logLengthBefore = output.getAll().length();

        // When
        MvcResult result = mockMvc.perform(get(ORGS).header(HttpHeaders.AUTHORIZATION, authorization)).andReturn();

        // Then
        assertProblem(result, 401, "UNAUTHENTICATED", ORGS);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(output.getAll().substring(logLengthBefore))
                .doesNotContainPattern(ERROR_ENTRY).doesNotContain("\tat ");
    }

    @Test
    @Tag("AC-3.1")
    @DisplayName("qa: a valid token is not accepted from a query parameter")
    void aTokenInTheQueryStringIsIgnored() throws Exception {
        MvcResult result = mockMvc.perform(get(ORGS).param("access_token", tokenOf(mockMvc, "ana@example.com")))
                .andReturn();

        assertProblem(result, 401, "UNAUTHENTICATED", ORGS);
    }

    @ParameterizedTest(name = "AC-3.3 (qa) scheme \"{0}\" is accepted")
    @ValueSource(strings = {"BEARER", "bEaReR", "bearer"})
    @Tag("AC-3.3")
    void theBearerSchemeIsCaseInsensitive(String scheme) throws Exception {
        MvcResult result = mockMvc.perform(get(ORGS).header(HttpHeaders.AUTHORIZATION,
                scheme + " " + tokenOf(mockMvc, "ana@example.com"))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("Acme QA");
    }

    @Test
    @Tag("AC-3.5")
    @DisplayName("qa: the login token of a user who was then deleted is rejected")
    void theTokenOfADeletedUserIsRejected() throws Exception {
        // Given Cai logs in and is then deleted
        String token = tokenOf(mockMvc, "cai@example.com");
        orgMembershipRepository.delete(
                orgMembershipRepository.findByUserIdAndOrganizationId(acme.cai().getId(), acme.orgId()).orElseThrow());
        userRepository.deleteById(acme.cai().getId());

        // When
        MvcResult result = mockMvc.perform(get(ORGS).header(HttpHeaders.AUTHORIZATION, bearer(token))).andReturn();

        // Then
        assertProblem(result, 401, "UNAUTHENTICATED", ORGS);
    }

    @Test
    @Tag("AC-3.5")
    @DisplayName("qa: after a user is deleted and the email registered again, the old token doesn't act as the new account")
    void anOldTokenDoesntActAsANewAccountWithTheSameEmail() throws Exception {
        // Given Eve logs in, is deleted, and the address is registered again by someone else
        String oldToken = tokenOf(mockMvc, "eve@example.com");
        userRepository.deleteById(acme.eve().getId());
        MvcResult registered = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"eve@example.com\",\"password\":\"Other123!\",\"fullName\":\"New Eve\"}"))
                .andReturn();
        assertThat(registered.getResponse().getStatus()).isEqualTo(201);

        // When
        MvcResult result = mockMvc.perform(get(ORGS).header(HttpHeaders.AUTHORIZATION, bearer(oldToken))).andReturn();

        // Then
        assertProblem(result, 401, "UNAUTHENTICATED", ORGS);
    }

    @Test
    @Tag("AC-3.4")
    @DisplayName("qa: a suspended user's token can't accept an invitation; the caller is treated as anonymous")
    void aSuspendedUsersTokenCantAcceptAnInvitation() throws Exception {
        // Given Cai holds a token and an invitation to Initech, then is suspended
        Organization initech = fixture.organization("Initech QA");
        fixture.member(initech, acme.eve(), OrganizationRole.OWNER);
        seedPendingInvitation(invitationRepository, initech, acme.eve(), "cai@example.com", OrganizationRole.ADMIN,
                "inv-suspended-QA");
        String token = tokenOf(mockMvc, "cai@example.com");
        fixture.setStatus(acme.cai(), UserStatus.SUSPENDED);
        String accept = "/api/auth/invitations/inv-suspended-QA/accept";

        // When: no body (anonymous needs one), then a body (anonymous with an existing email)
        MvcResult withoutBody = mockMvc.perform(post(accept).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andReturn();
        MvcResult withBody = mockMvc.perform(post(accept).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"Secret123!\",\"fullName\":\"Cai Wu\"}")).andReturn();

        // Then
        assertProblem(withoutBody, 400, "VALIDATION_FAILED", accept);
        assertProblem(withBody, 409, "EMAIL_ALREADY_REGISTERED", accept);
        assertThat(orgMembershipRepository.findByUserIdAndOrganizationId(acme.cai().getId(), initech.getId()))
                .isEmpty();
    }

    @Test
    @Tag("AC-2.6")
    @DisplayName("qa: a suspended user's login with a wrong password and with the right one look identical")
    void aSuspendedUsersLoginRevealsNothing() throws Exception {
        // Given
        fixture.setStatus(acme.cai(), UserStatus.SUSPENDED);

        // When
        MvcResult right = login("cai@example.com", AcmeFixture.PASSWORD);
        MvcResult wrong = login("cai@example.com", "Wrong123!");

        // Then
        assertThat(assertProblem(right, 401, "INVALID_CREDENTIALS", "/api/auth/login").read("$.detail", String.class))
                .isEqualTo(assertProblem(wrong, 401, "INVALID_CREDENTIALS", "/api/auth/login")
                        .read("$.detail", String.class));
        assertThat(right.getResponse().getContentAsString()).isEqualTo(wrong.getResponse().getContentAsString());
    }

    @Test
    @Tag("AC-2.4")
    @DisplayName("qa: INVALID_CREDENTIALS also carries the Bearer challenge, and the body never names the email")
    void invalidCredentialsCarriesTheChallenge() throws Exception {
        MvcResult result = login("nobody@example.com", "Wrong123!");

        assertProblem(result, 401, "INVALID_CREDENTIALS", "/api/auth/login");
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("nobody");
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(email, password))).andReturn();
    }
}
