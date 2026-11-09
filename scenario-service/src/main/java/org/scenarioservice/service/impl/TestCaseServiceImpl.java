package org.scenarioservice.service.impl;

import org.scenarioservice.dto.response.DocStringResponse;
import org.scenarioservice.dto.response.TestCaseResponse;
import org.scenarioservice.dto.response.TestCaseStepResponse;
import org.scenarioservice.exception.FeatureNotFoundException;
import org.scenarioservice.gherkin.CompiledTestCase;
import org.scenarioservice.gherkin.GherkinAdapter;
import org.scenarioservice.model.ExampleRow;
import org.scenarioservice.model.Examples;
import org.scenarioservice.model.Feature;
import org.scenarioservice.model.Rule;
import org.scenarioservice.model.Scenario;
import org.scenarioservice.repository.ExampleRowRepository;
import org.scenarioservice.repository.ExamplesRepository;
import org.scenarioservice.repository.FeatureRepository;
import org.scenarioservice.repository.FeatureVersionRepository;
import org.scenarioservice.repository.RuleRepository;
import org.scenarioservice.repository.ScenarioRepository;
import org.scenarioservice.security.ProjectAccessChecker;
import org.scenarioservice.service.TestCaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Test cases (US-4, D13): the access check first (D5), then, in a read-only transaction, the current version is
 * compiled with the parser's pickle compiler ({@link GherkinAdapter#testCases}), and each test case gets its stored
 * id: the scenario's for a Scenario, the example row's for an Outline row. Positions in text order map the compiled
 * test cases to the stored rows, which always reflect the current version.
 */
@Service
public class TestCaseServiceImpl implements TestCaseService {

    private static final String SCENARIO = "SCENARIO";
    private static final String EXAMPLE_ROW = "EXAMPLE_ROW";

    private final ProjectAccessChecker accessChecker;
    private final GherkinAdapter gherkin;
    private final FeatureRepository features;
    private final FeatureVersionRepository versions;
    private final RuleRepository rules;
    private final ScenarioRepository scenarios;
    private final ExamplesRepository examples;
    private final ExampleRowRepository exampleRows;
    private final TransactionTemplate readTransaction;

    public TestCaseServiceImpl(ProjectAccessChecker accessChecker, GherkinAdapter gherkin,
                               FeatureRepository features, FeatureVersionRepository versions, RuleRepository rules,
                               ScenarioRepository scenarios, ExamplesRepository examples,
                               ExampleRowRepository exampleRows, PlatformTransactionManager transactionManager) {
        this.accessChecker = accessChecker;
        this.gherkin = gherkin;
        this.features = features;
        this.versions = versions;
        this.rules = rules;
        this.scenarios = scenarios;
        this.examples = examples;
        this.exampleRows = exampleRows;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public List<TestCaseResponse> testCases(UUID projectId, UUID featureId) {
        accessChecker.requireRead(projectId);
        return readTransaction.execute(status -> {
            Feature feature = features.findByIdAndProjectId(featureId, projectId)
                    .orElseThrow(FeatureNotFoundException::new);
            String source = versions.findByFeatureIdAndVersion(featureId, feature.getCurrentVersion())
                    .orElseThrow(() -> new IllegalStateException("A feature has no current version")).getSource();
            List<Scenario> scenariosInTextOrder = scenariosInTextOrder(featureId);
            List<ExampleRow> rowsInTextOrder = rowsInTextOrder(scenariosInTextOrder);
            return gherkin.testCases(source).stream()
                    .map(testCase -> response(testCase, scenariosInTextOrder, rowsInTextOrder)).toList();
        });
    }

    private static TestCaseResponse response(CompiledTestCase testCase, List<Scenario> scenarios,
                                             List<ExampleRow> rows) {
        require(testCase.scenarioIndex() < scenarios.size());
        UUID scenarioId = scenarios.get(testCase.scenarioIndex()).getId();
        UUID rowId = null;
        if (testCase.rowIndex() != null) {
            require(testCase.rowIndex() < rows.size());
            rowId = rows.get(testCase.rowIndex()).getId();
        }
        List<TestCaseStepResponse> steps = testCase.steps().stream().map(step -> new TestCaseStepResponse(
                step.keyword(), step.text(), step.dataTable(), step.docString() == null ? null
                : new DocStringResponse(step.docString().content(), step.docString().mediaType()))).toList();
        return new TestCaseResponse(rowId == null ? scenarioId : rowId, rowId == null ? SCENARIO : EXAMPLE_ROW,
                scenarioId, rowId, testCase.name(), testCase.tags(), testCase.line(), steps);
    }

    /**
     * Every stored scenario (Backgrounds included) in text order: the Feature's children, a Rule's in its place.
     */
    private List<Scenario> scenariosInTextOrder(UUID featureId) {
        List<Rule> storedRules = rules.findByFeatureId(featureId);
        List<Scenario> storedScenarios = scenarios.findByFeatureId(featureId);
        List<Object> featureLevel = new ArrayList<>(storedRules);
        storedScenarios.stream().filter(scenario -> scenario.getRuleId() == null).forEach(featureLevel::add);
        featureLevel.sort(Comparator.comparingInt(TestCaseServiceImpl::position));
        List<Scenario> ordered = new ArrayList<>();
        for (Object child : featureLevel) {
            if (child instanceof Scenario scenario) {
                ordered.add(scenario);
            } else {
                UUID ruleId = ((Rule) child).getId();
                storedScenarios.stream().filter(scenario -> Objects.equals(scenario.getRuleId(), ruleId))
                        .sorted(Comparator.comparingInt(Scenario::getPosition)).forEach(ordered::add);
            }
        }
        return ordered;
    }

    /**
     * Every stored example row in text order: by scenario, then Examples block, then row position.
     */
    private List<ExampleRow> rowsInTextOrder(List<Scenario> scenariosInTextOrder) {
        List<UUID> scenarioIds = scenariosInTextOrder.stream().map(Scenario::getId).toList();
        if (scenarioIds.isEmpty()) {
            return List.of();
        }
        List<Examples> blocks = examples.findByScenarioIdIn(scenarioIds);
        if (blocks.isEmpty()) {
            return List.of();
        }
        List<ExampleRow> storedRows = exampleRows.findByExamplesIdIn(blocks.stream().map(Examples::getId).toList());
        List<ExampleRow> ordered = new ArrayList<>();
        for (UUID scenarioId : scenarioIds) {
            blocks.stream().filter(block -> block.getScenarioId().equals(scenarioId))
                    .sorted(Comparator.comparingInt(Examples::getPosition))
                    .forEach(block -> storedRows.stream().filter(row -> row.getExamplesId().equals(block.getId()))
                            .sorted(Comparator.comparingInt(ExampleRow::getPosition)).forEach(ordered::add));
        }
        return ordered;
    }

    private static int position(Object ruleOrScenario) {
        return ruleOrScenario instanceof Rule rule ? rule.getPosition() : ((Scenario) ruleOrScenario).getPosition();
    }

    private static void require(boolean consistent) {
        if (!consistent) {
            throw new IllegalStateException("The stored structure doesn't match the current version's text");
        }
    }
}
