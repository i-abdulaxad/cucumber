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
import org.scenarioservice.support.AdjustableClock;
import org.scenarioservice.support.FeatureData;
import org.scenarioservice.support.GherkinSources;
import org.scenarioservice.support.ScenarioFixture.User;
import org.scenarioservice.support.ScenarioServiceIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.scenarioservice.support.FeatureCalls.create;
import static org.scenarioservice.support.FeatureCalls.created;
import static org.scenarioservice.support.FeatureCalls.features;
import static org.scenarioservice.support.FeatureCalls.fields;
import static org.scenarioservice.support.FeatureCalls.perform;
import static org.scenarioservice.support.FeatureCalls.view;
import static org.scenarioservice.support.ProblemAssertions.assertProblem;
import static org.scenarioservice.support.ProblemAssertions.body;
import static org.scenarioservice.support.ScenarioFixture.ANA;
import static org.scenarioservice.support.ScenarioFixture.BILLING;
import static org.scenarioservice.support.ScenarioFixture.CAI;
import static org.scenarioservice.support.ScenarioFixture.CHECKOUT;
import static org.scenarioservice.support.ScenarioFixture.DAN;
import static org.scenarioservice.support.ScenarioFixture.EVE;
import static org.scenarioservice.support.ScenarioFixture.GUS;
import static org.scenarioservice.support.ScenarioFixture.HAL;
import static org.scenarioservice.support.ScenarioFixture.IVY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * US-1 (T8, end to end on the real endpoint): create a feature from Gherkin text.
 */
@ScenarioServiceIntegrationTest
class CreateFeatureTest {

    private static final String PAYMENT_PATH = "features/checkout/payment.feature";
    private static final String TIMESTAMP = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z$";

    private final AccessCheckStub stub = AccessCheckStub.get();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeatureData data;

    @Autowired
    private AdjustableClock clock;

    @BeforeEach
    void reset() {
        data.reset();
        stub.reset();
        clock.reset();
    }

