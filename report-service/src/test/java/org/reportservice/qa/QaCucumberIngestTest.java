package org.reportservice.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reportservice.enums.ExecutionStatus;
import org.reportservice.enums.StepResultKind;
import org.reportservice.ingest.CucumberJsonException;
import org.reportservice.ingest.CucumberJsonReader;
import org.reportservice.ingest.IngestedRow;
import org.reportservice.ingest.IngestedTestCase;
import org.reportservice.ingest.JsonProblem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * qa, independent of backend-swe's {@code CucumberJsonReaderTest}: edge cases of the D9 subset (BD-10 to BD-13) on the
 * pure reader, no Spring context. Every invalid case asserts the exact JSONPath pointer.
 */
class QaCucumberIngestTest {

    private static final List<String> WORST_FIRST =
            List.of("failed", "ambiguous", "undefined", "pending", "skipped", "passed");

    private final CucumberJsonReader reader = new CucumberJsonReader();

    // ---------- status rollup and duration (AC-2.3, D9) ----------

    @Test
    @Tag("AC-2.3")
    @DisplayName("AC-2.3 qa: every ordered pair of the 6 statuses rolls up to the worse one")
    void everyOrderedPairRollsUpToTheWorse() {
        for (String first : WORST_FIRST) {
            for (String second : WORST_FIRST) {
                String worse = WORST_FIRST.indexOf(first) <= WORST_FIRST.indexOf(second) ? first : second;
                IngestedTestCase testCase = single(scenario(step("s1", first, null) + "," + step("s2", second, null)));
                assertThat(testCase.status()).as(first + " + " + second)
                        .isEqualTo(ExecutionStatus.valueOf(worse.toUpperCase()));
            }
        }
    }

