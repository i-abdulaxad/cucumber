package org.scenarioservice.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.scenarioservice.support.AccessCheckStub;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.ScenarioFixture.User;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
import static org.scenarioservice.support.ScenarioFixture.DAN;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.scenarioservice.support.ScenarioFixture.HAL;
import static org.scenarioservice.support.ScenarioFixture.IVY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * US-4 (T10, end to end): test cases derived from the current version, never stored (D13).
 */
@ScenarioServiceIntegrationTest
class TestCasesTest {

    private static final String PATH = "features/checkout/payment.feature";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    private UUID payment;

    @BeforeEach
    void gusHasCreatedPayment() throws Exception {
        data.reset();
        AccessCheckStub.get().reset();
        payment = created(mockMvc, GUS, CHECKOUT, PATH, GherkinSources.PAYMENT);
    }

    @Test
    @Tag("AC-4.1")
    @DisplayName("AC-4.1 A Scenario is one test case and each Outline row is another")
    void aScenarioIsOneTestCaseAndEachRowAnother() throws Exception {
        String feature = body(view(mockMvc, GUS, CHECKOUT, payment));

        MvcResult result = testCases(HAL, payment);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = body(result);
        String card = JsonPath.read(feature, "$.children[1].id");
        String outline = JsonPath.read(feature, "$.children[2].children[0].id");
        List<String> rows = JsonPath.read(feature, "$..rows[*].id");
        assertThat(JsonPath.<List<String>>read(body, "$[*].type"))
                .containsExactly("SCENARIO", "EXAMPLE_ROW", "EXAMPLE_ROW", "EXAMPLE_ROW");
        assertThat(JsonPath.<List<String>>read(body, "$[*].id"))
                .containsExactly(card, rows.get(0), rows.get(1), rows.get(2));
        assertThat(JsonPath.<List<String>>read(body, "$[*].name")).containsExactly("Pay by card",
                "Pay with a voucher", "Pay with a voucher", "Pay with a voucher");
        assertThat(JsonPath.<List<Object>>read(body, "$[*].tags")).containsExactly(List.of("@checkout", "@smoke"),
                List.of("@checkout", "@regression"), List.of("@checkout", "@regression"), List.of("@checkout"));
        assertThat(JsonPath.<List<Integer>>read(body, "$[*].line")).containsExactly(12, 28, 29, 33);
        assertThat(JsonPath.<List<String>>read(body, "$[1:].scenarioId")).containsOnly(outline);
        assertThat(JsonPath.<String>read(body, "$[0].scenarioId")).isEqualTo(card);
        assertThat(JsonPath.<Object>read(body, "$[0].exampleRowId")).isNull();
        assertThat(JsonPath.<List<String>>read(body, "$[1:].exampleRowId")).containsExactlyElementsOf(rows);
        Map<String, Object> given = JsonPath.read(body, "$[0].steps[0]");
        assertThat(given).containsEntry("keyword", "Given").containsEntry("text", "a basket with these items:")
                .containsEntry("docString", null);
        assertThat(JsonPath.<List<List<String>>>read(body, "$[0].steps[0].dataTable")).containsExactly(
                List.of("item", "price"), List.of("Mug", "8.00"), List.of("Shirt", "20.00"));
        assertThat(JsonPath.<List<String>>read(body, "$[0].steps[*].text"))
                .containsExactly("a basket with these items:", "the customer pays by card", "the receipt is:");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$[0].steps[2].docString"))
                .isEqualTo(Map.of("content", "Total: 28.00", "mediaType", "text"));
        assertThat(JsonPath.<List<String>>read(body, "$[3].steps[*].text")).containsExactly(
                "a basket with these items:", "the customer pays with voucher \"OLD5\"",
                "the result is \"rejected\"");
        assertThat(JsonPath.<List<String>>read(body, "$[3].steps[*].keyword")).containsExactly("Given", "When", "Then");
    }

