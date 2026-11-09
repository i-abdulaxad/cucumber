package org.scenarioservice.qa;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.ScenarioFixture.User;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.create;
import static org.scenarioservice.support.FeatureCalls.created;
import static org.scenarioservice.support.FeatureCalls.features;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.json;
import static org.scenarioservice.support.FeatureCalls.perform;
import static org.scenarioservice.support.FeatureCalls.view;
import static org.scenarioservice.support.ProblemAssertions.assertProblem;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.ANA;
import static org.scenarioservice.support.ScenarioFixture.BILLING;
import static org.scenarioservice.support.ScenarioFixture.CAI;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.DAN;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.scenarioservice.support.ScenarioFixture.HAL;
import static org.scenarioservice.support.ScenarioFixture.IVY;
import static org.scenarioservice.support.ScenarioFixture.LEGACY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * QA (adversarial, D3–D5, AC-2.4, AC-7.5): stale roles after a change, cross-project access and IDOR on
 * {@code projectId}, {@code featureId} and {@code {version}}. Each request must ask C5 once, about the project in its
 * path, and a feature of another project must look exactly like an unknown one.
 */
@ScenarioServiceIntegrationTest
class QaAuthorizationIdorTest {

    private static final String PAYMENT_PATH = "features/checkout/payment.feature";
    private static final String INVOICE_PATH = "features/invoice.feature";

    private final AccessCheckStub stub = AccessCheckStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    private UUID payment;
    private UUID invoice;

    @BeforeEach
    void background() throws Exception {
        data.reset();
        stub.reset();
        payment = created(mockMvc, GUS, CHECKOUT, PAYMENT_PATH, GherkinSources.PAYMENT);
        invoice = created(mockMvc, ANA, BILLING, INVOICE_PATH, GherkinSources.INVOICE);
        stub.reset();
    }

    @Test
    @Tag("AC-7.5")
    @DisplayName("Stale role: every operation follows each role change on the very next request, one C5 call each")
    void everyOperationFollowsTheCurrentRole() throws Exception {
        for (String role : List.of("READER", "TESTER")) {
            stub.role(GUS.id(), CHECKOUT, role);
            assertReadsWork(GUS);
            assertWritesAre(GUS, 403, "FORBIDDEN");
        }
        stub.role(GUS.id(), CHECKOUT, null);
        assertReadsAre(GUS, 403, "FORBIDDEN");
        assertWritesAre(GUS, 403, "FORBIDDEN");
        stub.role(GUS.id(), CHECKOUT, "ADMIN");
        assertReadsWork(GUS);
        MvcResult archived = perform(mockMvc, GUS, post(features(CHECKOUT) + "/" + payment + "/archive"));
        assertThat(archived.getResponse().getStatus()).isEqualTo(200);
        stub.role(GUS.id(), CHECKOUT, "READER");
        assertProblem(perform(mockMvc, GUS, post(features(CHECKOUT) + "/" + payment + "/restore")), 403,
                "FORBIDDEN");
        assertThat(JsonPath.<String>read(body(view(mockMvc, GUS, CHECKOUT, payment)), "$.archivedAt")).isNotNull();
    }

    @Test
    @Tag("AC-7.4")
    @DisplayName("A token auth-service starts rejecting (suspended user) is 401 on reads and writes, nothing changes")
    void aSuspendedUserIsRejectedEverywhere() throws Exception {
        stub.rejectToken(GUS.id());

        for (MockHttpServletRequestBuilder request : reads(CHECKOUT, payment)) {
            assertProblem(perform(mockMvc, GUS, request), 401, "UNAUTHENTICATED");
        }
        for (MockHttpServletRequestBuilder request : writes(CHECKOUT, payment)) {
            assertProblem(perform(mockMvc, GUS, request), 401, "UNAUTHENTICATED");
        }
        assertThat(currentState(CHECKOUT, payment)).containsEntry("currentVersion", 1).containsEntry("archivedAt",
                null);
    }

    @ParameterizedTest(name = "IDOR: Ana (ADMIN in both) reaches Billing's feature through Checkout with {0}")
    @ValueSource(strings = {"view", "versions", "version 1", "test-cases", "save", "archive", "restore"})
    @Tag("AC-2.4")
    void aFeatureOfAnotherProjectIsNotFound(String operation) throws Exception {
        MvcResult result = perform(mockMvc, ANA, request(operation, CHECKOUT, invoice, INVOICE_PATH));

        Map<String, Object> problem = assertProblem(result, 404, "FEATURE_NOT_FOUND");
        assertThat(body(result)).doesNotContain("Invoice", INVOICE_PATH, "Send an invoice");
        assertThat(problem).doesNotContainKey("currentVersion");
        assertThat(stub.received()).singleElement().satisfies(received -> assertThat(received.path())
                .isEqualTo("/internal/projects/" + CHECKOUT + "/access"));
        assertThat(currentState(BILLING, invoice)).containsEntry("currentVersion", 1).containsEntry("archivedAt",
                null).containsEntry("path", INVOICE_PATH);
    }

