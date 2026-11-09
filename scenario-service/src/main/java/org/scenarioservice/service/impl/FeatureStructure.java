package org.scenarioservice.service.impl;

import org.scenarioservice.enums.ScenarioKind;
import org.scenarioservice.enums.StepArgumentType;
import org.scenarioservice.enums.TagTargetType;
import org.scenarioservice.gherkin.ParsedChild;
import org.scenarioservice.gherkin.ParsedExamples;
import org.scenarioservice.gherkin.ParsedFeature;
import org.scenarioservice.gherkin.ParsedRow;
import org.scenarioservice.gherkin.ParsedRule;
import org.scenarioservice.gherkin.ParsedScenario;
import org.scenarioservice.gherkin.ParsedStep;
import org.scenarioservice.model.ExampleRow;
import org.scenarioservice.model.Examples;
import org.scenarioservice.model.Rule;
import org.scenarioservice.model.Scenario;
import org.scenarioservice.model.Step;
import org.scenarioservice.model.Tag;
import org.scenarioservice.model.TagAssignment;
import org.scenarioservice.repository.ExampleRowRepository;
import org.scenarioservice.repository.ExamplesRepository;
import org.scenarioservice.repository.RuleRepository;
import org.scenarioservice.repository.ScenarioRepository;
import org.scenarioservice.repository.StepRepository;
import org.scenarioservice.repository.TagAssignmentRepository;
import org.scenarioservice.repository.TagRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Writes a feature's parsed structure (rules, scenarios, steps, examples, rows, tag assignments) for its current
 * version, inside the use case's transaction and under the project lock. On a save, the previous structure is
 * replaced and ids are kept as D9 says:
 * <ul>
 *     <li>a scenario keeps its id when the new version has one of the same kind with the same name in the same
 *     container (the Feature, or the n-th Rule with that name), the n-th occurrence matching the n-th;</li>
 *     <li>an example row keeps its id when its outline kept its id and an Examples block of that outline with the same
 *     header had a row with identical cells (each old row is used once, in order);</li>
 *     <li>rules, Examples blocks and steps get new ids.</li>
 * </ul>
 * Tags are created in the project's catalogue on first use; unused catalogue rows are kept (D12 allows either). The
 * project's existing tags are loaded in one query and new ones are inserted in a batch (BUG-6). Names are compared
 * exactly, as the unique index {@code (project_id, md5(name))} does. All of it runs under the project lock, so no
 * other write in the project can add the same tag in between; the index stays the safety net (409 CONFLICT).
 */
@Component
public class FeatureStructure {

    /**
     * The Feature itself as a container of scenarios.
     */
    private static final Container FEATURE = new Container(null, 0);

    private final RuleRepository rules;
    private final ScenarioRepository scenarios;
    private final StepRepository steps;
    private final ExamplesRepository examples;
    private final ExampleRowRepository exampleRows;
    private final TagRepository tags;
    private final TagAssignmentRepository tagAssignments;

    public FeatureStructure(RuleRepository rules, ScenarioRepository scenarios, StepRepository steps,
                            ExamplesRepository examples, ExampleRowRepository exampleRows, TagRepository tags,
                            TagAssignmentRepository tagAssignments) {
        this.rules = rules;
        this.scenarios = scenarios;
        this.steps = steps;
        this.examples = examples;
        this.exampleRows = exampleRows;
        this.tags = tags;
        this.tagAssignments = tagAssignments;
    }

    /**
     * Stores the structure of a new feature's first version, and returns the rows written.
     */
    public Written create(UUID projectId, UUID featureId, ParsedFeature parsed, Instant now) {
        return write(projectId, featureId, parsed, now, Previous.NONE);
    }

    /**
     * Replaces the feature's stored structure with the parsed one, keeping matched ids. The persistence context is
     * cleared on the way, so entities loaded before must be saved again (merged) afterwards.
     */
    public Written replace(UUID projectId, UUID featureId, ParsedFeature parsed, Instant now) {
        Previous previous = previous(featureId);
        tagAssignments.deleteByFeature(featureId);
        scenarios.deleteByFeature(featureId);
        rules.deleteByFeature(featureId);
        return write(projectId, featureId, parsed, now, previous);
    }

