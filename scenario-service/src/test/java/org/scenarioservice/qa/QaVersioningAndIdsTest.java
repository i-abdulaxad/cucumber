package org.scenarioservice.qa;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureCalls.Raw;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.created;
import static org.scenarioservice.support.FeatureCalls.features;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.perform;
import static org.scenarioservice.support.FeatureCalls.save;
import static org.scenarioservice.support.FeatureCalls.view;
import static org.scenarioservice.support.ProblemAssertions.assertProblem;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA (adversarial, D9, D11, D16; AC-3.2 to AC-3.7, AC-4.4, AC-6.5): id stability across edits for every matching rule
 * of D9 (kind, name, container, n-th occurrence, row pooling by header, identical rows), version conflicts and the
 * D16 order on save.
 */
@ScenarioServiceIntegrationTest
class QaVersioningAndIdsTest {

    private static final String PATH = "features/ids.feature";

    private final AccessCheckStub stub = AccessCheckStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    private UUID feature;
    private int version;

    @BeforeEach
    void reset() {
        data.reset();
        stub.reset();
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("D9: reordering feature-level scenarios keeps every id")
    void reorderingKeepsIds() throws Exception {
        String v1 = "Feature: F\n  Scenario: A\n    Given a\n  Scenario: B\n    Given b\n  Scenario: C\n    Given c\n";
        Map<String, String> before = ids(start(v1));

        Map<String, String> after = ids(next("Feature: F\n  Scenario: C\n    Given c\n  Scenario: A\n    Given a\n"
                + "  Scenario: B\n    Given b\n"));

        assertThat(after).isEqualTo(before);
    }

    @Test
    @Tag("AC-3.3")
    @DisplayName("D9: renaming a Rule gives its scenarios new ids (the container changed)")
    void renamingARuleChangesTheContainer() throws Exception {
        String v1 = "Feature: F\n  Rule: R1\n    Scenario: S\n      Given s\n";
        String before = ids(start(v1)).get("S");

        String after = ids(next(v1.replace("Rule: R1", "Rule: R2"))).get("S");

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @Tag("AC-3.3")
    @DisplayName("D9: moving a scenario from the Feature into a Rule gives it a new id")
    void movingIntoARuleChangesTheContainer() throws Exception {
        String before = ids(start("Feature: F\n  Scenario: S\n    Given s\n  Rule: R\n    Scenario: T\n      Given t\n"))
                .get("S");

        String after = ids(next("Feature: F\n  Rule: R\n    Scenario: T\n      Given t\n    Scenario: S\n"
                + "      Given s\n")).get("S");

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @Tag("AC-3.3")
    @DisplayName("D9: a Scenario that becomes a Scenario Outline with the same name gets a new id (another kind)")
    void changingTheKindChangesTheId() throws Exception {
        String before = ids(start("Feature: F\n  Scenario: S\n    Given s\n")).get("S");

        String after = ids(next("Feature: F\n  Scenario Outline: S\n    Given <x>\n    Examples:\n      | x |\n"
                + "      | 1 |\n")).get("S");

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("D9: Scenario/Example and Scenario Outline/Scenario Template are one kind each: ids are kept")
    void synonymKeywordsKeepIds() throws Exception {
        Map<String, String> before = ids(start("Feature: F\n  Scenario: S\n    Given s\n"
                + "  Scenario Outline: O\n    Given <x>\n    Examples:\n      | x |\n      | 1 |\n"));

        Map<String, String> after = ids(next("Feature: F\n  Example: S\n    Given s\n"
                + "  Scenario Template: O\n    Given <x>\n    Scenarios:\n      | x |\n      | 1 |\n"));

        assertThat(after).isEqualTo(before);
        assertThat(rowIds()).hasSize(1);
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("D9: with duplicate names the n-th old scenario matches the n-th new one")
    void duplicateNamesMatchByOccurrence() throws Exception {
        String v1 = "Feature: F\n  Scenario: Same\n    Given one\n  Scenario: Same\n    Given two\n"
                + "  Scenario: Same\n    Given three\n";
        List<String> before = childIds(start(v1));

        List<String> after = childIds(next("Feature: F\n  Scenario: Same\n    Given two\n  Scenario: Same\n"
                + "    Given three\n"));

        assertThat(after).containsExactly(before.get(0), before.get(1));
    }

    @Test
    @Tag("AC-3.4")
    @DisplayName("D9: two identical example rows keep two distinct ids across an unrelated edit")
    void identicalRowsKeepDistinctIds() throws Exception {
        String v1 = "Feature: F\n  Scenario Outline: O\n    Given <x>\n    Examples:\n      | x |\n      | 1 |\n"
                + "      | 1 |\n";
        start(v1);
        List<String> before = rowIds();
        assertThat(new HashSet<>(before)).hasSize(2);

        MvcResult result = save(mockMvc, GUS, CHECKOUT, feature, fields("path", PATH, "source",
                v1.replace("Feature: F", "Feature: F2"), "baseVersion", 1));

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        assertThat(rowIds()).containsExactlyElementsOf(before);
    }

    @Test
    @Tag("AC-3.15")
    @DisplayName("BUG-3 / AC-3.15 row 1: names containing the old separator keep two different ids, and the save is 200")
    void namesWithSeparatorsDontCollide() throws Exception {
        String v1 = "Feature: K\n  Rule: a\n    Scenario: b|1|SCENARIO|c\n      Given x\n"
                + "  Rule: a|1|SCENARIO|b\n    Scenario: c\n      Given y\n";
        Map<String, String> before = ids(start(v1));
        assertThat(before.get("b|1|SCENARIO|c")).isNotEqualTo(before.get("c"));

        Map<String, String> after = ids(next(v1.replace("Given y", "Given z")));

        assertThat(after).isEqualTo(before);
        assertThat(next(v1.replace("Given x", "Given w"))).contains(before.get("c"));
    }

    @Test
    @Tag("AC-3.15")
    @DisplayName("BUG-3 / AC-3.15 row 2: a header that only moves a comma between cells is a new header (new row id)")
    void headersAreComparedAsLists() throws Exception {
        start("Feature: K\n  Scenario Outline: O\n    Given <a, b> <c>\n    Examples:\n      | a, b | c |\n"
                + "      | 1 | 2 |\n");
        List<String> before = rowIds();

        next("Feature: K\n  Scenario Outline: O\n    Given <a> <b, c>\n    Examples:\n      | a | b, c |\n"
                + "      | 1 | 2 |\n");

        assertThat(rowIds()).doesNotContainAnyElementsOf(before).hasSize(1);
    }

    @Test
    @Tag("AC-3.15")
    @DisplayName("AC-3.15: a scenario named like a Rule container and a feature-level one don't share ids")
    void containersAndNamesDontMix() throws Exception {
        String v1 = "Feature: K\n  Scenario: R\n    Given f\n  Rule: R\n    Scenario: R\n      Given r\n"
                + "  Rule:\n    Scenario: \n      Given e\n";
        start(v1);
        List<String> before = JsonPath.read(body(view(mockMvc, GUS, CHECKOUT, feature)), "$..[?(@.steps)].id");
        assertThat(new HashSet<>(before)).hasSize(3);

        next(v1.replace("Given e", "Given e2"));

        assertThat(JsonPath.<List<String>>read(body(view(mockMvc, GUS, CHECKOUT, feature)), "$..[?(@.steps)].id"))
                .isEqualTo(before);
    }

    @Test
    @Tag("AC-3.4")
    @DisplayName("Review point B: a row moved unchanged to another Examples block with the same header keeps its id")
    void aRowMovedToAnotherBlockWithTheSameHeaderKeepsItsId() throws Exception {
        start("Feature: F\n  Scenario Outline: O\n    Given <x>\n    Examples: A\n      | x |\n      | 1 |\n"
                + "      | 2 |\n    Examples: B\n      | x |\n      | 3 |\n");
        List<String> before = rowIds();

        next("Feature: F\n  Scenario Outline: O\n    Given <x>\n    Examples: A\n      | x |\n      | 1 |\n"
                + "    Examples: B\n      | x |\n      | 3 |\n      | 2 |\n");

        assertThat(rowIds()).containsExactly(before.get(0), before.get(2), before.get(1));
    }

    @Test
    @Tag("AC-3.4")
    @DisplayName("D9: a changed Examples header gives every row of the block a new id")
    void aChangedHeaderGivesNewRowIds() throws Exception {
        start("Feature: F\n  Scenario Outline: O\n    Given <x>\n    Examples:\n      | x |\n      | 1 |\n");
        List<String> before = rowIds();

        next("Feature: F\n  Scenario Outline: O\n    Given <y>\n    Examples:\n      | y |\n      | 1 |\n");

        assertThat(rowIds()).doesNotContainAnyElementsOf(before);
    }

    @Test
    @Tag("AC-3.2")
    @DisplayName("D9: an id that matched nothing in one version doesn't come back when the scenario returns later")
    void aRemovedScenarioDoesntGetItsOldIdBack() throws Exception {
        String v1 = "Feature: F\n  Scenario: Keep\n    Given k\n  Scenario: Gone\n    Given g\n";
        String gone = ids(start(v1)).get("Gone");
        next("Feature: F\n  Scenario: Keep\n    Given k\n");

        String back = ids(next(v1)).get("Gone");

        assertThat(back).isNotEqualTo(gone);
    }

    @Test
    @Tag("AC-6.5")
    @DisplayName("D9, D11: ids survive a path change, an archive and a restore together")
    void idsSurviveMovesAndArchive() throws Exception {
        Map<String, String> before = ids(start(GherkinSources.PAYMENT));
        List<String> rows = rowIds();
        save(mockMvc, GUS, CHECKOUT, feature, fields("path", "features/moved.feature", "source",
                GherkinSources.PAYMENT_V2, "baseVersion", 1));
        perform(mockMvc, GUS, post(features(CHECKOUT) + "/" + feature + "/archive"));
        perform(mockMvc, GUS, post(features(CHECKOUT) + "/" + feature + "/restore"));

        Map<String, String> after = ids(body(view(mockMvc, GUS, CHECKOUT, feature)));

        assertThat(after).containsAllEntriesOf(before);
        assertThat(rowIds()).isEqualTo(rows);
    }

    @Test
    @Tag("AC-3.5")
    @DisplayName("D9: a base version ahead of the current one, or at int32's maximum, is a VERSION_CONFLICT")
    void aFutureBaseVersionConflicts() throws Exception {
        start(GherkinSources.PAYMENT);

        for (int base : new int[]{2, Integer.MAX_VALUE}) {
            Map<String, Object> problem = assertProblem(save(mockMvc, GUS, CHECKOUT, feature, fields("path", PATH,
                    "source", GherkinSources.PAYMENT_V2, "baseVersion", base)), 409, "VERSION_CONFLICT");
            assertThat(problem).containsEntry("currentVersion", 1);
        }
    }

    @Test
    @Tag("AC-3.7")
    @DisplayName("D9: an identical text with a stale base version is still a VERSION_CONFLICT (base checked first)")
    void anIdenticalSaveWithAStaleBaseConflicts() throws Exception {
        start(GherkinSources.PAYMENT);
        next(GherkinSources.PAYMENT_V2);

        assertProblem(save(mockMvc, GUS, CHECKOUT, feature, fields("path", PATH, "source", GherkinSources.PAYMENT_V2,
                "baseVersion", 1)), 409, "VERSION_CONFLICT");
    }

    @Test
    @Tag("AC-3.7")
    @DisplayName("D8: the same text with other line endings is another text: a new version")
    void otherLineEndingsAreANewVersion() throws Exception {
        start(GherkinSources.PAYMENT);

        MvcResult result = save(mockMvc, GUS, CHECKOUT, feature, fields("path", PATH, "source",
                GherkinSources.PAYMENT.replace("\n", "\r\n"), "baseVersion", 1));

        assertThat(JsonPath.<Integer>read(body(result), "$.currentVersion")).isEqualTo(2);
    }

    @Test
    @Tag("AC-6.2")
    @DisplayName("D16 order: saving an archived feature with a stale base is FEATURE_ARCHIVED, not VERSION_CONFLICT")
    void featureArchivedComesBeforeVersionConflict() throws Exception {
        start(GherkinSources.PAYMENT);
        next(GherkinSources.PAYMENT_V2);
        perform(mockMvc, GUS, post(features(CHECKOUT) + "/" + feature + "/archive"));

        assertProblem(save(mockMvc, GUS, CHECKOUT, feature, fields("path", PATH, "source", GherkinSources.PAYMENT,
                "baseVersion", 1)), 409, "FEATURE_ARCHIVED");
    }

    @Test
    @Tag("AC-3.6")
    @DisplayName("D16 order: a stale base with invalid Gherkin is VERSION_CONFLICT; a current base is GHERKIN_INVALID")
    void versionConflictComesBeforeGherkinInvalid() throws Exception {
        start(GherkinSources.PAYMENT);
        next(GherkinSources.PAYMENT_V2);

        assertProblem(save(mockMvc, GUS, CHECKOUT, feature, fields("path", PATH, "source",
                GherkinSources.INCONSISTENT_CELLS, "baseVersion", 1)), 409, "VERSION_CONFLICT");
        assertProblem(save(mockMvc, GUS, CHECKOUT, feature, fields("path", PATH, "source",
                GherkinSources.INCONSISTENT_CELLS, "baseVersion", 2)), 422, "GHERKIN_INVALID");
    }

    @Test
    @Tag("AC-3.12")
    @DisplayName("D16 order: invalid Gherkin with a taken path is GHERKIN_INVALID (parse before the path check)")
    void gherkinInvalidComesBeforePathTaken() throws Exception {
        created(mockMvc, GUS, CHECKOUT, "features/taken.feature", GherkinSources.ADDRESS);
        start(GherkinSources.PAYMENT);

        assertProblem(save(mockMvc, GUS, CHECKOUT, feature, fields("path", "features/taken.feature", "source",
                GherkinSources.INCONSISTENT_CELLS, "baseVersion", 1)), 422, "GHERKIN_INVALID");
    }

    @Test
    @Tag("AC-3.6")
    @DisplayName("Failed saves change nothing: no version, same updatedAt, same ids")
    void failedSavesChangeNothing() throws Exception {
        String v1 = body(view(mockMvc, GUS, CHECKOUT, startId(GherkinSources.PAYMENT)));

        save(mockMvc, GUS, CHECKOUT, feature, fields("path", PATH, "source", GherkinSources.INCONSISTENT_CELLS,
                "baseVersion", 1));
        save(mockMvc, GUS, CHECKOUT, feature, fields("path", PATH, "source", GherkinSources.PAYMENT_V2,
                "baseVersion", new Raw("1.0")));
        save(mockMvc, GUS, CHECKOUT, feature, fields("path", "/bad.feature", "source", GherkinSources.PAYMENT_V2,
                "baseVersion", 1));

        assertThat(body(view(mockMvc, GUS, CHECKOUT, feature))).isEqualTo(v1);
        assertThat(data.count("feature_versions")).isEqualTo(1);
    }

    private UUID startId(String source) throws Exception {
        start(source);
        return feature;
    }

    private String start(String source) throws Exception {
        feature = created(mockMvc, GUS, CHECKOUT, PATH, source);
        version = 1;
        return body(view(mockMvc, GUS, CHECKOUT, feature));
    }

    private String next(String source) throws Exception {
        MvcResult result = save(mockMvc, GUS, CHECKOUT, feature, fields("path", PATH, "source", source,
                "baseVersion", version));
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        version = JsonPath.read(body(result), "$.currentVersion");
        return body(result);
    }

    /**
     * Scenario ids by name (every scenario, outline and background with a name, in any container).
     */
    private static Map<String, String> ids(String feature) {
        List<Map<String, Object>> scenarios = JsonPath.read(feature, "$..[?(@.steps)]");
        Map<String, String> ids = new java.util.LinkedHashMap<>();
        for (Map<String, Object> scenario : scenarios) {
            ids.put((String) scenario.get("name"), (String) scenario.get("id"));
        }
        return ids;
    }

    private static List<String> childIds(String feature) {
        return JsonPath.read(feature, "$.children[*].id");
    }

    private List<String> rowIds() throws Exception {
        return JsonPath.read(body(perform(mockMvc, GUS, get(features(CHECKOUT) + "/" + feature + "/test-cases"))),
                "$[?(@.type == 'EXAMPLE_ROW')].id");
    }
}
