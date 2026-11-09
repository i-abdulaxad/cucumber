package org.scenarioservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureCalls.Raw;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.ScenarioFixture.User;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.controller.CreateFeatureTest.cycle;
import static org.scenarioservice.controller.CreateFeatureTest.errorFields;
import static org.scenarioservice.support.FeatureCalls.created;
import static org.scenarioservice.support.FeatureCalls.features;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.perform;
import static org.scenarioservice.support.FeatureCalls.save;
import static org.scenarioservice.support.FeatureCalls.view;
import static org.scenarioservice.support.ProblemAssertions.assertProblem;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.ANA;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.DAN;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.scenarioservice.support.ScenarioFixture.HAL;
import static org.scenarioservice.support.ScenarioFixture.IVY;
import static org.scenarioservice.support.ScenarioFixture.LEGACY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * US-3 (T9, end to end): save a new version and read the history. Background: Gus has created Payment.
 */
@ScenarioServiceIntegrationTest
class SaveFeatureTest {

    private static final String PATH = "features/checkout/payment.feature";

    private final AccessCheckStub stub = AccessCheckStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    private UUID payment;

    @BeforeEach
    void gusHasCreatedPayment() throws Exception {
        data.reset();
        stub.reset();
        MvcResult created = org.scenarioservice.support.FeatureCalls.create(mockMvc, GUS, CHECKOUT, fields(
                "path", PATH, "source", GherkinSources.PAYMENT, "message", "First draft"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        payment = UUID.fromString(JsonPath.read(body(created), "$.id"));
    }

    @Test
    @Tag("AC-3.1")
    @DisplayName("AC-3.1 Saving changed text creates the next version")
    void savingChangedTextCreatesTheNextVersion() throws Exception {
        MvcResult result = save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source",
                GherkinSources.PAYMENT_V2, "baseVersion", 1, "message", "Add bank transfer"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Map<String, Object>>read(body(result), "$")).containsEntry("currentVersion", 2)
                .containsEntry("updatedBy", GUS.id().toString()).containsEntry("source", GherkinSources.PAYMENT_V2);
        String history = body(perform(mockMvc, GUS, get(features(CHECKOUT) + "/" + payment + "/versions")));
        assertThat(JsonPath.<List<Integer>>read(history, "$[*].version")).containsExactly(2, 1);
        assertThat(JsonPath.<List<String>>read(history, "$[*].message"))
                .containsExactly("Add bank transfer", "First draft");
        String first = body(perform(mockMvc, GUS, get(features(CHECKOUT) + "/" + payment + "/versions/1")));
        assertThat(JsonPath.<Integer>read(first, "$.version")).isEqualTo(1);
        assertThat(JsonPath.<String>read(first, "$.source")).isEqualTo(GherkinSources.PAYMENT);
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("AC-3.2 Scenarios keep their ids when their names are unchanged")
    void scenariosKeepTheirIds() throws Exception {
        String before = body(view(mockMvc, GUS, CHECKOUT, payment));

        String after = body(save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source",
                GherkinSources.PAYMENT_V2, "baseVersion", 1)));

        assertThat(scenarioId(after, "Pay by card")).isEqualTo(scenarioId(before, "Pay by card"));
        assertThat(scenarioId(after, "Pay with a voucher")).isEqualTo(scenarioId(before, "Pay with a voucher"));
        assertThat(allScenarioIds(before)).doesNotContain(scenarioId(after, "Pay by bank transfer"));
        assertThat(rowIds(after)).isEqualTo(rowIds(before));
    }