    @ParameterizedTest(name = "IDOR: an unknown feature id and another project's id answer the same for {0}")
    @ValueSource(strings = {"view", "versions", "version 1", "test-cases", "save", "archive", "restore"})
    @Tag("AC-2.4")
    void anotherProjectsFeatureLooksLikeAnUnknownOne(String operation) throws Exception {
        MvcResult foreign = perform(mockMvc, ANA, request(operation, CHECKOUT, invoice, INVOICE_PATH));
        MvcResult unknown = perform(mockMvc, ANA, request(operation, CHECKOUT, UUID.randomUUID(), INVOICE_PATH));

        Map<String, Object> foreignProblem = JsonPath.read(body(foreign), "$");
        Map<String, Object> unknownProblem = JsonPath.read(body(unknown), "$");
        foreignProblem.remove("instance");
        unknownProblem.remove("instance");
        assertThat(foreign.getResponse().getStatus()).isEqualTo(unknown.getResponse().getStatus());
        assertThat(foreignProblem).isEqualTo(unknownProblem);
    }

    @ParameterizedTest(name = "Cai (Checkout ADMIN, no Billing access) and {0} of Billing's feature")
    @ValueSource(strings = {"view", "save", "archive"})
    @DisplayName("A caller without access to the owning project learns nothing, through either project's path")
    void noAccessToTheOwningProject(String operation) throws Exception {
        assertProblem(perform(mockMvc, CAI, request(operation, CHECKOUT, invoice, INVOICE_PATH)), 404,
                "FEATURE_NOT_FOUND");
        assertProblem(perform(mockMvc, CAI, request(operation, BILLING, invoice, INVOICE_PATH)), 403, "FORBIDDEN");
        assertThat(currentState(BILLING, invoice)).containsEntry("currentVersion", 1).containsEntry("archivedAt",
                null);
    }

    @Test
    @DisplayName("D16 order: a TESTER writing to another project's feature through Checkout gets 403, not 404")
    void theRoleIsCheckedBeforeTheFeatureExists() throws Exception {
        assertProblem(perform(mockMvc, HAL, request("save", CHECKOUT, invoice, INVOICE_PATH)), 403, "FORBIDDEN");
        assertProblem(perform(mockMvc, HAL, request("archive", CHECKOUT, UUID.randomUUID(), INVOICE_PATH)), 403,
                "FORBIDDEN");
    }

    @Test
    @DisplayName("Projects don't share tags: Billing's tags neither appear in nor filter Checkout")
    void tagsDontLeakAcrossProjects() throws Exception {
        String catalogue = body(perform(mockMvc, ANA, get("/api/projects/" + CHECKOUT + "/tags")));
        assertThat(JsonPath.<List<String>>read(catalogue, "$[*].name")).doesNotContain("@billing");
        assertThat(JsonPath.<List<Object>>read(body(perform(mockMvc, ANA, get(features(CHECKOUT))
                .queryParam("tag", "@billing"))), "$")).isEmpty();
    }

    @Test
    @DisplayName("A project id the caller may write (stub override) gets its own content; the path's id is stored")
    void contentBelongsToThePathsProject() throws Exception {
        UUID fresh = UUID.randomUUID();
        stub.role(GUS.id(), fresh, "MEMBER");

        MvcResult result = create(mockMvc, GUS, fresh, PAYMENT_PATH, GherkinSources.PAYMENT);

        assertThat(result.getResponse().getStatus()).as("the same path is free in another project").isEqualTo(201);
        assertThat(JsonPath.<String>read(body(result), "$.projectId")).isEqualTo(fresh.toString());
        assertThat(JsonPath.<List<String>>read(body(perform(mockMvc, GUS, get(features(CHECKOUT)))), "$[*].id"))
                .containsExactly(payment.toString());
    }