    @Test
    @Tag("AC-2.3")
    @DisplayName("AC-2.3 qa: a hook-only scenario with a failed after hook is FAILED")
    void aHookOnlyScenarioIsRolledUp() {
        IngestedTestCase testCase = single("{\"type\":\"scenario\",\"name\":\"h\","
                + "\"after\":[{\"result\":{\"status\":\"failed\"}}]}");
        assertThat(testCase.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(testCase.durationMs()).isNull();
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("D9 qa: the test-case duration is the floor of the summed nanoseconds, not the sum of floors")
    void durationIsTheFloorOfTheSum() {
        IngestedTestCase testCase = single(scenario(step("a", "passed", 999_999L) + "," + step("b", "passed", 999_999L)));
        assertThat(testCase.rows()).extracting(IngestedRow::durationMs).containsExactly(0L, 0L);
        assertThat(testCase.durationMs()).isEqualTo(1L);
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("D9 qa: one row without a duration doesn't null the total; a zero duration is 0, not null")
    void missingAndZeroDurations() {
        assertThat(single(scenario(step("a", "passed", 1_500_000L) + "," + step("b", "passed", null))).durationMs())
                .isEqualTo(1L);
        IngestedTestCase zero = single(scenario(step("a", "passed", 0L)));
        assertThat(zero.durationMs()).isZero();
        assertThat(zero.rows().get(0).durationMs()).isZero();
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("D9 qa: one Long.MAX duration is read exactly")
    void oneMaximalDurationIsRead() {
        IngestedTestCase testCase = single(scenario(step("a", "passed", Long.MAX_VALUE)));
        assertThat(testCase.durationMs()).isEqualTo(Long.MAX_VALUE / 1_000_000L);
    }

    @Test
    @Tag("AC-2.8")
    @DisplayName("RE11 qa: an overflowing sum through hooks, on the second element, is invalid at that element only")
    void durationOverflowNamesTheElement() {
        String max = String.valueOf(Long.MAX_VALUE);
        String json = feature(scenario(step("ok", "passed", 1L)) + ",{\"type\":\"scenario\",\"name\":\"y\","
                + "\"before\":[{\"result\":{\"status\":\"passed\",\"duration\":" + max + "}}],"
                + "\"steps\":[" + step("s", "passed", 1L) + "]}");
        assertThat(invalid(json)).extracting(JsonProblem::pointer).containsExactly("$[0].elements[1]");
    }

    @Test
    @Tag("AC-2.8")
    @DisplayName("RE11 qa: a background plus a step that together overflow are invalid at the scenario")
    void backgroundDurationOverflow() {
        String json = feature("{\"type\":\"background\",\"steps\":[" + step("bg", "passed", Long.MAX_VALUE) + "]},"
                + scenario(step("s", "passed", 1L)));
        assertThat(invalid(json)).extracting(JsonProblem::pointer).containsExactly("$[0].elements[1]");
    }

    @ParameterizedTest(name = "D9 qa: duration {0} is invalid")
    @CsvSource(delimiter = '|', value = {"1.5", "\"100\"", "1e3", "9223372036854775808", "-1", "null"})
    @Tag("AC-2.8")
    void nonIntegralDurationsAreInvalid(String duration) {
        String json = feature("{\"type\":\"scenario\",\"name\":\"x\",\"steps\":[{\"keyword\":\"Given \",\"name\":\"a\","
                + "\"result\":{\"status\":\"passed\",\"duration\":" + duration + "}}]}");
        assertInvalid(json, "$[0].elements[0].steps[0].result.duration");
    }

    // ---------- mapping (AC-2.2, AC-2.13, D9) ----------

    @Test
    @Tag("AC-2.2")
    @DisplayName("D9 qa: a background goes only to the next scenario of its feature; a trailing one adds nothing")
    void backgroundAttachment() {
        String background = "{\"type\":\"background\",\"steps\":[" + step("bg", "passed", null) + "]}";
        String json = "[{\"uri\":\"one.feature\",\"elements\":[" + background + "," + scenario(step("s1", "passed", null))
                + "," + scenario(step("s2", "passed", null)) + "," + background + "]},"
                + "{\"uri\":\"two.feature\",\"elements\":[" + scenario(step("s3", "passed", null)) + "]}]";
        List<IngestedTestCase> cases = reader.read(bytes(json));
        assertThat(cases).hasSize(3);
        assertThat(cases.get(0).rows()).extracting(IngestedRow::text).containsExactly("bg", "s1");
        assertThat(cases.get(1).rows()).extracting(IngestedRow::text).containsExactly("s2");
        assertThat(cases.get(2).rows()).extracting(IngestedRow::text).containsExactly("s3");
        assertThat(cases.get(2).featurePath()).isEqualTo("two.feature");
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("D9 qa: rows are element before hooks, background, steps with their step hooks, element after hooks")
    void fullRowOrder() {
        String json = feature("{\"type\":\"background\",\"steps\":[" + step("bg", "passed", null) + "]},"
                + "{\"type\":\"scenario\",\"name\":\"x\","
                + "\"before\":[{\"result\":{\"status\":\"passed\"}},{\"result\":{\"status\":\"passed\"}}],"
                + "\"steps\":[{\"keyword\":\"Given \",\"name\":\"one\",\"result\":{\"status\":\"passed\"},"
                + "\"before\":[{\"result\":{\"status\":\"passed\"}}],\"after\":[{\"result\":{\"status\":\"passed\"}}]},"
                + step("two", "passed", null) + "],"
                + "\"after\":[{\"result\":{\"status\":\"passed\"}},{\"result\":{\"status\":\"failed\"}}]}");
        IngestedTestCase testCase = single(reader.read(bytes(json)));
        assertThat(testCase.rows()).extracting(IngestedRow::kind).containsExactly(
                StepResultKind.BEFORE_HOOK, StepResultKind.BEFORE_HOOK, StepResultKind.STEP,
                StepResultKind.BEFORE_HOOK, StepResultKind.STEP, StepResultKind.AFTER_HOOK, StepResultKind.STEP,
                StepResultKind.AFTER_HOOK, StepResultKind.AFTER_HOOK);
        assertThat(testCase.rows()).extracting(IngestedRow::text)
                .containsExactly(null, null, "bg", null, "one", null, "two", null, null);
        assertThat(testCase.status()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    @Tag("AC-2.13")
    @DisplayName("AC-2.13 qa: tags dedupe exactly (case-sensitive), feature tags first")
    void tagsDedupeCaseSensitively() {
        String json = "[{\"uri\":\"f\",\"tags\":[{\"name\":\"@a\"}],\"elements\":[{\"type\":\"scenario\",\"name\":\"x\","
                + "\"tags\":[{\"name\":\"@a\"},{\"name\":\"@b\"},{\"name\":\"@A\"},{\"name\":\"@a\"}],\"steps\":[]}]}]";
        assertThat(single(reader.read(bytes(json))).tags()).containsExactly("@a", "@b", "@A");
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("D9 qa: scenario_outline is a scenario; two elements with one id are two test cases")
    void outlinesAndDuplicateIds() {
        String json = feature("{\"type\":\"scenario_outline\",\"id\":\"same\",\"name\":\"o\",\"steps\":[]},"
                + "{\"type\":\"scenario\",\"id\":\"same\",\"name\":\"o\",\"steps\":[]}");
        List<IngestedTestCase> cases = reader.read(bytes(json));
        assertThat(cases).hasSize(2).allSatisfy(c -> assertThat(c.status()).isEqualTo(ExecutionStatus.UNKNOWN));
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("D9 qa: only trailing keyword whitespace is trimmed")
    void keywordTrimming() {
        String json = feature(scenario("{\"keyword\":\"* \",\"name\":\"a\",\"result\":{\"status\":\"passed\"}},"
                + "{\"keyword\":\" Given\\t\",\"name\":\"b\",\"result\":{\"status\":\"passed\"}}"));
        assertThat(single(reader.read(bytes(json))).rows()).extracting(IngestedRow::keyword)
                .containsExactly("*", " Given");
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("D9 qa: ragged and empty table rows are stored as given; an empty doc string stays empty")
    void tablesAndDocStrings() {
        String json = feature(scenario("{\"keyword\":\"Given \",\"name\":\"t\",\"result\":{\"status\":\"passed\"},"
                + "\"rows\":[{\"cells\":[\"a\",\"b\"]},{\"cells\":[\"c\",\"d\",\"e\"]},{\"cells\":[]}],"
                + "\"doc_string\":{\"value\":\"\"}}"));
        IngestedRow row = single(reader.read(bytes(json))).rows().get(0);
        assertThat(row.dataTable()).containsExactly(List.of("a", "b"), List.of("c", "d", "e"), List.of());
        assertThat(row.docString()).isEmpty();
    }

    @Test
    @Tag("AC-2.12")
    @DisplayName("AC-2.12 qa: error_message of 70,000 astral code points keeps 65,536 code points and no half pair")
    void errorMessageTruncatesByCodePoint() {
        String message = "\uD83D\uDE00".repeat(70_000);
        String json = feature(scenario("{\"keyword\":\"Then \",\"name\":\"x\",\"result\":{\"status\":\"failed\","
                + "\"error_message\":\"" + message + "\"}}"));
        String stored = single(reader.read(bytes(json))).rows().get(0).errorMessage();
        assertThat(stored.codePointCount(0, stored.length())).isEqualTo(65_536);
        assertThat(stored).isEqualTo("\uD83D\uDE00".repeat(65_536));
    }

    @Test
    @Tag("AC-2.8")
    @DisplayName("D9 qa: uri of 4,096 astral code points is read; 4,097 and empty are invalid")
    void uriLengthCountsCodePoints() {
        String ok = "\uD83D\uDE00".repeat(4_096);
        assertThat(single(reader.read(bytes("[{\"uri\":\"" + ok + "\",\"elements\":[" + scenario("") + "]}]")))
                .featurePath()).isEqualTo(ok);
        assertInvalid("[{\"uri\":\"" + ok + "a\",\"elements\":[" + scenario("") + "]}]", "$[0].uri");
        assertInvalid("[{\"uri\":\"\",\"elements\":[" + scenario("") + "]}]", "$[0].uri");
    }

    // ---------- strict on what is read (AC-2.8, BD-10) ----------

    @ParameterizedTest(name = "AC-2.8 qa: {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "{\"type\":\"scenario\",\"name\":\"x\",\"line\":0}                         | $[0].elements[0].line",
            "{\"type\":\"scenario\",\"name\":\"x\",\"line\":-1}                        | $[0].elements[0].line",
            "{\"type\":\"scenario\",\"name\":\"x\",\"line\":\"12\"}                    | $[0].elements[0].line",
            "{\"type\":\"scenario\",\"name\":\"x\",\"line\":1.0}                       | $[0].elements[0].line",
            "{\"type\":\"scenario\",\"name\":\"x\",\"tags\":{}}                        | $[0].elements[0].tags",
            "{\"type\":\"scenario\",\"name\":\"x\",\"tags\":[{\"name\":5}]}            | $[0].elements[0].tags[0].name",
            "{\"type\":\"scenario\",\"name\":\"x\",\"tags\":[{\"line\":1}]}            | $[0].elements[0].tags[0].name",
            "{\"type\":\"Scenario\",\"name\":\"x\"}                                    | $[0].elements[0].type",
            "{\"name\":\"x\"}                                                          | $[0].elements[0].type",
            "{\"type\":\"scenario\",\"steps\":\"x\"}                                   | $[0].elements[0].steps",
            "{\"type\":\"scenario\",\"steps\":[{\"keyword\":\"Given \",\"name\":\"a\"}]} | $[0].elements[0].steps[0].result",
            "{\"type\":\"scenario\",\"steps\":[{\"keyword\":\"Given \",\"name\":\"a\",\"result\":{}}]} | $[0].elements[0].steps[0].result.status",
            "{\"type\":\"scenario\",\"steps\":[{\"keyword\":\"Given \",\"name\":\"a\",\"result\":{\"status\":\"passed\",\"error_message\":\"\\uD800\"}}]} | $[0].elements[0].steps[0].result.error_message",
            "{\"type\":\"scenario\",\"steps\":[{\"keyword\":\"Given \",\"name\":\"a\",\"result\":{\"status\":\"passed\"},\"rows\":[{\"cells\":[\"a\",\"b\\u0000\"]}]}]} | $[0].elements[0].steps[0].rows[0].cells[1]",
            "{\"type\":\"scenario\",\"steps\":[{\"keyword\":\"Given \",\"name\":\"a\",\"result\":{\"status\":\"passed\"},\"rows\":[{\"cells\":[\"a\",7]}]}]} | $[0].elements[0].steps[0].rows[0].cells[1]",
            "{\"type\":\"scenario\",\"steps\":[{\"keyword\":\"Given \",\"name\":\"a\",\"result\":{\"status\":\"passed\"},\"doc_string\":{\"value\":\"\\u0000\"}}]} | $[0].elements[0].steps[0].doc_string.value",
            "{\"type\":\"scenario\",\"steps\":[{\"keyword\":\"Given \",\"name\":\"a\",\"result\":{\"status\":\"passed\"},\"doc_string\":{}}]} | $[0].elements[0].steps[0].doc_string.value",
            "{\"type\":\"scenario\",\"name\":null}                                     | $[0].elements[0].name",
            "{\"type\":\"scenario\",\"steps\":null}                                    | $[0].elements[0].steps"})
    @Tag("AC-2.8")
    void strictReadMembers(String element, String pointer) {
        assertInvalid(feature(element), pointer);
    }

    @Test
    @Tag("AC-2.8")
    @DisplayName("AC-2.8 qa: a U+0000 in the uri is invalid at $[0].uri")
    void nulInUri() {
        assertInvalid("[{\"uri\":\"a\\u0000\",\"elements\":[" + scenario("") + "]}]", "$[0].uri");
    }

    @Test
    @Tag("AC-2.12")
    @DisplayName("D9 qa: U+0000 and lone surrogates inside members that aren't read are ignored")
    void unreadMembersAreNotValidated() {
        String json = "[{\"uri\":\"f\",\"id\":\"\\u0000\",\"comments\":[{\"value\":\"\\uD800\"}],\"elements\":["
                + "{\"type\":\"scenario\",\"name\":\"x\",\"id\":\"\\uDC00\",\"steps\":[{\"keyword\":\"Given \","
                + "\"name\":\"a\",\"match\":{\"location\":\"\\u0000\"},\"embeddings\":[{\"data\":\"\\uD800\"}],"
                + "\"result\":{\"status\":\"passed\"}}]}],\"extra\":{\"deep\":[1,2,3]}}]";
        assertThat(single(reader.read(bytes(json))).status()).isEqualTo(ExecutionStatus.PASSED);
    }

    @Test
    @Tag("AC-2.8")
    @DisplayName("AC-2.8 qa: 30 independent errors give exactly the first 20, in document order")
    void atMostTwentyInDocumentOrder() {
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            elements.add("{\"type\":\"scenario\",\"name\":" + i + "}");
        }
        List<JsonProblem> problems = invalid(feature(String.join(",", elements)));
        assertThat(problems).hasSize(20);
        for (int i = 0; i < 20; i++) {
            assertThat(problems.get(i).pointer()).isEqualTo("$[0].elements[" + i + "].name");
        }
    }

    @ParameterizedTest(name = "AC-2.8 qa: top level {0} is invalid at $")
    @CsvSource(delimiter = '|', value = {"{\"uri\":\"a\"}", "42", "\"x\"", "null", "true"})
    @Tag("AC-2.8")
    void topLevelNonArrays(String body) {
        assertInvalid(body, "$");
    }

    @Test
    @Tag("AC-2.8")
    @DisplayName("AC-2.8 qa: trailing garbage, invalid UTF-8 and an empty body are malformed")
    void malformedBodies() {
        assertReason("[]]".getBytes(StandardCharsets.UTF_8), CucumberJsonException.Reason.MALFORMED);
        assertReason("[] x".getBytes(StandardCharsets.UTF_8), CucumberJsonException.Reason.MALFORMED);
        assertReason(new byte[0], CucumberJsonException.Reason.MALFORMED);
        byte[] badUtf8 = ("[{\"uri\":\"" + "x\"}]").getBytes(StandardCharsets.UTF_8);
        byte[] withInvalid = new byte[badUtf8.length + 2];
        System.arraycopy(badUtf8, 0, withInvalid, 0, 9);
        withInvalid[9] = (byte) 0xC3;
        withInvalid[10] = (byte) 0x28;
        System.arraycopy(badUtf8, 9, withInvalid, 11, badUtf8.length - 9);
        assertReason(withInvalid, CucumberJsonException.Reason.MALFORMED);
    }

    @Test
    @Tag("AC-2.8")
    @DisplayName("AC-2.8 qa: nesting 2,000 deep in an ignored member is a typed rejection, never a crash")
    void deepNestingIsRejectedCleanly() {
        String deep = "[".repeat(2_000) + "]".repeat(2_000);
        String json = "[{\"uri\":\"f\",\"extra\":" + deep + ",\"elements\":[" + scenario("") + "]}]";
        Throwable thrown = catchThrowable(json);
        assertThat(thrown).isInstanceOf(CucumberJsonException.class);
        assertThat(((CucumberJsonException) thrown).reason()).isEqualTo(CucumberJsonException.Reason.MALFORMED);
    }

    // ---------- Cucumber-JS 13 forms (AC-2.15, BD-11, BD-12) ----------

    @Test
    @Tag("AC-2.15")
    @DisplayName("AC-2.15 qa: a hidden After hook between steps stays in its place")
    void hiddenHookBetweenSteps() {
        String json = feature(scenario(step("a", "passed", null)
                + ",{\"hidden\":true,\"keyword\":\"After\",\"result\":{\"status\":\"passed\",\"duration\":400000}},"
                + step("b", "passed", null)));
        IngestedTestCase testCase = single(reader.read(bytes(json)));
        assertThat(testCase.rows()).extracting(IngestedRow::kind)
                .containsExactly(StepResultKind.STEP, StepResultKind.AFTER_HOOK, StepResultKind.STEP);
        assertThat(testCase.rows().get(1).durationMs()).isZero();
        assertThat(testCase.rows().get(1).keyword()).isNull();
    }

    @ParameterizedTest(name = "AC-2.15 qa: {0} without name -> .name")
    @CsvSource(delimiter = '|', value = {
            "{\"hidden\":false,\"keyword\":\"Before\",\"result\":{\"status\":\"passed\"}}",
            "{\"hidden\":true,\"keyword\":\"Given \",\"result\":{\"status\":\"passed\"}}",
            "{\"keyword\":\"After\",\"result\":{\"status\":\"passed\"}}"})
    @Tag("AC-2.15")
    void onlyHiddenHookKeywordsMayOmitTheName(String step) {
        assertInvalid(feature(scenario(step)), "$[0].elements[0].steps[0].name");
    }

    @ParameterizedTest(name = "AC-2.15 qa: arguments {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "{}                         | $[0].elements[0].steps[0].arguments",
            "[{\"content\":5}]          | $[0].elements[0].steps[0].arguments[0].content",
            "[{\"rows\":[{\"cells\":[1]}]}] | $[0].elements[0].steps[0].arguments[0].rows[0].cells[0]"})
    @Tag("AC-2.15")
    void invalidArguments(String arguments, String pointer) {
        assertInvalid(feature(scenario("{\"keyword\":\"Given \",\"name\":\"a\",\"result\":{\"status\":\"passed\"},"
                + "\"arguments\":" + arguments + "}")), pointer);
    }

    @Test
    @Tag("AC-2.15")
    @DisplayName("AC-2.15 qa: an unrelated arguments entry is ignored")
    void unrelatedArgumentsAreIgnored() {
        IngestedRow row = single(reader.read(bytes(feature(scenario("{\"keyword\":\"Given \",\"name\":\"a\","
                + "\"result\":{\"status\":\"passed\"},\"arguments\":[{\"foo\":1},7]}"))))).rows().get(0);
        assertThat(row.dataTable()).isNull();
        assertThat(row.docString()).isNull();
    }

    // ---------- helpers ----------

    private IngestedTestCase single(String element) {
        return single(reader.read(bytes(feature(element))));
    }

    private static IngestedTestCase single(List<IngestedTestCase> cases) {
        assertThat(cases).hasSize(1);
        return cases.get(0);
    }

    private static String feature(String elements) {
        return "[{\"uri\":\"features/qa.feature\",\"name\":\"QA\",\"elements\":[" + elements + "]}]";
    }

    private static String scenario(String steps) {
        return "{\"type\":\"scenario\",\"name\":\"x\",\"steps\":[" + steps + "]}";
    }

    private static String step(String name, String status, Long duration) {
        return "{\"keyword\":\"Given \",\"name\":\"" + name + "\",\"result\":{\"status\":\"" + status + "\""
                + (duration == null ? "" : ",\"duration\":" + duration) + "}}";
    }

    private void assertInvalid(String json, String pointer) {
        assertThat(invalid(json)).extracting(JsonProblem::pointer).contains(pointer);
    }

    private List<JsonProblem> invalid(String json) {
        Throwable thrown = catchThrowable(json);
        assertThat(thrown).as(json).isInstanceOf(CucumberJsonException.class);
        CucumberJsonException ex = (CucumberJsonException) thrown;
        assertThat(ex.reason()).isEqualTo(CucumberJsonException.Reason.INVALID);
        assertThat(ex.problems()).hasSizeLessThanOrEqualTo(20);
        return ex.problems();
    }

    private void assertReason(byte[] body, CucumberJsonException.Reason reason) {
        CucumberJsonException ex = catchThrowableOfType(CucumberJsonException.class, () -> reader.read(body));
        assertThat(ex).isNotNull();
        assertThat(ex.reason()).isEqualTo(reason);
    }

    private Throwable catchThrowable(String json) {
        try {
            reader.read(bytes(json));
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