    private Written write(UUID projectId, UUID featureId, ParsedFeature parsed, Instant now, Previous previous) {
        Writer writer = new Writer(projectId, featureId, now, previous);
        writer.assign(TagTargetType.FEATURE, featureId, parsed.tags());
        Map<String, Integer> ruleOccurrences = new HashMap<>();
        Map<Named, Integer> featureOccurrences = new HashMap<>();
        List<ParsedChild> children = parsed.children();
        for (int position = 0; position < children.size(); position++) {
            ParsedChild child = children.get(position);
            if (child instanceof ParsedScenario scenario) {
                writer.scenario(scenario, null, FEATURE, position, featureOccurrences);
            } else if (child instanceof ParsedRule rule) {
                Container container = new Container(rule.name(), next(ruleOccurrences, rule.name()));
                UUID ruleId = UUID.randomUUID();
                writer.rules.add(Rule.builder().id(ruleId).featureId(featureId).keywordText(rule.keyword())
                        .name(rule.name()).description(rule.description()).position(position).line(rule.line())
                        .createdAt(now).updatedAt(now).build());
                writer.assign(TagTargetType.RULE, ruleId, rule.tags());
                Map<Named, Integer> occurrences = new HashMap<>();
                for (int index = 0; index < rule.children().size(); index++) {
                    writer.scenario(rule.children().get(index), ruleId, container, index, occurrences);
                }
            }
        }
        tags.saveAll(writer.newTags);
        rules.saveAll(writer.rules);
        scenarios.saveAll(writer.scenarios);
        steps.saveAll(writer.steps);
        examples.saveAll(writer.examples);
        exampleRows.saveAll(writer.rows);
        tagAssignments.saveAll(writer.assignments);
        return new Written(writer.rules, writer.scenarios, writer.steps, writer.examples, writer.rows);
    }

    /**
     * The previous version's scenario ids by match key, and its example rows by outline and header.
     */
    private Previous previous(UUID featureId) {
        List<Rule> oldRules = rules.findByFeatureId(featureId).stream()
                .sorted(Comparator.comparingInt(Rule::getPosition)).toList();
        List<Scenario> oldScenarios = scenarios.findByFeatureId(featureId);
        Map<ScenarioKey, UUID> scenarioIds = new HashMap<>();
        Map<Named, Integer> featureOccurrences = new HashMap<>();
        oldScenarios.stream().filter(scenario -> scenario.getRuleId() == null)
                .sorted(Comparator.comparingInt(Scenario::getPosition))
                .forEach(scenario -> scenarioIds.put(key(FEATURE, scenario.getKind(), scenario.getName(),
                        featureOccurrences), scenario.getId()));
        Map<String, Integer> ruleOccurrences = new HashMap<>();
        for (Rule rule : oldRules) {
            Container container = new Container(rule.getName(), next(ruleOccurrences, rule.getName()));
            Map<Named, Integer> occurrences = new HashMap<>();
            oldScenarios.stream().filter(scenario -> Objects.equals(scenario.getRuleId(), rule.getId()))
                    .sorted(Comparator.comparingInt(Scenario::getPosition))
                    .forEach(scenario -> scenarioIds.put(key(container, scenario.getKind(), scenario.getName(),
                            occurrences), scenario.getId()));
        }
        List<UUID> outlineIds = oldScenarios.stream().map(Scenario::getId).toList();
        List<Examples> oldExamples = outlineIds.isEmpty() ? List.of() : examples.findByScenarioIdIn(outlineIds);
        List<UUID> examplesIds = oldExamples.stream().map(Examples::getId).toList();
        Map<UUID, List<ExampleRow>> oldRows = (examplesIds.isEmpty() ? List.<ExampleRow>of()
                : exampleRows.findByExamplesIdIn(examplesIds)).stream()
                .sorted(Comparator.comparingInt(ExampleRow::getPosition))
                .collect(Collectors.groupingBy(ExampleRow::getExamplesId));
        Map<RowPool, List<OldRow>> rowPools = new HashMap<>();
        oldExamples.stream().sorted(Comparator.comparingInt(Examples::getPosition)).forEach(block -> {
            List<OldRow> pool = rowPools.computeIfAbsent(new RowPool(block.getScenarioId(), block.getHeader()),
                    ignored -> new ArrayList<>());
            oldRows.getOrDefault(block.getId(), List.of())
                    .forEach(row -> pool.add(new OldRow(row.getId(), row.getCells())));
        });
        return new Previous(scenarioIds, rowPools);
    }

    /**
     * The scenario's match key, counting its occurrence among same-kind, same-name scenarios of the container.
     */
    private static ScenarioKey key(Container container, ScenarioKind kind, String name,
                                   Map<Named, Integer> occurrences) {
        return new ScenarioKey(container, kind, name, next(occurrences, new Named(kind, name)));
    }

    private static <K> int next(Map<K, Integer> occurrences, K key) {
        return occurrences.merge(key, 1, Integer::sum);
    }

    /**
     * D21 #3: structured match keys; values are never joined into strings.
     */
    private record Container(String ruleName, int ruleOccurrence) {
    }

    private record Named(ScenarioKind kind, String name) {
    }

    private record ScenarioKey(Container container, ScenarioKind kind, String name, int occurrence) {
    }

    private record RowPool(UUID outlineId, List<String> header) {
    }

    private record OldRow(UUID id, List<String> cells) {
    }

    /**
     * The rows one write stored, in insert order, so a response can be built without reading them back.
     */
    public record Written(List<Rule> rules, List<Scenario> scenarios, List<Step> steps, List<Examples> examples,
                          List<ExampleRow> rows) {
    }