    @Test
    @Tag("AC-4.2")
    @DisplayName("AC-4.2 Placeholders are replaced in the name, step text and doc string")
    void placeholdersAreReplaced() throws Exception {
        UUID greeting = created(mockMvc, GUS, CHECKOUT, "features/greeting.feature", GherkinSources.GREETING);

        String body = body(testCases(GUS, greeting));

        assertThat(JsonPath.<List<String>>read(body, "$[*].name")).containsExactly("Greet Ana");
        assertThat(JsonPath.<List<String>>read(body, "$[0].steps[*].text"))
                .containsExactly("the user \"Ana\"", "the greeting is:");
        assertThat(JsonPath.<String>read(body, "$[0].steps[1].docString.content")).isEqualTo("Hello, Ana!");
    }

    @Test
    @Tag("AC-4.3")
    @DisplayName("AC-4.3 Backgrounds and Outlines without rows produce no test case")
    void backgroundsAndEmptyOutlinesProduceNone() throws Exception {
        UUID none = created(mockMvc, GUS, CHECKOUT, "features/none.feature", "Feature: None\n  Background:\n"
                + "    Given a step\n  Scenario Outline: O\n    Given <x>\n    Examples:\n      | x |\n");

        assertThat(JsonPath.<List<Object>>read(body(testCases(GUS, none)), "$")).isEmpty();
    }

    @Test
    @Tag("AC-4.4")
    @DisplayName("AC-4.4 Test cases follow the current version and keep their ids")
    void testCasesFollowTheCurrentVersion() throws Exception {
        List<String> before = JsonPath.read(body(testCases(GUS, payment)), "$[*].id");

        save(mockMvc, GUS, CHECKOUT, payment, fields("path", PATH, "source", GherkinSources.PAYMENT_V2,
                "baseVersion", 1));

        String after = body(testCases(GUS, payment));
        assertThat(JsonPath.<List<String>>read(after, "$[*].name")).hasSize(5).element(1)
                .isEqualTo("Pay by bank transfer");
        List<String> afterIds = JsonPath.read(after, "$[*].id");
        assertThat(List.of(afterIds.get(0), afterIds.get(2), afterIds.get(3), afterIds.get(4)))
                .containsExactlyElementsOf(before);
        assertThat(before).doesNotContain(afterIds.get(1));
    }

    @ParameterizedTest(name = "AC-4.5 {0} reads test cases: {1}")
    @CsvSource({"dan,200", "ivy,403"})
    @Tag("AC-4.5")
    void everyoneWithAccessCanReadTestCases(String actor, int status) throws Exception {
        MvcResult result = testCases(actor.equals("dan") ? DAN : IVY, payment);

        if (status == 200) {
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
        } else {
            assertProblem(result, 403, "FORBIDDEN");
        }
    }

    @Test
    @Tag("AC-4.6")
    @DisplayName("AC-4.6 Absent names are empty strings, and an Examples block without a table is empty")
    void absentNamesAndEmptyExamples() throws Exception {
        UUID bare = created(mockMvc, GUS, CHECKOUT, "features/bare.feature", "Feature: Bare\n  Background:\n"
                + "    Given a step\n  Scenario Outline: O\n    Given <x>\n    Examples:\n");

        String feature = body(view(mockMvc, GUS, CHECKOUT, bare));
        assertThat(JsonPath.<Map<String, Object>>read(feature, "$.children[0]")).containsEntry("name", "")
                .containsEntry("description", "");
        assertThat(JsonPath.<Map<String, Object>>read(feature, "$.children[1].examples[0]")).containsEntry("name", "")
                .containsEntry("description", "").containsEntry("header", List.of()).containsEntry("rows", List.of());
        assertThat(JsonPath.<List<Object>>read(body(testCases(GUS, bare)), "$")).isEmpty();
    }

    @Test
    @DisplayName("D13: test cases of an unknown feature are 404, and nothing about them is stored")
    void unknownFeatureAndNothingStored() throws Exception {
        assertProblem(testCases(GUS, UUID.randomUUID()), 404, "FEATURE_NOT_FOUND");
        assertThat(data.count("scenarios")).isEqualTo(3);
        assertThat(data.count("example_rows")).isEqualTo(3);
    }

    private MvcResult testCases(User user, UUID featureId) throws Exception {
        return perform(mockMvc, user, get(features(CHECKOUT) + "/" + featureId + "/test-cases"));
    }
}
