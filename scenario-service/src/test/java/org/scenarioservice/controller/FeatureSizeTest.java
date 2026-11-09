package org.scenarioservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.create;
import static org.scenarioservice.support.FeatureCalls.features;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.perform;
import static org.scenarioservice.support.FeatureCalls.save;
import static org.scenarioservice.support.ProblemAssertions.assertProblem;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AC-1.20 (D21 #2, BUG-2; end to end in the shared test context, where {@code lock_timeout} is 1 s): at most 10,000
 * elements per feature; the largest allowed create and save are quick and don't block another writer. The timings are
 * printed with the prefix {@code SIZE-MEASURE} for the handoff.
 */
@ScenarioServiceIntegrationTest
class FeatureSizeTest {

    private static final Duration TARGET = Duration.ofSeconds(3);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    @BeforeEach
    void reset() {
        data.reset();
        AccessCheckStub.get().reset();
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("AC-1.20 10,001 elements is 400 VALIDATION_FAILED on source, naming the limit and not the text")
    void tenThousandAndOneElementsIsRejected() throws Exception {
        long start = System.nanoTime();
        MvcResult result = create(mockMvc, GUS, CHECKOUT, "features/big.feature", "@big\n" + scenarios(5000, 0));
        System.out.printf("SIZE-MEASURE 10,001 elements rejected in %d ms%n", millis(start));

        Map<String, Object> problem = assertProblem(result, 400, "VALIDATION_FAILED");
        List<Map<String, Object>> errors = errors(problem);
        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error).containsEntry("field", "source");
            assertThat((String) error.get("message")).contains("10,000").doesNotContain("Scenario", "step");
        });
        assertThat(data.stored()).isEqualTo("0 features, 0 versions, 0 tags");
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("AC-1.20 10,000 elements: 201 within 3 s, and a create sent 1 s later gets 201, never 409")
    void tenThousandElementsAreQuickAndDontBlockOtherWriters() throws Exception {
        long start = System.nanoTime();
        CompletableFuture<MvcResult> big = CompletableFuture.supplyAsync(() -> call(() ->
                create(mockMvc, GUS, CHECKOUT, "features/big.feature", scenarios(5000, 0))));
        Thread.sleep(1000);
        long otherStart = System.nanoTime();
        MvcResult other = create(mockMvc, GUS, CHECKOUT, "features/other.feature", GherkinSources.ADDRESS);
        long otherMillis = millis(otherStart);
        MvcResult created = big.get(30, TimeUnit.SECONDS);
        long bigMillis = millis(start);
        System.out.printf("SIZE-MEASURE 10,000-element create %d ms; a create sent 1 s later took %d ms%n",
                bigMillis, otherMillis);

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(Duration.ofMillis(bigMillis)).isLessThanOrEqualTo(TARGET);
        assertThat(other.getResponse().getStatus()).as("the other create").isEqualTo(201);
        assertThat(data.count("scenarios")).isEqualTo(5000 + 1);
        assertThat(data.count("steps")).isEqualTo(5000 + 2);
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("D21 #2: a 10,000-element save is within 3 s, keeps the ids, and holds the lock briefly")
    void tenThousandElementSave() throws Exception {
        MvcResult created = create(mockMvc, GUS, CHECKOUT, "features/big.feature", scenarios(5000, 0));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.read(body(created), "$.id"));
        List<String> before = JsonPath.read(body(created), "$.children[*].id");
        String changed = scenarios(5000, 0).replace("    Given step 4999\n", "    Given step four thousand\n");

        long start = System.nanoTime();
        CompletableFuture<MvcResult> saved = CompletableFuture.supplyAsync(() -> call(() -> save(mockMvc, GUS,
                CHECKOUT, id, fields("path", "features/big.feature", "source", changed, "baseVersion", 1))));
        // A small write started while the big save runs waits at most for the save's lock hold
        Thread.sleep(300);
        long otherStart = System.nanoTime();
        MvcResult other = create(mockMvc, GUS, CHECKOUT, "features/other.feature", GherkinSources.ADDRESS);
        long otherMillis = millis(otherStart);
        MvcResult result = saved.get(30, TimeUnit.SECONDS);
        long saveMillis = millis(start);
        System.out.printf("SIZE-MEASURE 10,000-element save %d ms; a create sent 300 ms later took %d ms%n",
                saveMillis, otherMillis);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(Duration.ofMillis(saveMillis)).isLessThanOrEqualTo(TARGET);
        assertThat(JsonPath.<List<String>>read(body(result), "$.children[*].id")).isEqualTo(before);
        assertThat(other.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("D21 #2: qa's 999,970-character text (about 74,900 elements) is rejected before any write")
    void qasMaximumSizeTextIsRejected() throws Exception {
        StringBuilder text = new StringBuilder("Feature: W\n");
        for (int i = 0; i < 37447; i++) {
            text.append("  Scenario: s").append(i).append("\n    * a\n");
        }
        long start = System.nanoTime();
        MvcResult result = create(mockMvc, GUS, CHECKOUT, "features/w.feature", text.toString());
        System.out.printf("SIZE-MEASURE qa's %,d-character text rejected in %d ms%n", text.length(), millis(start));

        assertThat(errors(assertProblem(result, 400, "VALIDATION_FAILED"))).extracting(error -> error.get("field"))
                .containsExactly("source");
        assertThat(data.count("features")).isZero();
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("AC-1.20 (BUG-6) 10,000 distinct tags: 201 within 3 s, and a create sent 1 s later gets 201")
    void tenThousandDistinctTagsAreQuickAndDontBlockOtherWriters() throws Exception {
        long start = System.nanoTime();
        CompletableFuture<MvcResult> big = CompletableFuture.supplyAsync(() -> call(() ->
                create(mockMvc, GUS, CHECKOUT, "features/tags.feature", distinctTags(10000, "t"))));
        Thread.sleep(1000);
        long otherStart = System.nanoTime();
        MvcResult other = create(mockMvc, GUS, CHECKOUT, "features/other.feature", "Feature: O\n");
        long otherMillis = millis(otherStart);
        MvcResult created = big.get(60, TimeUnit.SECONDS);
        long bigMillis = millis(start);
        System.out.printf("SIZE-MEASURE 10,000 distinct tags: create %d ms; a create sent 1 s later took %d ms%n",
                bigMillis, otherMillis);

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(Duration.ofMillis(bigMillis)).isLessThanOrEqualTo(TARGET);
        assertThat(other.getResponse().getStatus()).as("the other create").isEqualTo(201);
        assertThat(data.count("tags")).isEqualTo(10000);
        assertThat(data.count("tag_assignments")).isEqualTo(10000);
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("AC-1.20 (BUG-6) a save of 10,000 tags, half of them new, is within 3 s and reuses the existing tags")
    void tenThousandTagSave() throws Exception {
        MvcResult created = create(mockMvc, GUS, CHECKOUT, "features/tags.feature", distinctTags(10000, "t"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.read(body(created), "$.id"));
        // The first 5,000 tags stay, the other 5,000 are replaced by new names
        String changed = distinctTags(5000, "t").replace("Feature: Tags\n", "") + distinctTags(5000, "n");

        long start = System.nanoTime();
        MvcResult saved = save(mockMvc, GUS, CHECKOUT, id, fields("path", "features/tags.feature", "source", changed,
                "baseVersion", 1));
        long saveMillis = millis(start);
        System.out.printf("SIZE-MEASURE 10,000 tags (5,000 new): save %d ms%n", saveMillis);

        assertThat(saved.getResponse().getStatus()).isEqualTo(200);
        assertThat(Duration.ofMillis(saveMillis)).isLessThanOrEqualTo(TARGET);
        assertThat(data.count("tags")).isEqualTo(15000);
        assertThat(data.count("tag_assignments")).isEqualTo(10000);
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("AC-1.20 (BUG-6) 3,333 scenarios with a tag each: quick, and a create sent 1 s later gets 201")
    void scenariosWithATagEachAreQuick() throws Exception {
        StringBuilder text = new StringBuilder("Feature: Tagged\n");
        for (int i = 0; i < 3333; i++) {
            text.append("  @s").append(i).append("\n  Scenario: S").append(i).append("\n    Given step\n");
        }
        long start = System.nanoTime();
        CompletableFuture<MvcResult> big = CompletableFuture.supplyAsync(() -> call(() ->
                create(mockMvc, GUS, CHECKOUT, "features/tagged.feature", text.toString())));
        Thread.sleep(1000);
        MvcResult other = create(mockMvc, GUS, CHECKOUT, "features/other.feature", "Feature: O\n");
        MvcResult created = big.get(60, TimeUnit.SECONDS);
        long bigMillis = millis(start);
        System.out.printf("SIZE-MEASURE 3,333 tagged scenarios (9,999 elements): create %d ms%n", bigMillis);

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(Duration.ofMillis(bigMillis)).isLessThanOrEqualTo(TARGET);
        assertThat(other.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @Tag("AC-1.20")
    @DisplayName("D16 step 7a: on save, not found, an archived feature and a stale baseVersion come before size")
    void theSizeCheckComesAfterTheOtherSaveChecks() throws Exception {
        String tooLarge = "@big\n" + scenarios(5000, 0);
        MvcResult created = create(mockMvc, GUS, CHECKOUT, "features/s.feature", GherkinSources.ADDRESS);
        UUID id = UUID.fromString(JsonPath.read(body(created), "$.id"));

        assertProblem(save(mockMvc, GUS, CHECKOUT, UUID.randomUUID(), fields("path", "features/s.feature", "source",
                tooLarge, "baseVersion", 1)), 404, "FEATURE_NOT_FOUND");
        assertProblem(save(mockMvc, GUS, CHECKOUT, id, fields("path", "features/s.feature", "source", tooLarge,
                "baseVersion", 2)), 409, "VERSION_CONFLICT");
        assertProblem(save(mockMvc, GUS, CHECKOUT, id, fields("path", "features/s.feature", "source", tooLarge,
                "baseVersion", 1)), 400, "VALIDATION_FAILED");
        assertThat(perform(mockMvc, GUS, post(features(CHECKOUT) + "/" + id + "/archive")).getResponse().getStatus())
                .isEqualTo(200);
        assertProblem(save(mockMvc, GUS, CHECKOUT, id, fields("path", "features/s.feature", "source", tooLarge,
                "baseVersion", 1)), 409, "FEATURE_ARCHIVED");
        assertThat(data.count("feature_versions")).isEqualTo(1);
    }

    /**
     * {@code count} distinct tags {@code @<prefix>0 …} on one line, then {@code Feature: Tags}: count elements.
     */
    private static String distinctTags(int count, String prefix) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append(i == 0 ? "@" : " @").append(prefix).append(i);
        }
        return text.append("\nFeature: Tags\n").toString();
    }

    /**
     * {@code count} Scenarios with one step each, after the Feature line: 2 x count elements.
     */
    private static String scenarios(int count, int offset) {
        StringBuilder text = new StringBuilder("Feature: Big\n");
        for (int i = offset; i < offset + count; i++) {
            text.append("  Scenario: S").append(i).append("\n    Given step ").append(i).append('\n');
        }
        return text.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(Map<String, Object> problem) {
        return (List<Map<String, Object>>) problem.get("errors");
    }

    private static long millis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static MvcResult call(Request request) {
        try {
            return request.send();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface Request {
        MvcResult send() throws Exception;
    }
}