    @Test
    @Tag("AC-3.3")
    @DisplayName("AC-3.3 A renamed scenario gets a new id")
    void aRenamedScenarioGetsANewId() throws Exception {
        String before = body(view(mockMvc, GUS, CHECKOUT, payment));
        String oldId = scenarioId(before, "Pay by card");

        String after = body(save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source",
                GherkinSources.PAYMENT.replace("Scenario: Pay by card", "Scenario: Pay with a card"),
                "baseVersion", 1)));

        assertThat(scenarioId(after, "Pay with a card")).isNotEqualTo(oldId);
        assertThat(after).doesNotContain(oldId);
    }

    @Test
    @Tag("AC-3.4")
    @DisplayName("AC-3.4 Example rows keep their ids when their cells are unchanged, even when reordered")
    void exampleRowsKeepTheirIds() throws Exception {
        List<String> before = rowIds(body(view(mockMvc, GUS, CHECKOUT, payment)));
        String source = GherkinSources.PAYMENT
                .replace("| SAVE10 | confirmed |\n        | SAVE20 | confirmed |",
                        "| SAVE20 | confirmed |\n        | SAVE10 | confirmed |")
                .replace("| OLD5 | rejected |", "| OLD9 | rejected |");
        assertThat(source).contains("OLD9").doesNotContain("SAVE10 | confirmed |\n        | SAVE20");

        String after = body(save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source", source,
                "baseVersion", 1)));

        List<String> afterIds = rowIds(after);
        assertThat(JsonPath.<List<Object>>read(after, "$.children[2].children[0].examples[0].rows[*].cells"))
                .containsExactly(List.of("SAVE20", "confirmed"), List.of("SAVE10", "confirmed"));
        assertThat(afterIds.get(0)).isEqualTo(before.get(1));
        assertThat(afterIds.get(1)).isEqualTo(before.get(0));
        assertThat(afterIds.get(2)).isNotEqualTo(before.get(2));
    }

    @Test
    @Tag("AC-3.5")
    @DisplayName("AC-3.5 A save based on an outdated version is refused")
    void anOutdatedSaveIsRefused() throws Exception {
        save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source", GherkinSources.PAYMENT_V2,
                "baseVersion", 1));

        MvcResult result = save(mockMvc, ANA, CHECKOUT, payment, fields("path", PATH, "source",
                GherkinSources.ADDRESS, "baseVersion", 1));

        assertThat(assertProblem(result, 409, "VERSION_CONFLICT")).containsEntry("currentVersion", 2);
        String feature = body(view(mockMvc, GUS, CHECKOUT, payment));
        assertThat(JsonPath.<Integer>read(feature, "$.currentVersion")).isEqualTo(2);
        assertThat(JsonPath.<String>read(feature, "$.source")).isEqualTo(GherkinSources.PAYMENT_V2);
    }

    @Test
    @Tag("AC-3.6")
    @DisplayName("AC-3.6 Invalid Gherkin is not saved")
    void invalidGherkinIsNotSaved() throws Exception {
        MvcResult result = save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source",
                GherkinSources.INCONSISTENT_CELLS, "baseVersion", 1));

        Map<String, Object> problem = assertProblem(result, 422, "GHERKIN_INVALID");
        assertThat(CreateFeatureTest.parseErrors(problem)).anySatisfy(error -> assertThat(error)
                .containsEntry("line", 5));
        String feature = body(view(mockMvc, GUS, CHECKOUT, payment));
        assertThat(JsonPath.<Integer>read(feature, "$.currentVersion")).isEqualTo(1);
        assertThat(JsonPath.<String>read(feature, "$.source")).isEqualTo(GherkinSources.PAYMENT);
        assertThat(rowIds(feature)).hasSize(3);
        assertThat(JsonPath.<Integer>read(body(perform(mockMvc, GUS, get(features(CHECKOUT)))),
                "$[0].testCaseCount")).isEqualTo(4);
    }

    @Test
    @Tag("AC-3.7")
    @DisplayName("AC-3.7 Saving unchanged text creates no version")
    void savingUnchangedTextCreatesNoVersion() throws Exception {
        String before = JsonPath.read(body(view(mockMvc, GUS, CHECKOUT, payment)), "$.updatedAt");

        MvcResult result = save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source",
                GherkinSources.PAYMENT, "baseVersion", 1));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(body(result), "$.currentVersion")).isEqualTo(1);
        assertThat(JsonPath.<String>read(body(result), "$.updatedAt")).isEqualTo(before);
        assertThat(data.count("feature_versions")).isEqualTo(1);
    }

    @ParameterizedTest(name = "AC-3.8 {0} saves: {1}")
    @CsvSource({"ana,200", "gus,200", "hal,403", "dan,403", "ivy,403"})
    @Tag("AC-3.8")
    void onlyMembersAndAdminsCanSave(String actor, int status) throws Exception {
        User user = switch (actor) {
            case "ana" -> ANA;
            case "gus" -> GUS;
            case "hal" -> HAL;
            case "dan" -> DAN;
            default -> IVY;
        };

        MvcResult result = save(mockMvc, user, CHECKOUT, payment, fields("path", PATH, "source",
                GherkinSources.PAYMENT_V2, "baseVersion", 1));

        if (status == 200) {
            assertThat(JsonPath.<Integer>read(body(result), "$.currentVersion")).isEqualTo(2);
        } else {
            assertProblem(result, 403, "FORBIDDEN");
            assertThat(currentVersion()).isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "AC-3.9 {0} reads version {1}")
    @CsvSource({"dan,1,200", "dan,2,200", "dan,3,404", "dan,0,400", "dan,abc,400", "ivy,1,403"})
    @Tag("AC-3.9")
    void readingTheHistory(String actor, String version, int status) throws Exception {
        save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source", GherkinSources.PAYMENT_V2,
                "baseVersion", 1));

        MvcResult result = perform(mockMvc, actor.equals("dan") ? DAN : IVY,
                get(features(CHECKOUT) + "/" + payment + "/versions/" + version));

        switch (status) {
            case 200 -> assertThat(JsonPath.<String>read(body(result), "$.source"))
                    .isEqualTo(version.equals("1") ? GherkinSources.PAYMENT : GherkinSources.PAYMENT_V2);
            case 404 -> assertProblem(result, 404, "VERSION_NOT_FOUND");
            case 400 -> assertProblem(result, 400, "MALFORMED_REQUEST");
            default -> assertProblem(result, 403, "FORBIDDEN");
        }
    }

    @ParameterizedTest(name = "AC-3.10 {0}")
    @ValueSource(strings = {"no baseVersion", "0", "-1"})
    @Tag("AC-3.10")
    void aSaveWithoutAValidBaseVersion(String baseVersion) throws Exception {
        Map<String, Object> body = fields("path", PATH, "source", GherkinSources.PAYMENT_V2);
        if (!baseVersion.equals("no baseVersion")) {
            body.put("baseVersion", Integer.parseInt(baseVersion));
        }

        Map<String, Object> problem = assertProblem(save(mockMvc, GUS, CHECKOUT, payment, body), 400,
                "VALIDATION_FAILED");

        assertThat(errorFields(problem)).contains("baseVersion");
        assertThat(currentVersion()).isEqualTo(1);
    }

    @ParameterizedTest(name = "AC-3.12 path {0}")
    @CsvSource({"features/checkout/card-payment.feature,200", "features/address.feature,409"})
    @Tag("AC-3.12")
    void thePathCanBeChangedByASave(String path, int status) throws Exception {
        created(mockMvc, GUS, CHECKOUT, "features/Address.feature", GherkinSources.ADDRESS);

        MvcResult result = save(mockMvc, GUS, CHECKOUT, payment, fields("path", path, "source",
                GherkinSources.PAYMENT, "baseVersion", 1));

        if (status == 200) {
            assertThat(JsonPath.<String>read(body(result), "$.path")).isEqualTo(path);
            assertThat(JsonPath.<Integer>read(body(result), "$.currentVersion")).isEqualTo(1);
        } else {
            assertProblem(result, 409, "FEATURE_PATH_TAKEN");
            assertThat(JsonPath.<String>read(body(view(mockMvc, GUS, CHECKOUT, payment)), "$.path")).isEqualTo(PATH);
        }
    }

    @Test
    @Tag("AC-3.13")
    @DisplayName("AC-3.13 A path change alone updates who changed the feature and when, without a version")
    void aPathChangeAloneUpdatesWhoAndWhen() throws Exception {
        String before = body(view(mockMvc, GUS, CHECKOUT, payment));

        MvcResult result = save(mockMvc, ANA, CHECKOUT, payment, fields("path",
                "features/checkout/card-payment.feature", "source", GherkinSources.PAYMENT, "baseVersion", 1));

        String after = body(result);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(after, "$.currentVersion")).isEqualTo(1);
        assertThat(JsonPath.<String>read(after, "$.updatedBy")).isEqualTo(ANA.id().toString());
        assertThat(Instant.parse(JsonPath.read(after, "$.updatedAt")))
                .isAfter(Instant.parse(JsonPath.read(before, "$.updatedAt")));
        assertThat(JsonPath.<String>read(after, "$.createdBy")).isEqualTo(GUS.id().toString());
        assertThat(JsonPath.<String>read(after, "$.createdAt")).isEqualTo(JsonPath.read(before, "$.createdAt"));
    }

    @ParameterizedTest(name = "AC-3.14 message {0}")
    @ValueSource(strings = {"trimmed", "blank", "inner line break", "501 code points"})
    @Tag("AC-3.14")
    void theVersionMessageIsTrimmed(String row) throws Exception {
        String message = switch (row) {
            case "trimmed" -> "  Fix typo \n";
            case "blank" -> " \u00A0\u200B ";
            case "inner line break" -> "Fix\nmore";
            default -> " " + "a".repeat(499) + " ";
        };

        MvcResult result = save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source",
                GherkinSources.PAYMENT_V2, "baseVersion", 1, "message", message));

        switch (row) {
            case "trimmed", "blank" -> {
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                Object saved = JsonPath.read(body(perform(mockMvc, GUS,
                        get(features(CHECKOUT) + "/" + payment + "/versions/2"))), "$.message");
                assertThat(saved).isEqualTo(row.equals("trimmed") ? "Fix typo" : null);
            }
            default -> assertThat(errorFields(assertProblem(result, 400, "VALIDATION_FAILED"))).contains("message");
        }
    }

    @Test
    @Tag("AC-7.7")
    @DisplayName("AC-7.7 PUT on an archived project's feature is 409 PROJECT_ARCHIVED")
    void aSaveOnAnArchivedProject() throws Exception {
        stub.answer(200, AccessCheckStub.accessBody(LEGACY, "MEMBER").replace("\"archived\":true",
                "\"archived\":false"));
        UUID legacy = created(mockMvc, GUS, LEGACY, "features/address.feature", GherkinSources.ADDRESS);
        stub.reset();

        MvcResult result = save(mockMvc, GUS, LEGACY, legacy, fields("path", "features/address.feature", "source",
                GherkinSources.PAYMENT, "baseVersion", 1));

        assertProblem(result, 409, "PROJECT_ARCHIVED");
        assertThat(JsonPath.<Integer>read(body(view(mockMvc, GUS, LEGACY, legacy)), "$.currentVersion"))
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "AC-7.12 baseVersion {0} is malformed")
    @ValueSource(strings = {"\"1\"", "1.5", "1.0", "2147483648"})
    @Tag("AC-7.12")
    void baseVersionHasOneSpelling(String baseVersion) throws Exception {
        MvcResult result = save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source",
                GherkinSources.PAYMENT_V2, "baseVersion", new Raw(baseVersion)));

        assertProblem(result, 400, "MALFORMED_REQUEST");
        assertThat(currentVersion()).isEqualTo(1);
    }

    @ParameterizedTest(name = "AC-7.12 /versions/{0} is malformed")
    @ValueSource(strings = {"01", "+1", "2147483648", "0"})
    @Tag("AC-7.12")
    void versionHasOneSpelling(String version) throws Exception {
        assertProblem(perform(mockMvc, GUS, get(URI.create(features(CHECKOUT) + "/" + payment + "/versions/"
                + version.replace("+", "%2B")))), 400, "MALFORMED_REQUEST");
    }

    @ParameterizedTest(name = "AC-7.14 {0} is malformed, with no access check")
    @ValueSource(strings = {"/versions/%201", "/versions/1%20", "%20/versions"})
    @Tag("AC-7.14")
    void versionsAreMatchedExactly(String suffix) throws Exception {
        stub.reset();

        assertProblem(perform(mockMvc, GUS, get(URI.create(features(CHECKOUT) + "/" + payment + suffix))), 400,
                "MALFORMED_REQUEST");
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @Tag("AC-7.15")
    @DisplayName("AC-7.15 PUT with \"source\": 7 is malformed")
    void aNumberIsNotASourceOnSave() throws Exception {
        MvcResult result = perform(mockMvc, GUS, put(features(CHECKOUT) + "/" + payment)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":7,\"path\":\"" + PATH + "\",\"baseVersion\":1}"));

        assertProblem(result, 400, "MALFORMED_REQUEST");
        assertThat(currentVersion()).isEqualTo(1);
        assertThat(data.count("features")).isEqualTo(1);
    }

    @Test
    @DisplayName("D20 follow-up 2: a save with \"message\": null creates a version without a message")
    void aNullMessageOnSave() throws Exception {
        MvcResult result = save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source",
                GherkinSources.PAYMENT_V2, "baseVersion", 1, "message", null));

        assertThat(JsonPath.<Integer>read(body(result), "$.currentVersion")).isEqualTo(2);
        assertThat(JsonPath.<Object>read(body(perform(mockMvc, GUS,
                get(features(CHECKOUT) + "/" + payment + "/versions/2"))), "$.message")).isNull();
    }

    @Test
    @Tag("AC-1.16")
    @DisplayName("AC-1.16 (save) an invisible path part is a validation error")
    void anInvisiblePathPartOnSave() throws Exception {
        MvcResult result = save(mockMvc, GUS, CHECKOUT, payment, fields("path", "features/\u200B/payment.feature",
                "source", GherkinSources.PAYMENT, "baseVersion", 1));

        assertThat(errorFields(assertProblem(result, 400, "VALIDATION_FAILED"))).contains("path");
        assertThat(body(result)).doesNotContain("\u200B");
    }

    @Test
    @Tag("AC-1.17")
    @DisplayName("AC-1.17 (save) a new path is compared with ICU root lower case")
    void aNewPathIsComparedWithIcuOnSave() throws Exception {
        created(mockMvc, GUS, CHECKOUT, "features/" + cycle(0x10400, 0x10426, 1007) + ".feature",
                GherkinSources.ADDRESS);

        MvcResult result = save(mockMvc, GUS, CHECKOUT, payment, fields("path",
                "features/" + cycle(0x10428, 0x1044E, 1007) + ".feature", "source", GherkinSources.PAYMENT,
                "baseVersion", 1));

        assertProblem(result, 409, "FEATURE_PATH_TAKEN");
    }

    @Test
    @DisplayName("D16: an unknown feature is 404 FEATURE_NOT_FOUND for save and history")
    void anUnknownFeatureOnSaveAndHistory() throws Exception {
        UUID unknown = UUID.randomUUID();

        assertProblem(save(mockMvc, GUS, CHECKOUT, unknown, fields("path", PATH, "source", GherkinSources.PAYMENT,
                "baseVersion", 1)), 404, "FEATURE_NOT_FOUND");
        assertProblem(perform(mockMvc, GUS, get(features(CHECKOUT) + "/" + unknown + "/versions")), 404,
                "FEATURE_NOT_FOUND");
        assertProblem(perform(mockMvc, GUS, get(features(CHECKOUT) + "/" + unknown + "/versions/1")), 404,
                "FEATURE_NOT_FOUND");
    }

    @Test
    @DisplayName("D9: versions are never rewritten; version 1 keeps its text, author and time after saves")
    void versionsAreImmutable() throws Exception {
        String before = body(perform(mockMvc, GUS, get(features(CHECKOUT) + "/" + payment + "/versions/1")));

        save(mockMvc, ANA, CHECKOUT, payment, fields("path", PATH, "source", GherkinSources.PAYMENT_V2,
                "baseVersion", 1));
        save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source", GherkinSources.PAYMENT,
                "baseVersion", 2));

        assertThat(body(perform(mockMvc, GUS, get(features(CHECKOUT) + "/" + payment + "/versions/1"))))
                .isEqualTo(before);
        assertThat(currentVersion()).isEqualTo(3);
    }

    private int currentVersion() throws Exception {
        return JsonPath.read(body(view(mockMvc, GUS, CHECKOUT, payment)), "$.currentVersion");
    }

    private static String scenarioId(String feature, String name) {
        List<String> ids = JsonPath.read(feature, "$..[?(@.name == '" + name + "' && @.steps)].id");
        assertThat(ids).as(name).hasSize(1);
        return ids.get(0);
    }

    private static List<String> allScenarioIds(String feature) {
        return JsonPath.read(feature, "$..[?(@.steps)].id");
    }

    private static List<String> rowIds(String feature) {
        return JsonPath.read(feature, "$..rows[*].id");
    }
}