    @Test
    @Tag("AC-1.1")
    @DisplayName("AC-1.1 A member creates a feature from valid Gherkin")
    void aMemberCreatesAFeature() throws Exception {
        MvcResult result = create(mockMvc, GUS, CHECKOUT, fields("path", PAYMENT_PATH, "source",
                GherkinSources.PAYMENT, "message", "First draft"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = body(result);
        String id = JsonPath.read(body, "$.id");
        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        Map<String, Object> feature = JsonPath.read(body, "$");
        assertThat(feature).containsEntry("projectId", CHECKOUT.toString()).containsEntry("path", PAYMENT_PATH)
                .containsEntry("name", "Payment").containsEntry("language", "en").containsEntry("currentVersion", 1)
                .containsEntry("createdBy", GUS.id().toString()).containsEntry("updatedBy", GUS.id().toString())
                .containsEntry("archivedAt", null).containsEntry("source", GherkinSources.PAYMENT)
                .containsKeys("createdAt", "updatedAt");
        assertThat(((String) feature.get("description")).trim()).isEqualTo("Customers pay for their basket.");
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/projects/" + CHECKOUT + "/features/" + id);
        MvcResult history = perform(mockMvc, GUS, get(features(CHECKOUT) + "/" + id + "/versions"));
        assertThat(JsonPath.<List<Map<String, Object>>>read(body(history), "$")).singleElement().satisfies(v -> {
            assertThat(v).containsEntry("version", 1).containsEntry("createdBy", GUS.id().toString())
                    .containsEntry("message", "First draft");
        });
    }

    @Test
    @Tag("AC-1.2")
    @DisplayName("AC-1.2 The feature's structure is parsed from the text")
    void theStructureIsParsed() throws Exception {
        UUID payment = created(mockMvc, GUS, CHECKOUT, PAYMENT_PATH, GherkinSources.PAYMENT);

        MvcResult result = view(mockMvc, DAN, CHECKOUT, payment);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = body(result);
        assertThat(JsonPath.<List<String>>read(body, "$.tags")).containsExactly("@checkout");
        assertThat(JsonPath.<List<String>>read(body, "$.children[*].kind"))
                .containsExactly("BACKGROUND", "SCENARIO", "RULE");
        assertThat(JsonPath.<List<String>>read(body, "$.children[*].keyword"))
                .containsExactly("Background", "Scenario", "Rule");
        assertThat(JsonPath.<List<String>>read(body, "$.children[*].name"))
                .containsExactly("", "Pay by card", "Vouchers");
        Map<String, Object> given = JsonPath.read(body, "$.children[0].steps[0]");
        assertThat(given).containsEntry("keyword", "Given").containsEntry("keywordType", "GIVEN")
                .containsEntry("text", "a basket with these items:").containsEntry("docString", null);
        assertThat(JsonPath.<List<List<String>>>read(body, "$.children[0].steps[0].dataTable")).containsExactly(
                List.of("item", "price"), List.of("Mug", "8.00"), List.of("Shirt", "20.00"));
        assertThat(JsonPath.<List<Object>>read(body, "$.children[0].steps")).hasSize(1);
        assertThat(JsonPath.<List<String>>read(body, "$.children[1].steps[*].keyword")).containsExactly("When", "Then");
        assertThat(JsonPath.<List<String>>read(body, "$.children[1].steps[*].text"))
                .containsExactly("the customer pays by card", "the receipt is:");
        assertThat(JsonPath.<Object>read(body, "$.children[1].steps[1].dataTable")).isNull();
        Map<String, Object> card = JsonPath.read(body, "$.children[1]");
        assertThat((String) card.get("id")).matches("[0-9a-f-]{36}");
        assertThat(card).containsEntry("tags", List.of("@smoke")).containsEntry("line", 12)
                .containsEntry("examples", List.of());
        assertThat(JsonPath.<List<String>>read(body, "$.children[1].steps[*].keywordType"))
                .containsExactly("WHEN", "THEN");
        assertThat(JsonPath.<Object>read(body, "$.children[1].steps[0].dataTable")).isNull();
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.children[1].steps[1].docString"))
                .isEqualTo(Map.of("content", "Total: 28.00", "mediaType", "text"));
        Map<String, Object> outline = JsonPath.read(body, "$.children[2].children[0]");
        assertThat(outline).containsEntry("kind", "SCENARIO_OUTLINE").containsEntry("keyword", "Scenario Outline")
                .containsEntry("name", "Pay with a voucher");
        assertThat(JsonPath.<List<String>>read(body, "$.children[2].children[0].steps[*].text")).containsExactly(
                "the customer pays with voucher \"<code>\"", "the result is \"<result>\"");
        assertThat(JsonPath.<List<String>>read(body, "$.children[2].children[0].examples[*].name"))
                .containsExactly("Valid vouchers", "Expired vouchers");
        assertThat(JsonPath.<List<Object>>read(body, "$.children[2].children[0].examples[*].tags"))
                .containsExactly(List.of("@regression"), List.of());
        assertThat(JsonPath.<List<Object>>read(body, "$.children[2].children[0].examples[*].header"))
                .containsExactly(List.of("code", "result"), List.of("code", "result"));
        assertThat(JsonPath.<List<Object>>read(body, "$.children[2].children[0].examples[*].rows[*].cells"))
                .containsExactly(List.of("SAVE10", "confirmed"), List.of("SAVE20", "confirmed"),
                        List.of("OLD5", "rejected"));
        assertThat(JsonPath.<List<Integer>>read(body, "$.children[2].children[0].examples[*].rows[*].line"))
                .containsExactly(28, 29, 33);
        assertThat(JsonPath.<List<String>>read(body, "$.children[2].children[0].examples[*].rows[*].id"))
                .allMatch(rowId -> rowId.matches("[0-9a-f-]{36}")).doesNotHaveDuplicates();
    }

    @ParameterizedTest(name = "AC-1.3 {0} creates: {1}")
    @CsvSource({"ana,201", "cai,201", "gus,201", "hal,403", "dan,403", "ivy,403", "eve,403"})
    @Tag("AC-1.3")
    void onlyMembersAndAdminsCanCreate(String actor, int status) throws Exception {
        User user = switch (actor) {
            case "ana" -> ANA;
            case "cai" -> CAI;
            case "gus" -> GUS;
            case "hal" -> HAL;
            case "dan" -> DAN;
            case "ivy" -> IVY;
            default -> EVE;
        };

        MvcResult result = create(mockMvc, user, CHECKOUT, "features/address.feature", GherkinSources.ADDRESS);

        if (status == 201) {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
        } else {
            assertProblem(result, 403, "FORBIDDEN");
            assertThat(data.stored()).isEqualTo("0 features, 0 versions, 0 tags");
        }
    }

    @ParameterizedTest(name = "AC-1.4 {0} is 422 at {1}:{2}")
    @CsvSource({"step before Feature,1,1", "inconsistent cells,5,7", "step after Examples,7,5",
            "unknown language,1,1", "no Feature,1,1"})
    @Tag("AC-1.4")
    void invalidGherkinIsRejectedWithLineAndColumn(String name, int line, int column) throws Exception {
        String source = switch (name) {
            case "step before Feature" -> GherkinSources.STEP_BEFORE_FEATURE;
            case "inconsistent cells" -> GherkinSources.INCONSISTENT_CELLS;
            case "step after Examples" -> GherkinSources.STEP_AFTER_EXAMPLES;
            case "unknown language" -> GherkinSources.UNKNOWN_LANGUAGE;
            default -> GherkinSources.NO_FEATURE;
        };

        MvcResult result = create(mockMvc, GUS, CHECKOUT, "features/broken.feature", source);

        Map<String, Object> problem = assertProblem(result, 422, "GHERKIN_INVALID");
        assertThat(parseErrors(problem)).anySatisfy(error -> {
            assertThat(error).containsEntry("line", line).containsEntry("column", column);
            assertThat((String) error.get("message")).isNotBlank();
        });
        assertThat(data.stored()).isEqualTo("0 features, 0 versions, 0 tags");
    }

    @Test
    @Tag("AC-1.5")
    @DisplayName("AC-1.5 Every parse error is reported, in line order")
    void everyParseErrorIsReported() throws Exception {
        String source = "Feature: Tables\n  Scenario: One\n    Given a table\n      | a | b |\n      | 1 |\n"
                + "  Scenario: Two\n    Given a table\n      | a | b |\n      | 1 |\n";

        List<Map<String, Object>> errors = parseErrors(assertProblem(
                create(mockMvc, GUS, CHECKOUT, "features/broken.feature", source), 422, "GHERKIN_INVALID"));

        assertThat(errors).extracting(error -> error.get("line")).contains(5, 9);
        assertThat(errors).extracting(error -> (Integer) error.get("line")).isSorted();
    }

    @ParameterizedTest(name = "AC-1.6 path {0}")
    @ValueSource(strings = {"(missing)", "", "/features/address.feature", "features//address.feature",
            "features/../address.feature", "features/./address.feature", "features/address.txt",
            "features\\address.feature", " features/address.feature", "features/add\u0007ress.feature",
            "(1,025 characters)"})
    @Tag("AC-1.6")
    void anInvalidPathIsAValidationError(String path) throws Exception {
        Map<String, Object> body = fields("source", GherkinSources.ADDRESS);
        if (path.equals("(1,025 characters)")) {
            body.put("path", "a".repeat(1009) + "/address.feature");
        } else if (!path.equals("(missing)")) {
            body.put("path", path);
        }

        assertValidationError(create(mockMvc, GUS, CHECKOUT, body), "path");
    }

    @ParameterizedTest(name = "AC-1.7 {0}")
    @CsvSource(delimiter = '|', value = {"no source|source", "empty source|source", "1,000,001 code points|source",
            "U+0000 after Address|source", "unpaired U+D800|source", "message of 501|message",
            "message with U+0007|message"})
    @Tag("AC-1.7")
    void invalidSourceAndMessageValues(String row, String field) throws Exception {
        Map<String, Object> body = fields("path", "features/address.feature", "source", GherkinSources.ADDRESS);
        switch (row) {
            case "no source" -> body.remove("source");
            case "empty source" -> body.put("source", "");
            case "1,000,001 code points" -> body.put("source", "Feature: A\n#" + "x".repeat(1_000_001 - 12));
            case "U+0000 after Address" -> body.put("source",
                    GherkinSources.ADDRESS.replace("Address", "Address\u0000"));
            case "unpaired U+D800" -> body.put("source", GherkinSources.ADDRESS.replace("adds", "ad\uD800ds"));
            case "message of 501" -> body.put("message", "m".repeat(501));
            default -> body.put("message", "first\u0007draft");
        }

        assertValidationError(create(mockMvc, GUS, CHECKOUT, body), field);
    }

    @Test
    @Tag("AC-1.7")
    @DisplayName("AC-1.7 limits count code points: 501 non-ASCII characters are too many, 500 emoji are fine")
    void limitsCountCodePointsForNonAsciiText() throws Exception {
        assertValidationError(create(mockMvc, GUS, CHECKOUT, fields("path", "features/address.feature", "source",
                GherkinSources.ADDRESS, "message", "\u00E9".repeat(501))), "message");

        MvcResult result = create(mockMvc, GUS, CHECKOUT, fields("path", "features/address.feature", "source",
                GherkinSources.ADDRESS, "message", "\uD83D\uDE00".repeat(500)));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @ParameterizedTest(name = "AC-1.8 the path {0} is taken")
    @ValueSource(strings = {PAYMENT_PATH, "Features/Checkout/PAYMENT.feature"})
    @Tag("AC-1.8")
    void aPathCantRepeatWhateverTheCase(String path) throws Exception {
        created(mockMvc, GUS, CHECKOUT, PAYMENT_PATH, GherkinSources.PAYMENT);

        assertProblem(create(mockMvc, ANA, CHECKOUT, path, GherkinSources.ADDRESS), 409, "FEATURE_PATH_TAKEN");
        assertThat(data.count("features")).isEqualTo(1);
    }

    @Test
    @Tag("AC-1.9")
    @DisplayName("AC-1.9 The same path is allowed in another project")
    void theSamePathInAnotherProject() throws Exception {
        created(mockMvc, GUS, CHECKOUT, PAYMENT_PATH, GherkinSources.PAYMENT);

        MvcResult result = create(mockMvc, ANA, BILLING, PAYMENT_PATH, GherkinSources.PAYMENT);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(JsonPath.<String>read(body(result), "$.projectId")).isEqualTo(BILLING.toString());
    }

    @Test
    @Tag("AC-1.11")
    @DisplayName("AC-1.11 Any text the Gherkin parser accepts can be stored")
    void anEmptyFeatureIsStored() throws Exception {
        MvcResult result = create(mockMvc, GUS, CHECKOUT, "features/empty.feature", "Feature:\n");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(JsonPath.<String>read(body(result), "$.name")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(body(result), "$.children")).isEmpty();
    }

    @Test
    @Tag("AC-1.12")
    @DisplayName("AC-1.12 Features in other Gherkin languages are parsed with that language's keywords")
    void aFrenchFeature() throws Exception {
        MvcResult result = create(mockMvc, GUS, CHECKOUT, "features/paiement.feature", GherkinSources.FRENCH);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String body = body(result);
        assertThat(JsonPath.<String>read(body, "$.language")).isEqualTo("fr");
        assertThat(JsonPath.<String>read(body, "$.name")).isEqualTo("Paiement");
        assertThat(JsonPath.<List<String>>read(body, "$.children[0].steps[*].keyword"))
                .containsExactly("Soit", "Alors");
        assertThat(JsonPath.<List<String>>read(body, "$.children[0].steps[*].keywordType"))
                .containsExactly("GIVEN", "THEN");
        assertThat(JsonPath.<List<String>>read(body, "$.children[0].steps[*].text"))
                .containsExactly("un panier", "la commande est confirm\u00E9e");
    }

    @ParameterizedTest(name = "AC-1.13 the source \"{0}\" has no Feature")
    @ValueSource(strings = {"   \n", "\n\n# only a comment\n"})
    @Tag("AC-1.13")
    void aTextWithoutAFeature(String source) throws Exception {
        Map<String, Object> problem = assertProblem(create(mockMvc, GUS, CHECKOUT, "features/none.feature", source),
                422, "GHERKIN_INVALID");

        assertThat(parseErrors(problem)).containsExactly(Map.of("line", 1, "column", 1, "message",
                "The text has no Feature"));
        assertThat(data.stored()).isEqualTo("0 features, 0 versions, 0 tags");
    }

    @ParameterizedTest(name = "AC-1.14 path {0}")
    @ValueSource(strings = {"features/address.FEATURE", "features/.feature", "features/add\u0085ress.feature"})
    @Tag("AC-1.14")
    void suffixAndControlCharacterRules(String path) throws Exception {
        assertValidationError(create(mockMvc, GUS, CHECKOUT, path, GherkinSources.ADDRESS), "path");
    }

    @Test
    @Tag("AC-1.15")
    @DisplayName("AC-1.15 Parser errors replace the \"no Feature\" entry")
    void parserErrorsReplaceNoFeature() throws Exception {
        List<Map<String, Object>> errors = parseErrors(assertProblem(
                create(mockMvc, GUS, CHECKOUT, "features/step.feature", "Given a step\n"), 422, "GHERKIN_INVALID"));

        assertThat(errors).anySatisfy(error -> assertThat(error).containsEntry("line", 1).containsEntry("column", 1));
        assertThat(errors).noneMatch(error -> "The text has no Feature".equals(error.get("message")));
    }

    @ParameterizedTest(name = "AC-1.16 {0}")
    @CsvSource(delimiter = '|', value = {"features/a\uD800b.feature|path", "'\u00A0features/address.feature'|path",
            "\u2060features/address.feature|path", "features/ /address.feature|path",
            "features/\u200B/address.feature|path", "features/\u00A0\u2007.feature|path",
            "message a\uDC00b|message", "message a\u0000b|message"})
    @Tag("AC-1.16")
    void unstorableTextAndInvisiblePathParts(String change, String field) throws Exception {
        Map<String, Object> body = fields("path", "features/address.feature", "source", GherkinSources.ADDRESS);
        String value = change.startsWith("message ") ? change.substring(8) : change;
        body.put(field, value);

        MvcResult result = create(mockMvc, GUS, CHECKOUT, body);

        Map<String, Object> problem = assertValidationError(result, field);
        assertThat(body(result)).doesNotContain(value);
        assertThat(problem.get("detail").toString()).doesNotContain(value);
    }

    @ParameterizedTest(name = "AC-1.17 {0} then {1}: {2}")
    @CsvSource({"features/\u0130zmir.feature,features/i\u0307zmir.feature,409",
            "features/\u0130zmir.feature,features/izmir.feature,201",
            "features/\u2C2F.feature,features/\u2C5F.feature,409",
            "deseret,deseret,409"})
    @Tag("AC-1.17")
    void pathsAreComparedWithIcuRootLowerCase(String existing, String candidate, int status) throws Exception {
        if (existing.equals("deseret")) {
            existing = "features/" + cycle(0x10400, 0x10426, 1007) + ".feature";
            candidate = "features/" + cycle(0x10428, 0x1044E, 1007) + ".feature";
            assertThat(existing.codePointCount(0, existing.length())).isEqualTo(1024);
        }
        created(mockMvc, GUS, CHECKOUT, existing, GherkinSources.ADDRESS);

        MvcResult result = create(mockMvc, GUS, CHECKOUT, candidate, GherkinSources.ADDRESS);

        if (status == 409) {
            assertProblem(result, 409, "FEATURE_PATH_TAKEN");
            assertThat(data.count("features")).isEqualTo(1);
        } else {
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
        }
    }

    @ParameterizedTest(name = "AC-1.18 the clock at {0}")
    @ValueSource(strings = {"2026-10-14T09:30:00.123456Z", "2026-10-14T09:30:00Z"})
    @Tag("AC-1.18")
    void timestampsComeFromTheServiceClock(String instant) throws Exception {
        Instant now = Instant.parse(instant);
        clock.set(now);
        MvcResult result;
        MvcResult history;
        try {
            result = create(mockMvc, GUS, CHECKOUT, PAYMENT_PATH, GherkinSources.PAYMENT);
            history = perform(mockMvc, GUS, get(features(CHECKOUT) + "/"
                    + JsonPath.read(body(result), "$.id") + "/versions"));
        } finally {
            clock.reset();
        }

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        for (String field : List.of("createdAt", "updatedAt")) {
            String text = JsonPath.read(body(result), "$." + field);
            assertThat(text).matches(TIMESTAMP);
            assertThat(Instant.parse(text)).isEqualTo(now);
        }
        assertThat(Instant.parse(JsonPath.<String>read(body(history), "$[0].createdAt"))).isEqualTo(now);
    }

    @Test
    @DisplayName("D20 follow-up 2: \"message\": null means no message")
    void aNullMessageIsNoMessage() throws Exception {
        MvcResult result = create(mockMvc, GUS, CHECKOUT, fields("path", PAYMENT_PATH, "source",
                GherkinSources.PAYMENT, "message", null));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        MvcResult history = perform(mockMvc, GUS, get(features(CHECKOUT) + "/" + JsonPath.read(body(result), "$.id")
                + "/versions"));
        assertThat(JsonPath.<Object>read(body(history), "$[0].message")).isNull();
    }

    @Test
    @DisplayName("D19 #9: the message is trimmed of Unicode-blank characters; nothing left is no message")
    void theMessageIsTrimmed() throws Exception {
        MvcResult first = create(mockMvc, GUS, CHECKOUT, fields("path", PAYMENT_PATH, "source",
                GherkinSources.PAYMENT, "message", " \u00A0First draft\u200B\n"));
        MvcResult second = create(mockMvc, GUS, CHECKOUT, fields("path", "features/address.feature", "source",
                GherkinSources.ADDRESS, "message", " \u00A0\u200B "));

        assertThat(message(first)).isEqualTo("First draft");
        assertThat(message(second)).isNull();
    }

    private String message(MvcResult created) throws Exception {
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        MvcResult history = perform(mockMvc, GUS, get(features(CHECKOUT) + "/"
                + JsonPath.read(body(created), "$.id") + "/versions"));
        return JsonPath.read(body(history), "$[0].message");
    }

    private Map<String, Object> assertValidationError(MvcResult result, String field) {
        Map<String, Object> problem = assertProblem(result, 400, "VALIDATION_FAILED");
        assertThat(errorFields(problem)).contains(field);
        assertThat(data.stored()).isEqualTo("0 features, 0 versions, 0 tags");
        return problem;
    }

    @SuppressWarnings("unchecked")
    static List<String> errorFields(Map<String, Object> problem) {
        return ((List<Map<String, Object>>) problem.get("errors")).stream().map(e -> (String) e.get("field")).toList();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> parseErrors(Map<String, Object> problem) {
        return (List<Map<String, Object>>) problem.get("parseErrors");
    }

    static String cycle(int first, int last, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.appendCodePoint(first + i % (last - first + 1));
        }
        return text.toString();
    }
}
