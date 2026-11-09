package org.scenarioservice.gherkin;

import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.gherkin.GherkinDialectProvider;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Background;
import io.cucumber.messages.types.DataTable;
import io.cucumber.messages.types.DocString;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Examples;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.Location;
import io.cucumber.messages.types.ParseError;
import io.cucumber.messages.types.Pickle;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.Rule;
import io.cucumber.messages.types.RuleChild;
import io.cucumber.messages.types.Scenario;
import io.cucumber.messages.types.Step;
import io.cucumber.messages.types.TableRow;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.Tag;
import org.scenarioservice.enums.ScenarioKind;
import org.scenarioservice.enums.StepKeyword;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only class that uses the official Gherkin parser (D7, P14; {@code io.cucumber:gherkin} 28.0.0 with
 * {@code messages} 24.1.0). It turns text into this service's immutable model, applies D8's validity rule, and
 * compiles test cases exactly as Cucumber's pickle compiler does (D13), mapped to positions in the parsed feature.
 * Pure: no persistence, no logging (the text is user content).
 */
@Component
public class GherkinAdapter {

    /**
     * D8, D19 #8: the error for a text the parser accepts but that has no Feature.
     */
    public static final String NO_FEATURE = "The text has no Feature";

    /**
     * D21 #1: the error for an encoding pragma that names any charset other than UTF-8.
     */
    public static final String ONLY_UTF_8 = "Only UTF-8 encoding is supported";

    /**
     * gherkin 28's own pragma rules ({@code EncodingParser}): leading comment or blank lines, case-insensitive.
     */
    private static final Pattern COMMENT_OR_EMPTY_LINE = Pattern.compile("^\\s*#|^\\s*$");
    private static final Pattern ENCODING_PRAGMA =
            Pattern.compile("^\\s*#\\s*encoding\\s*:\\s*([0-9a-zA-Z\\-]+)", Pattern.CASE_INSENSITIVE);

    private static final String URI = "feature";
    private static final String ASTERISK = "* ";
    private static final Comparator<GherkinParseError> ERROR_ORDER =
            Comparator.comparingInt(GherkinParseError::line).thenComparingInt(GherkinParseError::column);

    private final GherkinDialectProvider dialects = new GherkinDialectProvider();

    /**
     * Valid Gherkin with a Feature, or every error with its 1-based line and column, sorted (D8).
     */
    public GherkinParseResult parse(String source) {
        if (!utf8Only(source)) {
            return new GherkinParseResult.Invalid(List.of(new GherkinParseError(1, 1, ONLY_UTF_8)));
        }
        List<Envelope> envelopes;
        try {
            envelopes = envelopes(source, false);
        } catch (IllegalArgumentException ex) {
            // Charset exceptions are IllegalArgumentExceptions; utf8Only already rejects what would cause them
            return new GherkinParseResult.Invalid(List.of(new GherkinParseError(1, 1, ONLY_UTF_8)));
        }
        List<GherkinParseError> errors = envelopes.stream().flatMap(envelope -> envelope.getParseError().stream())
                .map(GherkinAdapter::error).sorted(ERROR_ORDER).toList();
        if (!errors.isEmpty()) {
            return new GherkinParseResult.Invalid(errors);
        }
        Optional<Feature> feature = envelopes.stream().flatMap(envelope -> envelope.getGherkinDocument().stream())
                .flatMap(document -> document.getFeature().stream()).findFirst();
        if (feature.isEmpty()) {
            return new GherkinParseResult.Invalid(List.of(new GherkinParseError(1, 1, NO_FEATURE)));
        }
        return new GherkinParseResult.Valid(feature(feature.get()));
    }

    /**
     * The test cases of a valid text, in text order, as Cucumber's pickles (D13). A Background is never a test case,
     * and an outline without example rows has none.
     *
     * @throws IllegalArgumentException when the text isn't valid Gherkin with a Feature
     */
    public List<CompiledTestCase> testCases(String source) {
        List<Envelope> envelopes = envelopes(source, true);
        Feature feature = envelopes.stream().flatMap(envelope -> envelope.getGherkinDocument().stream())
                .flatMap(document -> document.getFeature().stream()).findFirst()
                .filter(found -> envelopes.stream().noneMatch(envelope -> envelope.getParseError().isPresent()))
                .orElseThrow(() -> new IllegalArgumentException("The text is not valid Gherkin with a Feature"));
        AstIndex index = new AstIndex(feature);
        return envelopes.stream().flatMap(envelope -> envelope.getPickle().stream())
                .map(pickle -> testCase(pickle, index)).toList();
    }

