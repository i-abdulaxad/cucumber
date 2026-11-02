package org.reportservice.ingest;

import org.reportservice.enums.ExecutionStatus;
import org.reportservice.enums.StepResultKind;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only reader of Cucumber JSON (D9, contract C8; Cucumber-JVM 7 and Cucumber-JS 13). Pure: Jackson's tree and
 * plain Java, no JPA or HTTP; a bean only so the upload service can have it injected. Unknown members are ignored at
 * every level (D9's named deviation); what is read is strict, and every broken rule is reported with its JSONPath,
 * at most {@value #MAX_PROBLEMS}, in document order. Nothing here logs.
 * <p>
 * Mapping: each {@code scenario} (or {@code scenario_outline}) element is one test case, in document order; a
 * {@code background} element's steps go before the next scenario element of the same feature; a test case's rows
 * are its element {@code before} hooks, then each step with its step-level hooks around it, then its element
 * {@code after} hooks. Cucumber-JS 13 forms: a step with {@code hidden: true}, keyword {@code Before} or
 * {@code After} and no {@code name} is a hook row in its place; a table or doc string missing from {@code rows} or
 * {@code doc_string} is read from the first {@code arguments} entry of its kind (BD-11). An explicit {@code null} on a
 * member that is read is a wrong type (BD-10); a background's own hooks are not read (BD-13).
 */
@Component
public class CucumberJsonReader {

    static final int MAX_PROBLEMS = 20;
    static final int MAX_TEST_CASES = 5_000;
    static final int MAX_URI_CODE_POINTS = 4_096;
    static final int MAX_ERROR_MESSAGE_CODE_POINTS = 65_536;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final Map<String, ExecutionStatus> STATUSES = Map.of(
            "passed", ExecutionStatus.PASSED, "failed", ExecutionStatus.FAILED, "skipped", ExecutionStatus.SKIPPED,
            "pending", ExecutionStatus.PENDING, "undefined", ExecutionStatus.UNDEFINED,
            "ambiguous", ExecutionStatus.AMBIGUOUS);
    /** Cucumber's precedence, worst first (D9). */
    private static final List<ExecutionStatus> WORST_FIRST = List.of(ExecutionStatus.FAILED,
            ExecutionStatus.AMBIGUOUS, ExecutionStatus.UNDEFINED, ExecutionStatus.PENDING, ExecutionStatus.SKIPPED,
            ExecutionStatus.PASSED);

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** The test cases of the body, or {@link CucumberJsonException}. */
    public List<IngestedTestCase> read(byte[] body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JacksonException ex) {
            throw new CucumberJsonException(CucumberJsonException.Reason.MALFORMED, List.of());
        }
        if (root == null || root.isMissingNode()) {
            throw new CucumberJsonException(CucumberJsonException.Reason.MALFORMED, List.of());
        }
        Reading reading = new Reading();
        List<IngestedTestCase> cases = reading.features(root);
        if (!reading.problems.isEmpty()) {
            throw new CucumberJsonException(CucumberJsonException.Reason.INVALID, reading.problems);
        }
        if (cases.isEmpty()) {
            throw new CucumberJsonException(CucumberJsonException.Reason.EMPTY, List.of());
        }
        if (cases.size() > MAX_TEST_CASES) {
            throw new CucumberJsonException(CucumberJsonException.Reason.TOO_MANY_TEST_CASES, List.of());
        }
        return cases;
    }

    /** One read of one body: the problems found so far. */
    private static final class Reading {

        private final List<JsonProblem> problems = new ArrayList<>();

        List<IngestedTestCase> features(JsonNode root) {
            List<IngestedTestCase> cases = new ArrayList<>();
            if (!root.isArray()) {
                problem("$", "The body must be an array of features");
                return cases;
            }
            for (int f = 0; f < root.size(); f++) {
                feature(root.get(f), "$[" + f + "]", cases);
            }
            return cases;
        }

        private void feature(JsonNode feature, String at, List<IngestedTestCase> cases) {
            if (!object(feature, at, "A feature must be an object")) {
                return;
            }
            String uri = requiredString(feature, at, "uri");
            if (uri != null && (uri.isEmpty() || uri.codePointCount(0, uri.length()) > MAX_URI_CODE_POINTS)) {
                problem(at + ".uri", "Uri must be 1 to 4,096 characters");
            }
            String name = optionalString(feature, at, "name", "");
            List<String> featureTags = tags(feature, at);
            List<IngestedRow> pendingBackground = null;
            for (JsonNode element : optionalArray(feature, at, "elements")) {
                String elementAt = at + ".elements[" + indexOf(feature.get("elements"), element) + "]";
                if (!object(element, elementAt, "An element must be an object")) {
                    continue;
                }
                String type = requiredString(element, elementAt, "type");
                String elementName = optionalString(element, elementAt, "name", "");
                Integer line = line(element, elementAt);
                List<String> elementTags = tags(element, elementAt);
                // BD-13: a background's own before and after hooks are not read
                boolean background = "background".equals(type);
                List<IngestedRow> before = background ? List.of()
                        : hooks(element, elementAt, "before", StepResultKind.BEFORE_HOOK);
                List<IngestedRow> steps = steps(element, elementAt);
                List<IngestedRow> after = background ? List.of()
                        : hooks(element, elementAt, "after", StepResultKind.AFTER_HOOK);
                if (type == null) {
                    continue;
                }
                switch (type) {
                    case "background" -> pendingBackground = steps;
                    case "scenario", "scenario_outline" -> {
                        List<IngestedRow> rows = new ArrayList<>(before);
                        if (pendingBackground != null) {
                            rows.addAll(pendingBackground);
                            pendingBackground = null;
                        }
                        rows.addAll(steps);
                        rows.addAll(after);
                        Set<String> allTags = new LinkedHashSet<>(featureTags);
                        allTags.addAll(elementTags);
                        cases.add(new IngestedTestCase(uri, name, elementName,
                                List.copyOf(allTags), line, status(rows), duration(rows, elementAt),
                                List.copyOf(rows)));
                    }
                    default -> problem(elementAt + ".type", "Type must be background, scenario or scenario_outline");
                }
            }
        }

        /**
         * floor(sum of the row nanoseconds / 1,000,000), or null when no row has a duration (D9). A sum beyond
         * {@code Long.MAX_VALUE} nanoseconds is invalid JSON at the element (RE11), not an arithmetic failure.
         */
        private Long duration(List<IngestedRow> rows, String elementAt) {
            boolean any = false;
            long sum = 0;
            for (IngestedRow row : rows) {
                if (row.durationNanos() != null) {
                    any = true;
                    try {
                        sum = Math.addExact(sum, row.durationNanos());
                    } catch (ArithmeticException ex) {
                        problem(elementAt, "The durations of this test case add up to more than "
                                + Long.MAX_VALUE + " nanoseconds");
                        return null;
                    }
                }
            }
            return any ? sum / NANOS_PER_MILLI : null;
        }

        private List<IngestedRow> steps(JsonNode element, String at) {
            List<IngestedRow> rows = new ArrayList<>();
            JsonNode steps = element.get("steps");
            List<JsonNode> list = optionalArray(element, at, "steps");
            for (int s = 0; s < list.size(); s++) {
                String stepAt = at + ".steps[" + s + "]";
                JsonNode step = steps.get(s);
                if (!object(step, stepAt, "A step must be an object")) {
                    continue;
                }
                JsonNode hidden = step.get("hidden");
                if (hidden != null && !hidden.isBoolean()) {
                    problem(stepAt + ".hidden", "Hidden must be true or false");
                }
                if (isJsHook(step)) {
                    StepResultKind kind = "Before".equals(step.get("keyword").stringValue().stripTrailing())
                            ? StepResultKind.BEFORE_HOOK : StepResultKind.AFTER_HOOK;
                    rows.add(hook(step, stepAt, kind));
                    continue;
                }
                rows.addAll(hooks(step, stepAt, "before", StepResultKind.BEFORE_HOOK));
                rows.add(step(step, stepAt));
                rows.addAll(hooks(step, stepAt, "after", StepResultKind.AFTER_HOOK));
            }
            return rows;
        }

        /**
         * A Cucumber-JS 13 hook: {@code hidden: true}, keyword {@code Before} or {@code After} (trailing whitespace
         * trimmed, then matched case-sensitively, BD-12) and no {@code name} member.
         */
        private static boolean isJsHook(JsonNode step) {
            JsonNode hidden = step.get("hidden");
            JsonNode keyword = step.get("keyword");
            if (hidden == null || !hidden.isBoolean() || !hidden.booleanValue() || step.get("name") != null
                    || keyword == null || !keyword.isString()) {
                return false;
            }
            String trimmed = keyword.stringValue().stripTrailing();
            return "Before".equals(trimmed) || "After".equals(trimmed);
        }

        private IngestedRow step(JsonNode step, String at) {
            String keyword = requiredString(step, at, "keyword");
            String text = requiredString(step, at, "name");
            List<List<String>> table = null;
            String docString = null;
            if (step.get("rows") != null) {
                table = table(step.get("rows"), at + ".rows");
            }
            if (step.get("doc_string") != null) {
                JsonNode doc = step.get("doc_string");
                if (object(doc, at + ".doc_string", "Doc_string must be an object")) {
                    docString = requiredString(doc, at + ".doc_string", "value");
                }
            }
            JsonNode arguments = step.get("arguments");
            if (arguments != null && (table == null || docString == null)) {
                if (!arguments.isArray()) {
                    problem(at + ".arguments", "Arguments must be an array");
                } else {
                    for (int a = 0; a < arguments.size(); a++) {
                        JsonNode argument = arguments.get(a);
                        String argumentAt = at + ".arguments[" + a + "]";
                        if (argument == null || !argument.isObject()) {
                            continue;
                        }
                        if (table == null && argument.get("rows") != null) {
                            table = table(argument.get("rows"), argumentAt + ".rows");
                        } else if (docString == null && argument.get("content") != null) {
                            docString = requiredString(argument, argumentAt, "content");
                        }
                    }
                }
            }
            Result result = result(step, at);
            return new IngestedRow(StepResultKind.STEP, keyword == null ? null : keyword.stripTrailing(), text,
                    table, docString, result.status(), millis(result.nanos()), result.nanos(), result.errorMessage());
        }

        private List<IngestedRow> hooks(JsonNode owner, String at, String member, StepResultKind kind) {
            List<IngestedRow> rows = new ArrayList<>();
            List<JsonNode> list = optionalArray(owner, at, member);
            for (int h = 0; h < list.size(); h++) {
                String hookAt = at + "." + member + "[" + h + "]";
                if (object(list.get(h), hookAt, "A hook must be an object")) {
                    rows.add(hook(list.get(h), hookAt, kind));
                }
            }
            return rows;
        }

        private IngestedRow hook(JsonNode hook, String at, StepResultKind kind) {
            Result result = result(hook, at);
            return new IngestedRow(kind, null, null, null, null, result.status(), millis(result.nanos()),
                    result.nanos(), result.errorMessage());
        }

        private Result result(JsonNode owner, String at) {
            JsonNode result = owner.get("result");
            String resultAt = at + ".result";
            if (result == null) {
                problem(resultAt, "Result is required");
                return Result.NONE;
            }
            if (!object(result, resultAt, "Result must be an object")) {
                return Result.NONE;
            }
            String statusText = requiredString(result, resultAt, "status");
            ExecutionStatus status = statusText == null ? null : STATUSES.get(statusText);
            if (statusText != null && status == null) {
                problem(resultAt + ".status",
                        "Status must be one of passed, failed, skipped, pending, undefined, ambiguous");
            }
            Long nanos = null;
            JsonNode duration = result.get("duration");
            if (duration != null) {
                if (!duration.isIntegralNumber() || !duration.canConvertToLong() || duration.longValue() < 0) {
                    problem(resultAt + ".duration", "Duration must be a whole number of nanoseconds, 0 or more");
                } else {
                    nanos = duration.longValue();
                }
            }
            String message = optionalString(result, resultAt, "error_message", null);
            return new Result(status == null ? ExecutionStatus.UNKNOWN : status, nanos, truncate(message));
        }

        private List<List<String>> table(JsonNode rows, String at) {
            if (!rows.isArray()) {
                problem(at, "Rows must be an array");
                return null;
            }
            List<List<String>> table = new ArrayList<>();
            for (int r = 0; r < rows.size(); r++) {
                String rowAt = at + "[" + r + "]";
                JsonNode row = rows.get(r);
                if (!object(row, rowAt, "A row must be an object")) {
                    continue;
                }
                JsonNode cells = row.get("cells");
                if (cells == null || !cells.isArray()) {
                    problem(rowAt + ".cells", "Cells must be an array of strings");
                    continue;
                }
                List<String> values = new ArrayList<>();
                for (int c = 0; c < cells.size(); c++) {
                    String cell = text(cells.get(c), rowAt + ".cells[" + c + "]");
                    values.add(cell == null ? "" : cell);
                }
                table.add(List.copyOf(values));
            }
            return List.copyOf(table);
        }

        private List<String> tags(JsonNode owner, String at) {
            List<String> tags = new ArrayList<>();
            List<JsonNode> list = optionalArray(owner, at, "tags");
            for (int t = 0; t < list.size(); t++) {
                String tagAt = at + ".tags[" + t + "]";
                if (object(list.get(t), tagAt, "A tag must be an object")) {
                    String tag = requiredString(list.get(t), tagAt, "name");
                    if (tag != null) {
                        tags.add(tag);
                    }
                }
            }
            return tags;
        }

        private Integer line(JsonNode element, String at) {
            JsonNode line = element.get("line");
            if (line == null) {
                return null;
            }
            if (!line.isIntegralNumber() || !line.canConvertToInt() || line.intValue() < 1) {
                problem(at + ".line", "Line must be a whole number, 1 or more");
                return null;
            }
            return line.intValue();
        }

        private List<JsonNode> optionalArray(JsonNode owner, String at, String member) {
            JsonNode value = owner.get(member);
            if (value == null) {
                return List.of();
            }
            if (!value.isArray()) {
                problem(at + "." + member, member + " must be an array");
                return List.of();
            }
            List<JsonNode> items = new ArrayList<>(value.size());
            value.forEach(items::add);
            return items;
        }

        private String requiredString(JsonNode owner, String at, String member) {
            JsonNode value = owner.get(member);
            if (value == null) {
                problem(at + "." + member, member + " is required");
                return null;
            }
            return text(value, at + "." + member);
        }

        private String optionalString(JsonNode owner, String at, String member, String fallback) {
            JsonNode value = owner.get(member);
            return value == null ? fallback : text(value, at + "." + member);
        }

        /** A read string: a JSON string without U+0000 or a lone surrogate. */
        private String text(JsonNode value, String at) {
            if (value == null || !value.isString()) {
                problem(at, "Must be a string");
                return null;
            }
            String text = value.stringValue();
            if (!storable(text)) {
                problem(at, "Must not contain U+0000 or a lone surrogate");
                return null;
            }
            return text;
        }

        private boolean object(JsonNode value, String at, String message) {
            if (value == null || !value.isObject()) {
                problem(at, message);
                return false;
            }
            return true;
        }

        private void problem(String pointer, String message) {
            if (problems.size() < MAX_PROBLEMS) {
                problems.add(new JsonProblem(pointer, message));
            }
        }

        private static int indexOf(JsonNode array, JsonNode item) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i) == item) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static boolean storable(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0000' || Character.isLowSurrogate(c)) {
                return false;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            }
        }
        return true;
    }

    private static ExecutionStatus status(List<IngestedRow> rows) {
        for (ExecutionStatus candidate : WORST_FIRST) {
            if (rows.stream().anyMatch(row -> row.status() == candidate)) {
                return candidate;
            }
        }
        return ExecutionStatus.UNKNOWN;
    }


    private static Long millis(Long nanos) {
        return nanos == null ? null : nanos / NANOS_PER_MILLI;
    }

    private static String truncate(String message) {
        if (message == null || message.codePointCount(0, message.length()) <= MAX_ERROR_MESSAGE_CODE_POINTS) {
            return message;
        }
        return message.substring(0, message.offsetByCodePoints(0, MAX_ERROR_MESSAGE_CODE_POINTS));
    }

    private record Result(ExecutionStatus status, Long nanos, String errorMessage) {
        static final Result NONE = new Result(ExecutionStatus.UNKNOWN, null, null);
    }
}
