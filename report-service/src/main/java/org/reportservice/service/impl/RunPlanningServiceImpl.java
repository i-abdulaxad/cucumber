package org.reportservice.service.impl;

import org.reportservice.client.ScenarioServiceClient;
import org.reportservice.client.ScenarioServiceClient.Feature;
import org.reportservice.client.ScenarioServiceClient.TestCase;
import org.reportservice.dto.request.PlanRunRequest;
import org.reportservice.dto.response.TestRunResponse;
import org.reportservice.enums.ExecutionStatus;
import org.reportservice.enums.StepResultKind;
import org.reportservice.enums.TestRunSource;
import org.reportservice.enums.TestRunStatus;
import org.reportservice.exception.FeatureChangedException;
import org.reportservice.exception.FeatureNotPlannableException;
import org.reportservice.exception.InvalidFieldsException;
import org.reportservice.exception.RunEmptyException;
import org.reportservice.exception.RunTooLargeException;
import org.reportservice.model.Build;
import org.reportservice.model.Environment;
import org.reportservice.model.StepResult;
import org.reportservice.model.TestCaseResult;
import org.reportservice.model.TestRun;
import org.reportservice.repository.StepResultRepository;
import org.reportservice.repository.TestCaseResultRepository;
import org.reportservice.repository.TestRunRepository;
import org.reportservice.security.CallerPrincipal;
import org.reportservice.security.ProjectAccessChecker;
import org.reportservice.security.ProjectRole;
import org.reportservice.service.RunPlanningService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Manual run planning (US-3; D6, D7, D10, D11, D13, D14), in D13's order: check the request (400), the access check
 * ({@code MEMBER}, project not archived), then scenario-service, outside any transaction: the feature list, each
 * selected feature's test cases in request order, the {@code tag} filter (exact, case-sensitive), the size rules, and
 * the list again; a selected feature whose {@code currentVersion} changed (or that disappeared) is 409
 * FEATURE_CHANGED. Then one transaction: the project lock, environment and build by exact name, the run (PLANNED), its
 * results (UNKNOWN, linked by feature, version, scenario and example row) and their step snapshots (UNKNOWN), in
 * batches.
 */
@Service
public class RunPlanningServiceImpl implements RunPlanningService {

    static final int MAX_FEATURES = 20;
    static final int MAX_TEST_CASES = 2_000;
    static final int MAX_NAME_CODE_POINTS = 255;
    static final int MAX_ENVIRONMENT_CODE_POINTS = 100;
    static final int MAX_TAG_CODE_POINTS = 1_000;

    private static final Pattern LOWER_CASE_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final ProjectAccessChecker accessChecker;
    private final ScenarioServiceClient scenarios;
    private final TestRunRepository runs;
    private final TestCaseResultRepository results;
    private final StepResultRepository steps;
    private final RunAttributes attributes;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;

