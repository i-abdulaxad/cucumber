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
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.create;
import static org.scenarioservice.support.FeatureCalls.created;
import static org.scenarioservice.support.FeatureCalls.features;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.perform;
import static org.scenarioservice.support.FeatureCalls.save;
import static org.scenarioservice.support.ProblemAssertions.assertProblem;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.DAN;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.scenarioservice.support.ScenarioFixture.LEGACY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA (fix round 1, D21 #2, AC-1.20, D16 step 7a; BUG-scenario-service-2): the 10,000-element limit counted as D21
 * defines elements, for every element kind, and the error order around step 7a. backend-swe's FeatureSizeTest covers
 * the scenario-and-step shape and the timings.
 */
@ScenarioServiceIntegrationTest
class QaFeatureSizeTest {

    private static final String PATH = "features/size.feature";

    private final AccessCheckStub stub = AccessCheckStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        data.reset();
        stub.reset();
    }

    @ParameterizedTest(name = "{0}: 10,000 elements are stored, 10,001 are 400 on source")
    @ValueSource(strings = {"example rows", "rules", "feature tags", "examples blocks", "backgrounds in rules"})
    @Tag("AC-1.20")
    void theLimitCountsEveryElementKind(String kind) throws Exception {
        assertThat(create(mockMvc, GUS, CHECKOUT, PATH, source(kind, 10_000)).getResponse().getStatus())
                .isEqualTo(201);
        data.reset();

        MvcResult result = create(mockMvc, GUS, CHECKOUT, PATH, source(kind, 10_001));

        assertOverLimit(result);
        assertThat(data.stored()).isEqualTo("0 features, 0 versions, 0 tags");
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("D21 #2: data-table rows and doc-string text don't count; a duplicated tag counts once")
    void argumentsAndDuplicateTagsDontCount() throws Exception {
        StringBuilder table = new StringBuilder("@a @a\nFeature: Table\n  Scenario: S\n    Given a table\n");
        for (int i = 0; i < 20_000; i++) {
            table.append("      | ").append(i).append(" |\n");
        }
        table.append("    Then a doc\n      \"\"\"\n").append("line\n".repeat(20_000)).append("      \"\"\"\n");

        MvcResult result = create(mockMvc, GUS, CHECKOUT, PATH, table.toString());

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("Save: an over-limit text is 400 on source and changes nothing (version, ids, updatedAt)")
    void anOverLimitSaveChangesNothing() throws Exception {
        UUID id = created(mockMvc, GUS, CHECKOUT, PATH, GherkinSources.PAYMENT);
        String before = body(perform(mockMvc, GUS, org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.get(features(CHECKOUT) + "/" + id)));

        assertOverLimit(save(mockMvc, GUS, CHECKOUT, id, fields("path", PATH, "source", source("rules", 10_001),
                "baseVersion", 1)));

        assertThat(body(perform(mockMvc, GUS, org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.get(features(CHECKOUT) + "/" + id)))).isEqualTo(before);
        assertThat(data.count("feature_versions")).isEqualTo(1);
    }

    @ParameterizedTest(name = "D16 order around step 7a: {0}")
    @ValueSource(strings = {"READER creates", "archived project", "unknown feature", "archived feature",
            "stale base version", "parse error wins over size", "size wins over a taken path on create",
            "size wins over a taken path on save", "an invalid path wins over size"})
    @Tag("AC-1.20")
    void theOrderAroundStep7a(String row) throws Exception {
        String big = source("rules", 10_001);
        UUID payment = created(mockMvc, GUS, CHECKOUT, "features/payment.feature", GherkinSources.PAYMENT);
        MvcResult result = switch (row) {
            case "READER creates" -> create(mockMvc, DAN, CHECKOUT, PATH, big);
            case "archived project" -> create(mockMvc, GUS, LEGACY, PATH, big);
            case "unknown feature" -> save(mockMvc, GUS, CHECKOUT, UUID.randomUUID(), fields("path", PATH, "source",
                    big, "baseVersion", 1));
            case "archived feature" -> {
                perform(mockMvc, GUS, post(features(CHECKOUT) + "/" + payment + "/archive"));
                yield save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source", big, "baseVersion", 1));
            }
            case "stale base version" -> save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source", big,
                    "baseVersion", 2));
            case "parse error wins over size" -> create(mockMvc, GUS, CHECKOUT, PATH, big + "  | a | b |\n  | 1 |\n");
            case "size wins over a taken path on create" -> create(mockMvc, GUS, CHECKOUT,
                    "features/PAYMENT.feature", big);
            case "size wins over a taken path on save" -> {
                created(mockMvc, GUS, CHECKOUT, "features/other.feature", GherkinSources.ADDRESS);
                yield save(mockMvc, GUS, CHECKOUT, payment, fields("path", "features/Other.feature", "source", big,
                        "baseVersion", 1));
            }
            default -> create(mockMvc, GUS, CHECKOUT, "/absolute.feature", big);
        };

        switch (row) {
            case "READER creates" -> assertProblem(result, 403, "FORBIDDEN");
            case "archived project" -> assertProblem(result, 409, "PROJECT_ARCHIVED");
            case "unknown feature" -> assertProblem(result, 404, "FEATURE_NOT_FOUND");
            case "archived feature" -> assertProblem(result, 409, "FEATURE_ARCHIVED");
            case "stale base version" -> assertProblem(result, 409, "VERSION_CONFLICT");
            case "parse error wins over size" -> assertProblem(result, 422, "GHERKIN_INVALID");
            case "an invalid path wins over size" -> {
                assertProblem(result, 400, "VALIDATION_FAILED");
                assertThat(JsonPath.<List<String>>read(body(result), "$.errors[*].field")).containsExactly("path");
            }
            default -> assertOverLimit(result);
        }
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("BUG-2 reproduction: the 999,970-character text of 37,447 scenarios is rejected within 3 s, unstored")
    void theOriginalReproductionIsRejectedQuickly() throws Exception {
        StringBuilder text = new StringBuilder("Feature: W\n");
        for (int i = 0; text.length() < 999_950; i++) {
            text.append("  Scenario: s").append(i).append("\n    * a\n");
        }
        long start = System.nanoTime();

        MvcResult result = create(mockMvc, GUS, CHECKOUT, "features/w.feature", text.toString());

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        assertOverLimit(result);
        assertThat(data.count("features")).isZero();
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("BUG-6: 10,000 distinct tags are created within 3 s, and a create sent 1 s later is 201, never 409")
    void tenThousandDistinctTagsAreQuick() throws Exception {
        String source = tagLine(10_000) + "Feature: Tags\n";
        productionLikeStatistics();
        long start = System.nanoTime();
        java.util.concurrent.CompletableFuture<MvcResult> big = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> call(() -> create(mockMvc, GUS, CHECKOUT, "features/tags.feature", source)));
        Thread.sleep(1000);
        MvcResult other = create(mockMvc, GUS, CHECKOUT, "features/other.feature", GherkinSources.ADDRESS);
        MvcResult created = big.get(60, java.util.concurrent.TimeUnit.SECONDS);
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        System.out.println("QA-MEASURE 10,000 distinct tags: create " + took.toMillis() + " ms");

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(took).isLessThanOrEqualTo(Duration.ofSeconds(3));
        assertThat(other.getResponse().getStatus()).as(body(other)).isEqualTo(201);
        assertThat(data.count("tags")).isEqualTo(10_000 + 2);
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("BUG-6: a save with 5,000 kept and 5,000 new tags is within 3 s and reuses the kept catalogue rows")
    void aTagHeavySaveIsQuick() throws Exception {
        UUID id = created(mockMvc, GUS, CHECKOUT, "features/tags.feature", tagLine(10_000) + "Feature: Tags\n");
        StringBuilder half = new StringBuilder();
        for (int i = 5_000; i < 15_000; i++) {
            half.append("@t").append(i).append(' ');
        }
        productionLikeStatistics();
        long start = System.nanoTime();

        MvcResult saved = save(mockMvc, GUS, CHECKOUT, id, fields("path", "features/tags.feature", "source",
                half + "\nFeature: Tags\n", "baseVersion", 1));

        Duration took = Duration.ofNanos(System.nanoTime() - start);
        System.out.println("QA-MEASURE 10,000 tags with 5,000 new: save " + took.toMillis() + " ms");
        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        assertThat(took).isLessThanOrEqualTo(Duration.ofSeconds(3));
        assertThat(data.count("tags")).as("one catalogue row per distinct name").isEqualTo(15_000);
        assertThat(data.count("tag_assignments")).isEqualTo(10_000);
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("BUG-6: 3,333 scenarios with a tag each (9,999 elements) are created within 3 s")
    void taggedScenariosAreQuick() throws Exception {
        StringBuilder text = new StringBuilder("Feature: Tagged\n");
        for (int i = 0; i < 3_333; i++) {
            text.append("  @t").append(i).append("\n  Scenario: s").append(i).append("\n");
        }
        productionLikeStatistics();
        long start = System.nanoTime();

        MvcResult result = create(mockMvc, GUS, CHECKOUT, "features/tagged.feature", text.toString());

        Duration took = Duration.ofNanos(System.nanoTime() - start);
        System.out.println("QA-MEASURE 3,333 tagged scenarios: create " + took.toMillis() + " ms");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(took).isLessThanOrEqualTo(Duration.ofSeconds(3));
    }

    /**
     * The timed tag tests measure the application, not the test suite's resets. {@code FeatureData.reset()} deletes
     * every row, and autovacuum may then record {@code tags}, {@code tag_assignments} and {@code features} as empty
     * ({@code reltuples} 0). A pooled connection keeps the foreign-key check plans made for that state, and runs them
     * against tens of thousands of rows: QA measured 3.6-5.1 s instead of 0.3 s for the same save, all of it server CPU
     * inside the {@code tag_assignments} insert. A running database never empties these tables (catalogue rows are kept,
     * features are never deleted), and autovacuum keeps their statistics current, so the tests refresh the statistics
     * first, as autovacuum would. The 3 s bound itself is unchanged (QA report, fix round 2).
     */
    private void productionLikeStatistics() {
        jdbc.execute("ANALYZE tags, tag_assignments, features");
    }

    private static MvcResult call(java.util.concurrent.Callable<MvcResult> request) {
        try {
            return request.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertOverLimit(MvcResult result) {
        Map<String, Object> problem = assertProblem(result, 400, "VALIDATION_FAILED");
        List<Map<String, Object>> errors = JsonPath.read(body(result), "$.errors");
        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error).containsEntry("field", "source");
            assertThat((String) error.get("message")).contains("10,000").doesNotContain("Rule", "Scenario", "@t");
        });
        assertThat(problem).doesNotContainKey("parseErrors");
    }

    /** A valid text with exactly {@code elements} elements (D21), built from one element kind. */
    static String source(String kind, int elements) {
        StringBuilder text = new StringBuilder();
        switch (kind) {
            case "example rows" -> {
                text.append("Feature: Rows\n  Scenario Outline: O <n>\n    Given <n>\n    Examples:\n      | n |\n");
                for (int i = 0; i < elements - 3; i++) {
                    text.append("      | ").append(i).append(" |\n");
                }
            }
            case "rules" -> {
                int rules = elements / 3;
                int extra = elements % 3;
                text.append(extra >= 1 ? "@x" : "").append(extra == 2 ? " @y" : "")
                        .append("\nFeature: Rules\n");
                for (int i = 0; i < rules; i++) {
                    text.append("  Rule: r").append(i).append("\n    Scenario: s\n      Given a\n");
                }
            }
            case "feature tags" -> {
                text.append("@shared\nFeature: Tags\n");
                text.append("  Scenario Outline: O <n>\n    Given <n>\n    Examples:\n      | n |\n");
                for (int i = 0; i < elements - 4 - 50; i++) {
                    text.append("      | ").append(i).append(" |\n");
                }
                text.insert(0, tagLine(50));
            }
            case "examples blocks" -> {
                text.append("Feature: Blocks\n  Scenario Outline: O <n>\n    Given <n>\n");
                int blocks = (elements - 2) / 2;
                for (int i = 0; i < blocks; i++) {
                    text.append("    Examples:\n      | n |\n      | ").append(i).append(" |\n");
                }
                if ((elements - 2) % 2 == 1) {
                    text.insert(0, "@odd\n");
                }
            }
            default -> {
                int rules = elements / 3;
                int extra = elements % 3;
                text.append(extra >= 1 ? "@x" : "").append(extra == 2 ? " @y" : "").append("\nFeature: Bg\n");
                for (int i = 0; i < rules; i++) {
                    text.append("  Rule: r").append(i).append("\n    Background:\n      Given b\n");
                }
            }
        }
        return text.toString();
    }

    private static String tagLine(int count) {
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < count; i++) {
            tags.append("@t").append(i).append(' ');
        }
        return tags.append('\n').toString();
    }
}