    private record Previous(Map<ScenarioKey, UUID> scenarioIds, Map<RowPool, List<OldRow>> rowPools) {

        static final Previous NONE = new Previous(Map.of(), Map.of());

        UUID scenarioId(ScenarioKey key) {
            UUID kept = scenarioIds.get(key);
            return kept != null ? kept : UUID.randomUUID();
        }

        /**
         * The id of the first unused old row of this outline and header with these cells, or a new id.
         */
        UUID rowId(UUID outlineId, List<String> header, List<String> cells) {
            List<OldRow> pool = rowPools.get(new RowPool(outlineId, header));
            if (pool != null) {
                for (int i = 0; i < pool.size(); i++) {
                    if (pool.get(i).cells().equals(cells)) {
                        return pool.remove(i).id();
                    }
                }
            }
            return UUID.randomUUID();
        }
    }

    /**
     * The rows of one write, collected in insert order (parents before children).
     */
    private final class Writer {

        private final UUID projectId;
        private final UUID featureId;
        private final Instant now;
        private final Previous previous;
        private final Map<String, Tag> tagsByName;
        private final List<Tag> newTags = new ArrayList<>();
        private final List<Rule> rules = new ArrayList<>();
        private final List<Scenario> scenarios = new ArrayList<>();
        private final List<Step> steps = new ArrayList<>();
        private final List<Examples> examples = new ArrayList<>();
        private final List<ExampleRow> rows = new ArrayList<>();
        private final List<TagAssignment> assignments = new ArrayList<>();

        Writer(UUID projectId, UUID featureId, Instant now, Previous previous) {
            this.projectId = projectId;
            this.featureId = featureId;
            this.now = now;
            this.previous = previous;
            this.tagsByName = new HashMap<>();
            tags.findByProjectId(projectId).forEach(tag -> tagsByName.put(tag.getName(), tag));
        }

        void scenario(ParsedScenario scenario, UUID ruleId, Container container, int position,
                      Map<Named, Integer> occurrences) {
            UUID id = previous.scenarioId(key(container, scenario.kind(), scenario.name(), occurrences));
            scenarios.add(Scenario.builder().id(id).featureId(featureId).ruleId(ruleId).kind(scenario.kind())
                    .keywordText(scenario.keyword()).name(scenario.name()).description(scenario.description())
                    .position(position).line(scenario.line()).createdAt(now).updatedAt(now).build());
            assign(TagTargetType.SCENARIO, id, scenario.tags());
            for (int index = 0; index < scenario.steps().size(); index++) {
                ParsedStep step = scenario.steps().get(index);
                StepArgumentType argument = step.dataTable() != null ? StepArgumentType.DATA_TABLE
                        : step.docString() != null ? StepArgumentType.DOC_STRING : StepArgumentType.NONE;
                steps.add(Step.builder().id(UUID.randomUUID()).scenarioId(id).position(index)
                        .keyword(step.keywordType()).keywordText(step.keyword()).stepText(step.text())
                        .argumentType(argument).dataTable(step.dataTable())
                        .docString(step.docString() == null ? null : step.docString().content())
                        .docStringMediaType(step.docString() == null ? null : step.docString().mediaType())
                        .line(step.line()).createdAt(now).updatedAt(now).build());
            }
            for (int index = 0; index < scenario.examples().size(); index++) {
                ParsedExamples block = scenario.examples().get(index);
                UUID examplesId = UUID.randomUUID();
                examples.add(Examples.builder().id(examplesId).scenarioId(id).keywordText(block.keyword())
                        .name(block.name()).description(block.description()).position(index)
                        .header(block.header()).line(block.line()).createdAt(now).updatedAt(now).build());
                assign(TagTargetType.EXAMPLES, examplesId, block.tags());
                for (int rowIndex = 0; rowIndex < block.rows().size(); rowIndex++) {
                    ParsedRow row = block.rows().get(rowIndex);
                    rows.add(ExampleRow.builder().id(previous.rowId(id, block.header(), row.cells()))
                            .examplesId(examplesId).position(rowIndex).cells(row.cells()).line(row.line())
                            .createdAt(now).updatedAt(now).build());
                }
            }
        }

        void assign(TagTargetType targetType, UUID targetId, List<String> names) {
            for (String name : names) {
                Tag tag = tagsByName.computeIfAbsent(name, this::catalogueTag);
                assignments.add(TagAssignment.builder().id(UUID.randomUUID()).tagId(tag.getId())
                        .featureId(featureId).targetType(targetType).targetId(targetId).createdAt(now).build());
            }
        }

        /**
         * A tag the project doesn't have yet: created now and inserted with the others in one batch.
         */
        private Tag catalogueTag(String name) {
            Tag tag = Tag.builder().id(UUID.randomUUID()).projectId(projectId).name(name).createdAt(now).updatedAt(now)
                    .build();
            newTags.add(tag);
            return tag;
        }
    }
}