    @Test
    @DisplayName("Ids in either letter case address the same feature and the same project")
    void upperCaseIdsAddressTheSameFeature() throws Exception {
        String upper = "/api/projects/" + CHECKOUT.toString().toUpperCase(Locale.ROOT) + "/features/"
                + payment.toString().toUpperCase(Locale.ROOT);

        MvcResult result = perform(mockMvc, DAN, get(upper));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(body(result), "$.id")).isEqualTo(payment.toString());
        assertThat(stub.received()).singleElement().satisfies(received -> assertThat(received.path())
                .isEqualTo("/internal/projects/" + CHECKOUT + "/access"));
    }

    @Test
    @Tag("AC-7.7")
    @DisplayName("An archived project: an ADMIN's restore and save are 409 PROJECT_ARCHIVED; reads of every kind work")
    void anArchivedProjectIsReadOnlyForAdminsToo() throws Exception {
        stub.answer(200, AccessCheckStub.accessBody(LEGACY, "ADMIN").replace("\"archived\":true",
                "\"archived\":false"));
        UUID legacy = created(mockMvc, ANA, LEGACY, "features/address.feature", GherkinSources.ADDRESS);
        stub.reset();

        for (MockHttpServletRequestBuilder request : writes(LEGACY, legacy)) {
            assertProblem(perform(mockMvc, ANA, request), 409, "PROJECT_ARCHIVED");
        }
        for (MockHttpServletRequestBuilder request : reads(LEGACY, legacy)) {
            assertThat(perform(mockMvc, DAN, request).getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("A3: an invalid body is 400 before the access check, so a caller without access learns nothing more")
    void validationRunsBeforeTheAccessCheck() throws Exception {
        MvcResult result = perform(mockMvc, IVY, post(features(CHECKOUT)).contentType(MediaType.APPLICATION_JSON)
                .content(json(fields("path", "/absolute.feature", "source", GherkinSources.ADDRESS))));

        assertProblem(result, 400, "VALIDATION_FAILED");
        assertThat(stub.received()).isEmpty();
    }

    private void assertReadsWork(User user) throws Exception {
        for (MockHttpServletRequestBuilder request : reads(CHECKOUT, payment)) {
            int before = stub.received().size();
            assertThat(perform(mockMvc, user, request).getResponse().getStatus()).isEqualTo(200);
            assertThat(stub.received()).as("one C5 call per request, no cache").hasSize(before + 1);
        }
    }

    private void assertReadsAre(User user, int status, String code) throws Exception {
        for (MockHttpServletRequestBuilder request : reads(CHECKOUT, payment)) {
            assertProblem(perform(mockMvc, user, request), status, code);
        }
    }

    private void assertWritesAre(User user, int status, String code) throws Exception {
        for (MockHttpServletRequestBuilder request : writes(CHECKOUT, payment)) {
            int before = stub.received().size();
            assertProblem(perform(mockMvc, user, request), status, code);
            assertThat(stub.received()).hasSize(before + 1);
        }
        assertThat(currentState(CHECKOUT, payment)).containsEntry("currentVersion", 1).containsEntry("archivedAt",
                null).containsEntry("path", PAYMENT_PATH);
        assertThat(data.count("features")).isEqualTo(2);
    }

    /**
     * The feature as Ana (ADMIN of Checkout and Billing in the fixture) sees it.
     */
    private Map<String, Object> currentState(UUID projectId, UUID featureId) throws Exception {
        return JsonPath.read(body(view(mockMvc, ANA, projectId, featureId)), "$");
    }

    private static List<MockHttpServletRequestBuilder> reads(UUID projectId, UUID featureId) {
        String feature = features(projectId) + "/" + featureId;
        return List.of(get(features(projectId)), get(feature), get(feature + "/versions"),
                get(feature + "/versions/1"), get(feature + "/test-cases"), get("/api/projects/" + projectId + "/tags"));
    }

    private static List<MockHttpServletRequestBuilder> writes(UUID projectId, UUID featureId) {
        String feature = features(projectId) + "/" + featureId;
        return List.of(
                post(features(projectId)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(fields("path", "features/new.feature", "source", GherkinSources.ADDRESS))),
                put(feature).contentType(MediaType.APPLICATION_JSON).content(json(fields("path",
                        "features/moved.feature", "source", GherkinSources.PAYMENT_V2, "baseVersion", 1))),
                post(feature + "/archive"), post(feature + "/restore"));
    }

    private static MockHttpServletRequestBuilder request(String operation, UUID projectId, UUID featureId,
                                                         String path) {
        String feature = features(projectId) + "/" + featureId;
        return switch (operation) {
            case "view" -> get(feature);
            case "versions" -> get(feature + "/versions");
            case "version 1" -> get(feature + "/versions/1");
            case "test-cases" -> get(feature + "/test-cases");
            case "save" -> put(feature).contentType(MediaType.APPLICATION_JSON).content(json(fields("path", path,
                    "source", GherkinSources.ADDRESS, "baseVersion", 1)));
            case "archive" -> post(feature + "/archive");
            default -> post(feature + "/restore");
        };
    }
}