    public RunPlanningServiceImpl(ProjectAccessChecker accessChecker, ScenarioServiceClient scenarios,
                                  TestRunRepository runs, TestCaseResultRepository results, StepResultRepository steps,
                                  RunAttributes attributes, Clock clock,
                                  PlatformTransactionManager transactionManager) {
        this.accessChecker = accessChecker;
        this.scenarios = scenarios;
        this.runs = runs;
        this.results = results;
        this.steps = steps;
        this.attributes = attributes;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public TestRunResponse plan(UUID projectId, CallerPrincipal caller, PlanRunRequest request) {
        List<UUID> featureIds = check(request);
        accessChecker.requireWrite(projectId, ProjectRole.MEMBER);

        Map<UUID, Feature> listed = byId(scenarios.activeFeatures(projectId, caller.token()));
        List<UUID> unplannable = featureIds.stream().filter(id -> !listed.containsKey(id)).toList();
        if (!unplannable.isEmpty()) {
            throw new FeatureNotPlannableException(unplannable);
        }
        List<Planned> planned = new ArrayList<>();
        for (UUID featureId : featureIds) {
            for (TestCase testCase : scenarios.testCases(projectId, featureId, caller.token())) {
                if (request.tag() == null || testCase.tags().contains(request.tag())) {
                    planned.add(new Planned(listed.get(featureId), testCase));
                }
            }
        }
        if (planned.isEmpty()) {
            throw new RunEmptyException();
        }
        if (planned.size() > MAX_TEST_CASES) {
            throw new RunTooLargeException();
        }
        Map<UUID, Feature> relisted = byId(scenarios.activeFeatures(projectId, caller.token()));
        for (UUID featureId : featureIds) {
            Feature now = relisted.get(featureId);
            if (now == null || now.currentVersion() != listed.get(featureId).currentVersion()) {
                throw new FeatureChangedException();
            }
        }
        return writeTransaction.execute(status -> store(projectId, caller.userId(), request, planned));
    }

    private TestRunResponse store(UUID projectId, UUID userId, PlanRunRequest request, List<Planned> planned) {
        runs.lockProject(projectId);
        Instant now = Instant.now(clock);
        Environment environment = request.environment() == null ? null
                : attributes.environment(projectId, request.environment(), now);
        Build build = request.build() == null ? null : attributes.build(projectId, request.build(), now);

        TestRun run = new TestRun();
        run.setId(UUID.randomUUID());
        run.setProjectId(projectId);
        run.setName(request.name());
        run.setSource(TestRunSource.MANUAL);
        run.setStatus(TestRunStatus.PLANNED);
        run.setEnvironmentId(environment == null ? null : environment.getId());
        run.setBuildId(build == null ? null : build.getId());
        run.setTagExpression(request.tag());
        run.setCreatedBy(userId);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runs.save(run);

        List<TestCaseResult> resultRows = new ArrayList<>(planned.size());
        List<StepResult> stepRows = new ArrayList<>();
        List<RunSummaries.Item> items = new ArrayList<>(planned.size());
        for (int position = 0; position < planned.size(); position++) {
            Planned item = planned.get(position);
            TestCaseResult result = result(run.getId(), position + 1, item, now);
            resultRows.add(result);
            List<ScenarioServiceClient.Step> caseSteps = item.testCase().steps();
            for (int s = 0; s < caseSteps.size(); s++) {
                stepRows.add(step(result.getId(), s + 1, caseSteps.get(s), now));
            }
            items.add(new RunSummaries.Item(item.feature().path(), item.feature().name(), ExecutionStatus.UNKNOWN,
                    null));
        }
        results.saveAll(resultRows);
        steps.saveAll(stepRows);
        return new TestRunResponse(run.getId(), projectId, run.getName(), run.getSource(), run.getStatus(),
                RunAttributes.response(environment), RunAttributes.response(build), run.getTagExpression(), userId,
                null, null, now, now, RunSummaries.summarize(items));
    }

    private static List<UUID> check(PlanRunRequest request) {
        Map<String, String> invalid = new TreeMap<>();
        if (!TextRules.isName(request.name(), MAX_NAME_CODE_POINTS)) {
            invalid.put("name", "Name must be 1 to 255 characters, not blank, without control characters");
        }
        List<UUID> featureIds = new ArrayList<>();
        List<String> sent = request.featureIds();
        if (sent == null || sent.isEmpty() || sent.size() > MAX_FEATURES
                || sent.stream().anyMatch(id -> id == null || !LOWER_CASE_UUID.matcher(id).matches())
                || new HashSet<>(sent).size() != sent.size()) {
            invalid.put("featureIds", "Feature ids must be 1 to 20 distinct lower-case UUIDs");
        } else {
            sent.forEach(id -> featureIds.add(UUID.fromString(id)));
        }
        if (request.tag() != null && !isTag(request.tag())) {
            invalid.put("tag", "Tag must be @ and at least one character, without whitespace, at most 1,000 "
                    + "characters");
        }
        if (request.environment() != null && !TextRules.isName(request.environment(), MAX_ENVIRONMENT_CODE_POINTS)) {
            invalid.put("environment", "Environment must be 1 to 100 characters, not blank, without control "
                    + "characters");
        }
        if (request.build() != null && !TextRules.isName(request.build(), MAX_NAME_CODE_POINTS)) {
            invalid.put("build", "Build must be 1 to 255 characters, not blank, without control characters");
        }
        if (!invalid.isEmpty()) {
            throw new InvalidFieldsException(invalid);
        }
        return featureIds;
    }

    /**
     * {@code @} and at least one more code point; no whitespace, control character or lone surrogate (D10).
     */
    private static boolean isTag(String tag) {
        if (!tag.startsWith("@") || tag.length() < 2 || !TextRules.isName(tag, MAX_TAG_CODE_POINTS)) {
            return false;
        }
        return tag.codePoints().noneMatch(codePoint -> Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint) || codePoint == 0xFEFF);
    }

    private static Map<UUID, Feature> byId(List<Feature> features) {
        return features.stream().collect(Collectors.toMap(Feature::id, Function.identity(), (first, second) -> first,
                LinkedHashMap::new));
    }

    private static TestCaseResult result(UUID runId, int position, Planned item, Instant now) {
        TestCaseResult result = new TestCaseResult();
        result.setId(UUID.randomUUID());
        result.setTestRunId(runId);
        result.setPosition(position);
        result.setFeatureId(item.feature().id());
        result.setFeatureVersion(item.feature().currentVersion());
        result.setScenarioId(item.testCase().scenarioId());
        result.setExampleRowId(item.testCase().exampleRowId());
        result.setFeaturePath(item.feature().path());
        result.setFeatureName(item.feature().name());
        result.setScenarioName(item.testCase().name());
        result.setTags(List.copyOf(new LinkedHashSet<>(item.testCase().tags())));
        result.setLine(item.testCase().line());
        result.setStatus(ExecutionStatus.UNKNOWN);
        result.setAttempt(1);
        result.setCreatedAt(now);
        result.setUpdatedAt(now);
        return result;
    }

    private static StepResult step(UUID resultId, int position, ScenarioServiceClient.Step source, Instant now) {
        StepResult step = new StepResult();
        step.setId(UUID.randomUUID());
        step.setTestCaseResultId(resultId);
        step.setPosition(position);
        step.setKind(StepResultKind.STEP);
        step.setKeywordText(source.keyword());
        step.setStepText(source.text());
        step.setDataTable(source.dataTable());
        step.setDocString(source.docString());
        step.setStatus(ExecutionStatus.UNKNOWN);
        step.setCreatedAt(now);
        step.setUpdatedAt(now);
        return step;
    }

    private record Planned(Feature feature, TestCase testCase) {
    }
}
