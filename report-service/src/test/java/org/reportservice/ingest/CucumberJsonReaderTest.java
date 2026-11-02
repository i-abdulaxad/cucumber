package org.reportservice.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reportservice.enums.ExecutionStatus;
import org.reportservice.enums.StepResultKind;
import org.reportservice.support.CiSample;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T6 (D9, contract C8): the Cucumber JSON reader, without Spring. The rollup outline (AC-2.3), the Cucumber-JS 13
 * forms (AC-2.15), invalid input (AC-2.8), what phase 1 ignores or shortens (AC-2.12), tags (AC-2.13), the mapping
 * part of AC-2.2, and two real samples read whole: Cucumber-JVM 7.18.1 ({@code io.cucumber.core.cli.Main --plugin
 * json} on the CI sample's feature) and Cucumber-JS 13.3.0 ({@code @cucumber/cucumber} 13.3.0, {@code --format json}
 * on the same feature), both generated on 2026-11-02 and stored unchanged under {@code src/test/resources/ingest}.
 */
class CucumberJsonReaderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final CucumberJsonReader reader = new CucumberJsonReader();

    @ParameterizedTest(name = "AC-2.3 rows {0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "passed, passed                       | PASSED",
            "passed, skipped                      | SKIPPED",
            "pending, skipped                     | PENDING",
            "undefined, skipped                   | UNDEFINED",
            "passed, ambiguous                    | AMBIGUOUS",
            "passed, failed, skipped              | FAILED",
            "before hook failed, skipped, skipped | FAILED",
            "(no steps, no hooks)                 | UNKNOWN"})
    @Tag("AC-2.3")
    void theTestCaseStatusIsTheWorstRow(String rows, ExecutionStatus status) {
        ObjectNode scenario = MAPPER.createObjectNode().put("type", "scenario").put("name", "S");
        ArrayNode steps = scenario.putArray("steps");
        if (!rows.startsWith("(")) {
            for (String row : rows.split(",\\s*")) {
                if (row.startsWith("before hook ")) {
                    scenario.putArray("before").addObject().putObject("result")
                            .put("status", row.substring("before hook ".length()));
                } else {
                    ObjectNode step = steps.addObject().put("keyword", "Given ").put("name", "a step");
                    step.putObject("result").put("status", row);
                }
            }
        }

        List<IngestedTestCase> cases = reader.read(bytes(feature("a.feature", scenario)));

        assertThat(cases).singleElement().satisfies(testCase -> assertThat(testCase.status()).isEqualTo(status));
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("AC-2.2 The CI sample: 4 unlinked snapshots in upload order, with rows, durations and errors")
    void theCiSampleMapsToFourSnapshots() {
        List<IngestedTestCase> cases = reader.read(CiSample.JSON.getBytes(StandardCharsets.UTF_8));

        assertThat(cases).extracting(IngestedTestCase::name, IngestedTestCase::status).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Pay by card", ExecutionStatus.PASSED),
                org.assertj.core.groups.Tuple.tuple("Pay with voucher SAVE10", ExecutionStatus.PASSED),
                org.assertj.core.groups.Tuple.tuple("Pay with voucher OLD5", ExecutionStatus.FAILED),
                org.assertj.core.groups.Tuple.tuple("Change address", ExecutionStatus.PENDING));
        IngestedTestCase card = cases.get(0);
        assertThat(card.featurePath()).isEqualTo("features/checkout/payment.feature");
        assertThat(card.featureName()).isEqualTo("Payment");
        assertThat(card.line()).isEqualTo(12);
        assertThat(card.tags()).containsExactly("@checkout", "@smoke");
        assertThat(card.durationMs()).isEqualTo(6L);
        assertThat(card.rows()).extracting(IngestedRow::kind, IngestedRow::keyword, IngestedRow::text,
                IngestedRow::status, IngestedRow::durationMs).containsExactly(
                org.assertj.core.groups.Tuple.tuple(StepResultKind.STEP, "Given", "a basket", ExecutionStatus.PASSED,
                        1L),
                org.assertj.core.groups.Tuple.tuple(StepResultKind.STEP, "When", "the customer pays by card",
                        ExecutionStatus.PASSED, 2L),
                org.assertj.core.groups.Tuple.tuple(StepResultKind.STEP, "Then", "the receipt is",
                        ExecutionStatus.PASSED, 3L),
                org.assertj.core.groups.Tuple.tuple(StepResultKind.AFTER_HOOK, null, null, ExecutionStatus.PASSED,
                        0L));
        IngestedTestCase old5 = cases.get(2);
        assertThat(old5.rows().get(1).errorMessage()).isEqualTo("expected rejected but was accepted");
        assertThat(old5.rows().get(2).status()).isEqualTo(ExecutionStatus.SKIPPED);
        assertThat(old5.rows().get(2).durationMs()).isNull();
        assertThat(cases.get(3).featureName()).isEqualTo("Address");
        assertThat(cases.get(3).tags()).isEmpty();
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("D9: rows are element before hooks, each step with its own hooks around it, then element after hooks")
    void rowsFollowExecutionOrder() throws Exception {
        ObjectNode scenario = (ObjectNode) MAPPER.readTree("""
                {"type": "scenario", "name": "S",
                 "before": [{"result": {"status": "passed", "duration": 1}}],
                 "steps": [{"keyword": "Given  ", "name": "one", "result": {"status": "passed"},
                            "before": [{"result": {"status": "passed"}}],
                            "after": [{"result": {"status": "passed"}}]},
                           {"keyword": "When", "name": "two", "result": {"status": "passed"}}],
                 "after": [{"result": {"status": "passed"}}]}
                """);

        IngestedTestCase testCase = reader.read(bytes(feature("a.feature", scenario))).get(0);

        assertThat(testCase.rows()).extracting(IngestedRow::kind, IngestedRow::text).containsExactly(
                org.assertj.core.groups.Tuple.tuple(StepResultKind.BEFORE_HOOK, null),
                org.assertj.core.groups.Tuple.tuple(StepResultKind.BEFORE_HOOK, null),
                org.assertj.core.groups.Tuple.tuple(StepResultKind.STEP, "one"),
                org.assertj.core.groups.Tuple.tuple(StepResultKind.AFTER_HOOK, null),
                org.assertj.core.groups.Tuple.tuple(StepResultKind.STEP, "two"),
                org.assertj.core.groups.Tuple.tuple(StepResultKind.AFTER_HOOK, null));
        assertThat(testCase.rows().get(2).keyword()).isEqualTo("Given");
        assertThat(testCase.durationMs()).as("1 ns in total").isZero();
    }

    @Test
    @Tag("AC-2.15")
    @DisplayName("AC-2.15 The Cucumber-JS 13 forms: hidden hooks in steps, and arguments for table and doc string")
    void cucumberJsFormsAreRead() throws Exception {
        IngestedTestCase testCase = reader.read(jsSample(true)).get(0);

        assertThat(testCase.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(testCase.rows()).hasSize(4);
        assertThat(testCase.rows().get(0)).satisfies(row -> {
            assertThat(row.kind()).isEqualTo(StepResultKind.BEFORE_HOOK);
            assertThat(row.keyword()).isNull();
            assertThat(row.text()).isNull();
            assertThat(row.durationMs()).isZero();
        });
        assertThat(testCase.rows().get(1)).satisfies(row -> {
            assertThat(row.kind()).isEqualTo(StepResultKind.STEP);
            assertThat(row.keyword()).isEqualTo("Given");
            assertThat(row.dataTable()).containsExactly(List.of("item", "price"), List.of("Mug", "8.00"));
        });
        assertThat(testCase.rows().get(2)).satisfies(row -> {
            assertThat(row.keyword()).isEqualTo("Then");
            assertThat(row.docString()).isEqualTo("Total: 8.00");
        });
        assertThat(testCase.rows().get(3)).satisfies(row -> {
            assertThat(row.kind()).isEqualTo(StepResultKind.AFTER_HOOK);
            assertThat(row.errorMessage()).isEqualTo("boom");
        });
    }

    @Test
    @Tag("AC-2.15")
    @DisplayName("AC-2.15 A JS step without name is 422 at $[0].elements[0].steps[1].name")
    void aJsStepWithoutNameIsInvalid() throws Exception {
        CucumberJsonException failure = catchThrowableOfType(CucumberJsonException.class,
                () -> reader.read(jsSample(false)));

        assertThat(failure.reason()).isEqualTo(CucumberJsonException.Reason.INVALID);
        assertThat(failure.problems()).extracting(JsonProblem::pointer)
                .containsExactly("$[0].elements[0].steps[1].name");
    }

    @Test
    @Tag("AC-2.15")
    @DisplayName("AC-2.15 A hidden step with keyword Before and a name is a normal step")
    void aHiddenStepWithANameIsAStep() throws Exception {
        ObjectNode scenario = (ObjectNode) MAPPER.readTree("""
                {"type": "scenario", "name": "S", "steps": [
                  {"hidden": true, "keyword": "Before", "name": "a named step", "result": {"status": "passed"}}]}
                """);

        IngestedRow row = reader.read(bytes(feature("a.feature", scenario))).get(0).rows().get(0);

        assertThat(row.kind()).isEqualTo(StepResultKind.STEP);
        assertThat(row.keyword()).isEqualTo("Before");
        assertThat(row.text()).isEqualTo("a named step");
    }

    @ParameterizedTest(name = "AC-2.8 {0}: 422 CUCUMBER_JSON_INVALID at {1}")
    @CsvSource(delimiter = '|', value = {
            "an object instead of an array               | $",
            "\"Passed\" as the first step status          | $[0].elements[0].steps[0].result.status",
            "the first uri missing                       | $[0].uri",
            "the first scenario name 42                  | $[0].elements[1].name",
            "an element type \"outline\"                   | $[0].elements[1].type",
            "a duration of -1                            | $[0].elements[1].steps[0].result.duration",
            "U+0000 in a step name                       | $[0].elements[1].steps[0].name",
            "a lone surrogate in a step name             | $[0].elements[1].steps[0].name",
            "a fractional line                           | $[0].elements[1].line",
            "line 0                                      | $[0].elements[1].line",
            "a uri of 4,097 code points                  | $[0].uri",
            "a tag without name                          | $[0].tags[0].name",
            "hidden as a string                          | $[0].elements[1].steps[0].hidden",
            "a row cell that is a number                 | $[0].elements[1].steps[0].rows[0].cells[0]"})
    @Tag("AC-2.8")
    void invalidCucumberJsonIsRejectedWithAPointer(String change, String pointer) {
        ArrayNode sample = CiSample.tree();
        ObjectNode payment = (ObjectNode) sample.get(0);
        ObjectNode scenario = (ObjectNode) payment.get("elements").get(1);
        ObjectNode step = (ObjectNode) scenario.get("steps").get(0);
        byte[] body = switch (change.trim()) {
            case "an object instead of an array" -> "{\"uri\":\"a.feature\"}".getBytes(StandardCharsets.UTF_8);
            case "\"Passed\" as the first step status" -> {
                ((ObjectNode) payment.get("elements").get(0).get("steps").get(0).get("result")).put("status",
                        "Passed");
                yield bytes(sample);
            }
            case "the first uri missing" -> {
                payment.remove("uri");
                yield bytes(sample);
            }
            case "the first scenario name 42" -> {
                scenario.put("name", 42);
                yield bytes(sample);
            }
            case "an element type \"outline\"" -> {
                scenario.put("type", "outline");
                yield bytes(sample);
            }
            case "a duration of -1" -> {
                ((ObjectNode) step.get("result")).put("duration", -1);
                yield bytes(sample);
            }
            case "U+0000 in a step name" -> {
                step.put("name", "a\u0000b");
                yield bytes(sample);
            }
            case "a lone surrogate in a step name" -> {
                step.put("name", "PLACEHOLDER");
                yield CiSample.json(sample).replace("PLACEHOLDER", "a\\ud800b").getBytes(StandardCharsets.UTF_8);
            }
            case "a fractional line" -> {
                scenario.put("line", 12.5);
                yield bytes(sample);
            }
            case "line 0" -> {
                scenario.put("line", 0);
                yield bytes(sample);
            }
            case "a uri of 4,097 code points" -> {
                payment.put("uri", "😀".repeat(4097));
                yield bytes(sample);
            }
            case "a tag without name" -> {
                ((ObjectNode) payment.get("tags").get(0)).remove("name");
                yield bytes(sample);
            }
            case "hidden as a string" -> {
                step.put("hidden", "true");
                yield bytes(sample);
            }
            default -> {
                step.putArray("rows").addObject().putArray("cells").add(1);
                yield bytes(sample);
            }
        };

        CucumberJsonException failure = catchThrowableOfType(CucumberJsonException.class, () -> reader.read(body));

        assertThat(failure).isNotNull();
        assertThat(failure.reason()).isEqualTo(CucumberJsonException.Reason.INVALID);
        assertThat(failure.problems()).extracting(JsonProblem::pointer).containsExactly(pointer);
        assertThat(failure.problems()).allSatisfy(problem -> assertThat(problem.message())
                .doesNotContain("Passed", "😀", "a\u0000b", "\"outline\""));
    }

    @ParameterizedTest(name = "BD-10 an explicit null for {0} is 422 at {1}")
    @CsvSource(delimiter = '|', value = {
            "the body                  | $",
            "a feature                 | $[0]",
            "the feature name          | $[0].name",
            "the feature tags          | $[0].tags",
            "the feature elements      | $[0].elements",
            "an element name           | $[0].elements[1].name",
            "an element line           | $[0].elements[1].line",
            "an element after          | $[0].elements[1].after",
            "a step name               | $[0].elements[1].steps[0].name",
            "a step result             | $[0].elements[1].steps[0].result",
            "a duration                | $[0].elements[1].steps[0].result.duration",
            "an error_message          | $[0].elements[5].steps[0].result.error_message",
            "a step doc_string         | $[0].elements[1].steps[0].doc_string",
            "a step rows               | $[0].elements[1].steps[0].rows",
            "a step hidden             | $[0].elements[1].steps[0].hidden"})
    @Tag("AC-2.8")
    void anExplicitNullIsAWrongType(String member, String pointer) {
        ArrayNode sample = CiSample.tree();
        ObjectNode payment = (ObjectNode) sample.get(0);
        ObjectNode scenario = (ObjectNode) payment.get("elements").get(1);
        ObjectNode step = (ObjectNode) scenario.get("steps").get(0);
        byte[] body = switch (member.trim()) {
            case "the body" -> "null".getBytes(StandardCharsets.UTF_8);
            case "a feature" -> {
                sample.set(0, MAPPER.nullNode());
                yield bytes(sample);
            }
            case "the feature name" -> bytes(withNull(sample, payment, "name"));
            case "the feature tags" -> bytes(withNull(sample, payment, "tags"));
            case "the feature elements" -> bytes(withNull(sample, payment, "elements"));
            case "an element name" -> bytes(withNull(sample, scenario, "name"));
            case "an element line" -> bytes(withNull(sample, scenario, "line"));
            case "an element after" -> bytes(withNull(sample, scenario, "after"));
            case "a step name" -> bytes(withNull(sample, step, "name"));
            case "a step result" -> bytes(withNull(sample, step, "result"));
            case "a duration" -> bytes(withNull(sample, (ObjectNode) step.get("result"), "duration"));
            case "an error_message" -> bytes(withNull(sample, (ObjectNode) payment.get("elements").get(5)
                    .get("steps").get(0).get("result"), "error_message"));
            case "a step doc_string" -> bytes(withNull(sample, step, "doc_string"));
            case "a step rows" -> bytes(withNull(sample, step, "rows"));
            default -> bytes(withNull(sample, step, "hidden"));
        };

        CucumberJsonException failure = catchThrowableOfType(CucumberJsonException.class, () -> reader.read(body));

        assertThat(failure.reason()).isEqualTo(CucumberJsonException.Reason.INVALID);
        assertThat(failure.problems()).extracting(JsonProblem::pointer).containsExactly(pointer);
    }

    @Test
    @Tag("AC-2.15")
    @DisplayName("BD-11 With two arguments entries of the same kind, the first wins and the rest are not read")
    void theFirstArgumentsEntryOfEachKindWins() throws Exception {
        ObjectNode scenario = (ObjectNode) MAPPER.readTree("""
                {"type": "scenario", "name": "S", "steps": [{"keyword": "Given ", "name": "a step",
                  "result": {"status": "passed"}, "arguments": [
                    {"rows": [{"cells": ["first"]}]}, {"content": "first doc", "line": 3},
                    {"rows": [{"cells": [42]}]}, {"content": null}, {"other": true}]}]}
                """);

        IngestedRow row = reader.read(bytes(feature("a.feature", scenario))).get(0).rows().get(0);

        assertThat(row.dataTable()).containsExactly(List.of("first"));
        assertThat(row.docString()).isEqualTo("first doc");
    }

    @Test
    @Tag("AC-2.15")
    @DisplayName("BD-11 rows and doc_string win over arguments")
    void rowsAndDocStringWinOverArguments() throws Exception {
        ObjectNode scenario = (ObjectNode) MAPPER.readTree("""
                {"type": "scenario", "name": "S", "steps": [{"keyword": "Given ", "name": "a step",
                  "result": {"status": "passed"}, "rows": [{"cells": ["jvm"]}], "doc_string": {"value": "jvm doc"},
                  "arguments": [{"rows": [{"cells": ["js"]}]}, {"content": "js doc"}]}]}
                """);

        IngestedRow row = reader.read(bytes(feature("a.feature", scenario))).get(0).rows().get(0);

        assertThat(row.dataTable()).containsExactly(List.of("jvm"));
        assertThat(row.docString()).isEqualTo("jvm doc");
    }

    @ParameterizedTest(name = "BD-12 hidden step with keyword {0} and no name: {1}")
    @CsvSource(delimiter = '|', value = {
            "'Before '   | BEFORE_HOOK",
            "'After\t'   | AFTER_HOOK",
            "'before'    | $[0].elements[0].steps[0].name",
            "'AFTER'     | $[0].elements[0].steps[0].name",
            "' Before'   | $[0].elements[0].steps[0].name"})
    @Tag("AC-2.15")
    void theHookKeywordIsTrimmedThenMatchedCaseSensitively(String keyword, String outcome) {
        ObjectNode scenario = MAPPER.createObjectNode().put("type", "scenario").put("name", "S");
        ObjectNode step = scenario.putArray("steps").addObject().put("hidden", true)
                .put("keyword", keyword.replace("\\t", "\t"));
        step.putObject("result").put("status", "passed");

        if (outcome.startsWith("$")) {
            CucumberJsonException failure = catchThrowableOfType(CucumberJsonException.class,
                    () -> reader.read(bytes(feature("a.feature", scenario))));
            assertThat(failure.problems()).extracting(JsonProblem::pointer).containsExactly(outcome);
        } else {
            assertThat(reader.read(bytes(feature("a.feature", scenario))).get(0).rows().get(0).kind())
                    .isEqualTo(StepResultKind.valueOf(outcome));
        }
    }

    @Test
    @Tag("AC-2.15")
    @DisplayName("BD-12 A non-boolean hidden is 422 at .hidden")
    void aNonBooleanHiddenIsInvalid() {
        ObjectNode scenario = MAPPER.createObjectNode().put("type", "scenario").put("name", "S");
        ObjectNode step = scenario.putArray("steps").addObject().put("hidden", 1).put("keyword", "Given ")
                .put("name", "a step");
        step.putObject("result").put("status", "passed");

        CucumberJsonException failure = catchThrowableOfType(CucumberJsonException.class,
                () -> reader.read(bytes(feature("a.feature", scenario))));

        assertThat(failure.problems()).extracting(JsonProblem::pointer)
                .containsExactly("$[0].elements[0].steps[0].hidden");
    }

    @Test
    @Tag("AC-2.2")
    @DisplayName("BD-13 A background's own before and after are not read, even when malformed")
    void backgroundHooksAreIgnored() throws Exception {
        ObjectNode background = (ObjectNode) MAPPER.readTree("""
                {"type": "background", "steps": [{"keyword": "Given ", "name": "a basket",
                  "result": {"status": "passed"}}],
                 "before": [{"result": {"status": "failed"}}], "after": "not even an array"}
                """);
        ObjectNode scenario = MAPPER.createObjectNode().put("type", "scenario").put("name", "S");

        IngestedTestCase testCase = reader.read(bytes(feature("a.feature", background, scenario))).get(0);

        assertThat(testCase.rows()).extracting(IngestedRow::kind, IngestedRow::text).containsExactly(
                org.assertj.core.groups.Tuple.tuple(StepResultKind.STEP, "a basket"));
        assertThat(testCase.status()).isEqualTo(ExecutionStatus.PASSED);
    }

    @Test
    @Tag("AC-2.8")
    @DisplayName("AC-2.8 At most 20 errors, in document order")
    void atMostTwentyErrors() {
        ArrayNode sample = CiSample.tree();
        for (int i = 0; i < 30; i++) {
            sample.addObject().put("uri", 1);
        }

        CucumberJsonException failure = catchThrowableOfType(CucumberJsonException.class,
                () -> reader.read(bytes(sample)));

        assertThat(failure.problems()).hasSize(20);
        assertThat(failure.problems().get(0).pointer()).isEqualTo("$[2].uri");
        assertThat(failure.problems().get(19).pointer()).isEqualTo("$[21].uri");
    }

    @ParameterizedTest(name = "AC-2.8 {0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "not json                                          | MALFORMED",
            "[{\"uri\": \"a.feature\"}] trailing                    | MALFORMED",
            "[]                                                | EMPTY",
            "[{\"uri\":\"a.feature\",\"elements\":[{\"type\":\"background\",\"steps\":[]}]}] | EMPTY"})
    @Tag("AC-2.8")
    void unreadableOrEmptyUploads(String body, CucumberJsonException.Reason reason) {
        CucumberJsonException failure = catchThrowableOfType(CucumberJsonException.class,
                () -> reader.read(body.trim().getBytes(StandardCharsets.UTF_8)));

        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.problems()).isEmpty();
    }

    @Test
    @Tag("AC-2.9")
    @DisplayName("D9: more than 5,000 test cases is TOO_MANY_TEST_CASES; exactly 5,000 is read")
    void atMostFiveThousandTestCases() {
        assertThat(reader.read(bytes(manyScenarios(5000)))).hasSize(5000);
        CucumberJsonException failure = catchThrowableOfType(CucumberJsonException.class,
                () -> reader.read(bytes(manyScenarios(5001))));
        assertThat(failure.reason()).isEqualTo(CucumberJsonException.Reason.TOO_MANY_TEST_CASES);
    }

    @Test
    @Tag("AC-2.12")
    @DisplayName("AC-2.12 Embeddings, match and start_timestamp are ignored; error_message keeps 65,536 code points")
    void ignoredAndShortened() {
        ArrayNode sample = CiSample.tree();
        ObjectNode step = (ObjectNode) sample.get(0).get("elements").get(1).get("steps").get(0);
        step.putArray("embeddings").addObject().put("mime_type", "image/png").put("data", "A".repeat(2_000_000));
        String message = "😀" + "e".repeat(69_999);
        ((ObjectNode) step.get("result")).put("status", "failed").put("error_message", message);

        IngestedTestCase card = reader.read(bytes(sample)).get(0);

        String stored = card.rows().get(1).errorMessage();
        assertThat(stored.codePointCount(0, stored.length())).isEqualTo(65_536);
        assertThat(stored).isEqualTo(message.substring(0, message.offsetByCodePoints(0, 65_536)));
    }

    @Test
    @Tag("AC-2.13")
    @DisplayName("AC-2.13 Tags are the feature's, then the element's, each once")
    void tagsAreFeatureThenElementEachOnce() {
        IngestedTestCase card = reader.read(CiSample.JSON.getBytes(StandardCharsets.UTF_8)).get(0);

        assertThat(card.tags()).containsExactly("@checkout", "@smoke");
    }

    @ParameterizedTest(name = "T6 the real sample {0} is read whole")
    @ValueSource(strings = {"cucumber-jvm-7.18.1.json", "cucumber-js-13.3.0.json"})
    @Tag("AC-2.15")
    void realSamplesAreRead(String sample) throws IOException {
        List<IngestedTestCase> cases = reader.read(resource(sample));

        assertThat(cases).extracting(IngestedTestCase::name).containsExactly("Pay by card",
                "Pay with voucher SAVE10", "Pay with voucher OLD5", "Change address");
        // "Change address": a pending step, then a step without definition. Cucumber-JVM reports the second one
        // skipped (PENDING wins), Cucumber-JS undefined (UNDEFINED wins, D9's precedence).
        ExecutionStatus last = sample.startsWith("cucumber-js") ? ExecutionStatus.UNDEFINED : ExecutionStatus.PENDING;
        assertThat(cases).extracting(IngestedTestCase::status).containsExactly(ExecutionStatus.PASSED,
                ExecutionStatus.PASSED, ExecutionStatus.FAILED, last);
        IngestedTestCase card = cases.get(0);
        assertThat(card.tags()).containsExactly("@checkout", "@smoke");
        assertThat(card.rows()).extracting(IngestedRow::kind).startsWith(StepResultKind.BEFORE_HOOK)
                .endsWith(StepResultKind.AFTER_HOOK);
        assertThat(card.rows()).filteredOn(row -> row.kind() == StepResultKind.STEP).extracting(IngestedRow::text)
                .containsExactly("a basket", "a basket with these items:", "the customer pays by card",
                        "the receipt is:");
        IngestedRow table = card.rows().stream().filter(row -> "a basket with these items:".equals(row.text()))
                .findFirst().orElseThrow();
        assertThat(table.dataTable()).containsExactly(List.of("item", "price"), List.of("Mug", "8.00"));
        IngestedRow receipt = card.rows().stream().filter(row -> "the receipt is:".equals(row.text()))
                .findFirst().orElseThrow();
        assertThat(receipt.docString()).isEqualTo("Total: 8.00");
        IngestedRow basket = card.rows().stream().filter(row -> "a basket".equals(row.text())).findFirst()
                .orElseThrow();
        assertThat(basket.durationMs()).as("A2: a 1 ms sleep is at least 1,000,000 ns").isGreaterThanOrEqualTo(1L);
        assertThat(cases.get(2).rows()).anySatisfy(row -> assertThat(row.errorMessage())
                .contains("expected rejected but was accepted"));
    }

    private static byte[] jsSample(boolean withGivenName) throws Exception {
        ObjectNode scenario = (ObjectNode) MAPPER.readTree("""
                {"type": "scenario", "keyword": "Scenario", "name": "Pay by card", "line": 8, "steps": [
                  {"hidden": true, "keyword": "Before", "result": {"status": "passed", "duration": 400000}},
                  {"arguments": [{"rows": [{"cells": ["item", "price"]}, {"cells": ["Mug", "8.00"]}]}],
                   "keyword": "Given ", "name": "a basket with these items:", "line": 9,
                   "result": {"status": "passed"}},
                  {"arguments": [{"content": "Total: 8.00", "line": 15}],
                   "keyword": "Then ", "name": "the receipt is:", "line": 14, "result": {"status": "passed"}},
                  {"hidden": true, "keyword": "After", "result": {"status": "failed", "error_message": "boom"}}]}
                """);
        if (!withGivenName) {
            ((ObjectNode) scenario.get("steps").get(1)).remove("name");
        }
        return bytes(feature("features/checkout/payment.feature", scenario));
    }

    private static ArrayNode withNull(ArrayNode sample, ObjectNode owner, String member) {
        owner.putNull(member);
        return sample;
    }

    private static ArrayNode feature(String uri, ObjectNode... elements) {
        ArrayNode features = MAPPER.createArrayNode();
        ObjectNode feature = features.addObject().put("uri", uri).put("name", "F");
        ArrayNode list = feature.putArray("elements");
        for (ObjectNode element : elements) {
            list.add(element);
        }
        return features;
    }

    private static ArrayNode manyScenarios(int count) {
        List<ObjectNode> elements = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            elements.add(MAPPER.createObjectNode().put("type", "scenario").put("name", "S" + i));
        }
        return feature("many.feature", elements.toArray(ObjectNode[]::new));
    }

    private static byte[] bytes(ArrayNode tree) {
        return CiSample.json(tree).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = CucumberJsonReaderTest.class.getResourceAsStream("/ingest/" + name)) {
            return in.readAllBytes();
        }
    }
}
