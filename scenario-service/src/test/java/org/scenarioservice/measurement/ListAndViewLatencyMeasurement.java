package org.scenarioservice.measurement;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.scenarioservice.gherkin.GherkinAdapter;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.created;
import static org.scenarioservice.support.FeatureCalls.list;
import static org.scenarioservice.support.FeatureCalls.view;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.GUS;

/**
 * T14's p95 check (review point B, deviation 3): 50 features of about 100 lines in one project, the list and the view
 * measured through MockMvc with the access check stub (an HTTP call per request, as in production), and the share of
 * time spent parsing. It runs only on request, so it never slows the normal build:
 * {@code SCENARIO_MEASURE=true ./gradlew test --tests '*ListAndViewLatencyMeasurement'}. It fails when a p95 is over
 * the 200 ms target, so the result can't be missed.
 */
@ScenarioServiceIntegrationTest
@EnabledIfEnvironmentVariable(named = "SCENARIO_MEASURE", matches = "true")
class ListAndViewLatencyMeasurement {

    private static final int FEATURES = 50;
    private static final int WARM_UP = 30;
    private static final int SAMPLES = 200;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    @Autowired
    private GherkinAdapter gherkin;

    @Test
    @DisplayName("T14: p95 of list and view with 50 features of about 100 lines, and the parsing share")
    void measure() throws Exception {
        data.reset();
        AccessCheckStub.get().reset();
        List<String> sources = new ArrayList<>();
        UUID payment = null;
        for (int i = 0; i < FEATURES; i++) {
            String source = source(i);
            sources.add(source);
            UUID id = created(mockMvc, GUS, CHECKOUT, "features/area-" + (i % 7) + "/feature-" + i + ".feature",
                    source);
            payment = i == 0 ? id : payment;
        }
        int lines = sources.get(0).split("\n").length;

        long[] listNanos = time(() -> list(mockMvc, GUS, CHECKOUT));
        UUID viewed = payment;
        long[] viewNanos = time(() -> view(mockMvc, GUS, CHECKOUT, viewed));
        MvcResult listed = list(mockMvc, GUS, CHECKOUT);
        assertThat(JsonPath.<List<Object>>read(listed.getResponse().getContentAsString(), "$")).hasSize(FEATURES);

        // What parsing costs inside one list (parse + test cases for every feature) and one view (one parse)
        long[] listParse = new long[SAMPLES];
        long[] viewParse = new long[SAMPLES];
        for (int round = 0; round < WARM_UP + SAMPLES; round++) {
            long start = System.nanoTime();
            for (String source : sources) {
                gherkin.parse(source);
                gherkin.testCases(source);
            }
            long middle = System.nanoTime();
            gherkin.parse(sources.get(0));
            long end = System.nanoTime();
            if (round >= WARM_UP) {
                listParse[round - WARM_UP] = middle - start;
                viewParse[round - WARM_UP] = end - middle;
            }
        }

        double listP95 = p95(listNanos);
        double viewP95 = p95(viewNanos);
        System.out.printf("MEASUREMENT lines per feature: %d, features: %d, samples: %d%n", lines, FEATURES, SAMPLES);
        System.out.printf("MEASUREMENT list p50 %.1f ms, p95 %.1f ms; parsing p95 %.1f ms (%.0f%% of the list p95)%n",
                p50(listNanos), listP95, p95(listParse), 100 * p95(listParse) / listP95);
        System.out.printf("MEASUREMENT view p50 %.1f ms, p95 %.1f ms; parsing p95 %.1f ms (%.0f%% of the view p95)%n",
                p50(viewNanos), viewP95, p95(viewParse), 100 * p95(viewParse) / viewP95);
        assertThat(lines).isBetween(90, 110);
        assertThat(listP95).as("list p95 in ms").isLessThanOrEqualTo(200);
        assertThat(viewP95).as("view p95 in ms").isLessThanOrEqualTo(200);
        data.reset();
    }

    private long[] time(Request request) throws Exception {
        for (int i = 0; i < WARM_UP; i++) {
            assertThat(request.send().getResponse().getStatus()).isEqualTo(200);
        }
        long[] nanos = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            MvcResult result = request.send();
            nanos[i] = System.nanoTime() - start;
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
        }
        return nanos;
    }

    private static double p95(long[] nanos) {
        return percentile(nanos, 0.95);
    }

    private static double p50(long[] nanos) {
        return percentile(nanos, 0.50);
    }

    private static double percentile(long[] nanos, double share) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(share * sorted.length) - 1] / 1_000_000.0;
    }

    /**
     * A feature of about 100 lines: tags, a description, a Background, 12 Scenarios and an Outline with 6 rows.
     */
    private static String source(int number) {
        StringBuilder text = new StringBuilder("@area" + (number % 7) + " @smoke\nFeature: Feature " + number + "\n"
                + "  Customers do things in area " + (number % 7) + ".\n\n  Background:\n"
                + "    Given a basket with these items:\n      | item  | price |\n      | Mug   | 8.00  |\n\n");
        for (int i = 0; i < 12; i++) {
            text.append("  @case").append(i).append("\n  Scenario: Case ").append(i).append('\n')
                    .append("    Given the customer ").append(i).append(" is signed in\n")
                    .append("    When the customer pays ").append(i).append(" times\n")
                    .append("    Then the order is confirmed\n\n");
        }
        text.append("  Scenario Outline: Pay with <code>\n    When the customer pays with \"<code>\"\n")
                .append("    Then the result is \"<result>\"\n\n    Examples: Vouchers\n")
                .append("      | code   | result    |\n");
        for (int row = 0; row < 6; row++) {
            text.append("      | CODE").append(row).append("  | confirmed |\n");
        }
        return text.toString();
    }

    @FunctionalInterface
    private interface Request {
        MvcResult send() throws Exception;
    }
}