    /**
     * D21 #1: whether the text's encoding pragma, if any, names UTF-8 (or an alias Java resolves to it). The pragma is
     * found as the parser finds it: the first line matching it among the leading comment and blank lines, after an
     * optional byte order mark.
     */
    private static boolean utf8Only(String source) {
        String text = source.startsWith("\uFEFF") ? source.substring(1) : source;
        for (String line : text.split("[\\n\\r]")) {
            if (!COMMENT_OR_EMPTY_LINE.matcher(line).find()) {
                return true;
            }
            Matcher pragma = ENCODING_PRAGMA.matcher(line);
            if (pragma.find()) {
                try {
                    return Charset.forName(pragma.group(1).toUpperCase(Locale.ROOT)).equals(StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ex) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<Envelope> envelopes(String source, boolean pickles) {
        GherkinParser parser = GherkinParser.builder().includeSource(false).includeGherkinDocument(true)
                .includePickles(pickles).build();
        return parser.parse(URI, source.getBytes(StandardCharsets.UTF_8)).toList();
    }

    private static GherkinParseError error(ParseError error) {
        Location location = error.getSource().getLocation()
                .orElseThrow(() -> new IllegalStateException("The parser reported an error without a location"));
        int column = location.getColumn().map(Long::intValue).orElse(1);
        return new GherkinParseError(location.getLine().intValue(), Math.max(column, 1), error.getMessage());
    }

    private ParsedFeature feature(Feature feature) {
        GherkinDialect dialect = dialects.getDialect(feature.getLanguage()).orElseThrow();
        List<ParsedChild> children = new ArrayList<>();
        for (FeatureChild child : feature.getChildren()) {
            child.getBackground().ifPresent(background -> children.add(background(background, dialect)));
            child.getScenario().ifPresent(scenario -> children.add(scenario(scenario, dialect)));
            child.getRule().ifPresent(rule -> children.add(rule(rule, dialect)));
        }
        return new ParsedFeature(feature.getLanguage(), keyword(feature.getKeyword()), feature.getName(),
                feature.getDescription(), line(feature.getLocation()), tags(feature.getTags()), children);
    }

    private ParsedRule rule(Rule rule, GherkinDialect dialect) {
        List<ParsedScenario> children = new ArrayList<>();
        for (RuleChild child : rule.getChildren()) {
            child.getBackground().ifPresent(background -> children.add(background(background, dialect)));
            child.getScenario().ifPresent(scenario -> children.add(scenario(scenario, dialect)));
        }
        return new ParsedRule(keyword(rule.getKeyword()), rule.getName(), rule.getDescription(),
                line(rule.getLocation()), tags(rule.getTags()), children);
    }

    private ParsedScenario background(Background background, GherkinDialect dialect) {
        return new ParsedScenario(ScenarioKind.BACKGROUND, keyword(background.getKeyword()), background.getName(),
                background.getDescription(), line(background.getLocation()), List.of(),
                steps(background.getSteps(), dialect), List.of());
    }

    private ParsedScenario scenario(Scenario scenario, GherkinDialect dialect) {
        // Scenario Outline and Scenario Template are one kind; a Scenario that has Examples is an outline too,
        // because Cucumber runs one test case per row (D13), and only outlines have examples (ERD)
        boolean outline = dialect.getScenarioOutlineKeywords().contains(scenario.getKeyword())
                || !scenario.getExamples().isEmpty();
        List<ParsedExamples> examples = scenario.getExamples().stream().map(GherkinAdapter::examples).toList();
        return new ParsedScenario(outline ? ScenarioKind.SCENARIO_OUTLINE : ScenarioKind.SCENARIO,
                keyword(scenario.getKeyword()), scenario.getName(), scenario.getDescription(),
                line(scenario.getLocation()), tags(scenario.getTags()), steps(scenario.getSteps(), dialect), examples);
    }

    private static ParsedExamples examples(Examples examples) {
        List<String> header = examples.getTableHeader().map(GherkinAdapter::cells).orElse(List.of());
        List<ParsedRow> rows = examples.getTableBody().stream()
                .map(row -> new ParsedRow(line(row.getLocation()), cells(row))).toList();
        return new ParsedExamples(keyword(examples.getKeyword()), examples.getName(), examples.getDescription(),
                line(examples.getLocation()), tags(examples.getTags()), header, rows);
    }

    private static List<ParsedStep> steps(List<Step> steps, GherkinDialect dialect) {
        return steps.stream().map(step -> new ParsedStep(keyword(step.getKeyword()),
                keywordType(step.getKeyword(), dialect), step.getText(), line(step.getLocation()),
                step.getDataTable().map(GherkinAdapter::table).orElse(null),
                step.getDocString().map(GherkinAdapter::docString).orElse(null))).toList();
    }

    /**
     * The normalised type from the dialect's keyword lists (keywords there keep their trailing space).
     */
    private static StepKeyword keywordType(String keyword, GherkinDialect dialect) {
        if (ASTERISK.equals(keyword)) {
            return StepKeyword.ASTERISK;
        }
        if (dialect.getGivenKeywords().contains(keyword)) {
            return StepKeyword.GIVEN;
        }
        if (dialect.getWhenKeywords().contains(keyword)) {
            return StepKeyword.WHEN;
        }
        if (dialect.getThenKeywords().contains(keyword)) {
            return StepKeyword.THEN;
        }
        if (dialect.getAndKeywords().contains(keyword)) {
            return StepKeyword.AND;
        }
        if (dialect.getButKeywords().contains(keyword)) {
            return StepKeyword.BUT;
        }
        throw new IllegalStateException("A step keyword is not in its dialect");
    }

    private static List<List<String>> table(DataTable table) {
        return table.getRows().stream().map(GherkinAdapter::cells).toList();
    }

    private static ParsedDocString docString(DocString docString) {
        return new ParsedDocString(docString.getContent(), docString.getMediaType().orElse(null));
    }

    private static List<String> cells(TableRow row) {
        return row.getCells().stream().map(TableCell::getValue).toList();
    }

    /**
     * Each tag once, at its first position (D19 #11).
     */
    private static List<String> tags(List<Tag> tags) {
        return List.copyOf(new LinkedHashSet<>(tags.stream().map(Tag::getName).toList()));
    }

    private static String keyword(String keyword) {
        return keyword.stripTrailing();
    }

    private static int line(Location location) {
        return location.getLine().intValue();
    }

    private static CompiledTestCase testCase(Pickle pickle, AstIndex index) {
        List<String> astNodeIds = pickle.getAstNodeIds();
        int scenarioIndex = index.scenarioIndex(astNodeIds.get(0));
        Integer rowIndex = astNodeIds.size() > 1 ? index.rowIndex(astNodeIds.get(1)) : null;
        int line = astNodeIds.size() > 1 ? index.line(astNodeIds.get(1)) : index.line(astNodeIds.get(0));
        List<String> tags = List.copyOf(new LinkedHashSet<>(pickle.getTags().stream()
                .map(tag -> tag.getName()).toList()));
        List<CompiledStep> steps = pickle.getSteps().stream().map(step -> step(step, index)).toList();
        return new CompiledTestCase(scenarioIndex, rowIndex, pickle.getName(), tags, steps, line);
    }

    private static CompiledStep step(PickleStep step, AstIndex index) {
        Optional<PickleStepArgument> argument = step.getArgument();
        List<List<String>> dataTable = argument.flatMap(PickleStepArgument::getDataTable)
                .map(table -> table.getRows().stream()
                        .map(row -> row.getCells().stream().map(cell -> cell.getValue()).toList()).toList())
                .orElse(null);
        ParsedDocString docString = argument.flatMap(PickleStepArgument::getDocString)
                .map(doc -> new ParsedDocString(doc.getContent(), doc.getMediaType().orElse(null))).orElse(null);
        return new CompiledStep(index.keyword(step.getAstNodeIds().get(0)), step.getText(), dataTable, docString);
    }

    /**
     * The AST's node ids, mapped to text-order positions, lines and step keywords.
     */
    private static final class AstIndex {

        private final Map<String, Integer> scenarioIndexes = new HashMap<>();
        private final Map<String, Integer> rowIndexes = new HashMap<>();
        private final Map<String, Integer> lines = new HashMap<>();
        private final Map<String, String> keywords = new HashMap<>();

        AstIndex(Feature feature) {
            for (FeatureChild child : feature.getChildren()) {
                child.getBackground().ifPresent(this::add);
                child.getScenario().ifPresent(this::add);
                child.getRule().ifPresent(rule -> rule.getChildren().forEach(ruleChild -> {
                    ruleChild.getBackground().ifPresent(this::add);
                    ruleChild.getScenario().ifPresent(this::add);
                }));
            }
        }

        private void add(Background background) {
            scenarioIndexes.put(background.getId(), scenarioIndexes.size());
            lines.put(background.getId(), GherkinAdapter.line(background.getLocation()));
            background.getSteps()
                    .forEach(step -> keywords.put(step.getId(), GherkinAdapter.keyword(step.getKeyword())));
        }

        private void add(Scenario scenario) {
            scenarioIndexes.put(scenario.getId(), scenarioIndexes.size());
            lines.put(scenario.getId(), GherkinAdapter.line(scenario.getLocation()));
            scenario.getSteps().forEach(step -> keywords.put(step.getId(), GherkinAdapter.keyword(step.getKeyword())));
            scenario.getExamples().forEach(examples -> examples.getTableBody().forEach(row -> {
                rowIndexes.put(row.getId(), rowIndexes.size());
                lines.put(row.getId(), GherkinAdapter.line(row.getLocation()));
            }));
        }

        int scenarioIndex(String astNodeId) {
            return required(scenarioIndexes, astNodeId);
        }

        int rowIndex(String astNodeId) {
            return required(rowIndexes, astNodeId);
        }

        int line(String astNodeId) {
            return required(lines, astNodeId);
        }

        String keyword(String astNodeId) {
            String keyword = keywords.get(astNodeId);
            if (keyword == null) {
                throw new IllegalStateException("A pickle step can't be mapped to its step");
            }
            return keyword;
        }

        private static int required(Map<String, Integer> map, String astNodeId) {
            Integer value = map.get(astNodeId);
            if (value == null) {
                throw new IllegalStateException("A pickle can't be mapped to its scenario or example row");
            }
            return value;
        }
    }
}
