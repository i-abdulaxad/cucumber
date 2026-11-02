package org.reportservice.service.impl;

import org.reportservice.dto.response.BuildResponse;
import org.reportservice.dto.response.EnvironmentResponse;
import org.reportservice.dto.response.ResultPageResponse;
import org.reportservice.dto.response.RunPageResponse;
import org.reportservice.dto.response.RunSummaryResponse;
import org.reportservice.dto.response.StepResultResponse;
import org.reportservice.dto.response.TestCaseResultDetailResponse;
import org.reportservice.dto.response.TestRunListItemResponse;
import org.reportservice.dto.response.TestRunResponse;
import org.reportservice.enums.ExecutionStatus;
import org.reportservice.exception.InvalidFieldsException;
import org.reportservice.exception.ResultNotFoundException;
import org.reportservice.exception.RunNotFoundException;
import org.reportservice.model.Build;
import org.reportservice.model.Environment;
import org.reportservice.model.TestCaseResult;
import org.reportservice.model.TestRun;
import org.reportservice.repository.BuildRepository;
import org.reportservice.repository.EnvironmentRepository;
import org.reportservice.repository.RunStatusCount;
import org.reportservice.repository.StepResultRepository;
import org.reportservice.repository.TestCaseResultRepository;
import org.reportservice.repository.TestRunRepository;
import org.reportservice.security.ProjectAccessChecker;
import org.reportservice.security.ProjectRole;
import org.reportservice.service.RunQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reading runs and results (US-5; D7, D12, D13): the read parameters first (400), then the access check
 * ({@code READER}; archived projects included), then one read-only transaction. The run list takes one aggregate query
 * for the counts of its page; the run view one for its summary.
 */
@Service
public class RunQueryServiceImpl implements RunQueryService {

    static final int DEFAULT_RUN_PAGE_SIZE = 20;
    static final int MAX_RUN_PAGE_SIZE = 100;
    static final int DEFAULT_RESULT_PAGE_SIZE = 100;
    static final int MAX_RESULT_PAGE_SIZE = 500;

    /** One row of the first page: enough for the total of an empty far page (RE13). */
    private static final PageRequest FIRST_ROW = PageRequest.of(0, 1);

    private final ProjectAccessChecker accessChecker;
    private final TestRunRepository runs;
    private final TestCaseResultRepository results;
    private final StepResultRepository steps;
    private final EnvironmentRepository environments;
    private final BuildRepository builds;
    private final RunViews views;
    private final TransactionTemplate readTransaction;

    public RunQueryServiceImpl(ProjectAccessChecker accessChecker, TestRunRepository runs,
                               TestCaseResultRepository results, StepResultRepository steps,
                               EnvironmentRepository environments, BuildRepository builds, RunViews views,
                               PlatformTransactionManager transactionManager) {
        this.accessChecker = accessChecker;
        this.runs = runs;
        this.results = results;
        this.steps = steps;
        this.environments = environments;
        this.builds = builds;
        this.views = views;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public RunPageResponse runs(UUID projectId, Integer page, Integer size) {
        int[] paging = paging(page, size, DEFAULT_RUN_PAGE_SIZE, MAX_RUN_PAGE_SIZE, null);
        accessChecker.requireRead(projectId, ProjectRole.READER);
        return readTransaction.execute(status -> {
            if (beyondOffsetRange(paging)) {
                long total = runs.findByProjectIdOrderByCreatedAtDescIdDesc(projectId, FIRST_ROW).getTotalElements();
                return new RunPageResponse(List.of(), paging[0], paging[1], total);
            }
            Page<TestRun> found = runs.findByProjectIdOrderByCreatedAtDescIdDesc(projectId,
                    PageRequest.of(paging[0], paging[1]));
            List<UUID> ids = found.getContent().stream().map(TestRun::getId).toList();
            Map<UUID, List<RunSummaries.Item>> items = new HashMap<>();
            for (RunStatusCount count : ids.isEmpty() ? List.<RunStatusCount>of() : results.statusCounts(ids)) {
                List<RunSummaries.Item> list = items.computeIfAbsent(count.getRunId(), id -> new ArrayList<>());
                for (long i = 0; i < count.getCount(); i++) {
                    list.add(new RunSummaries.Item("", "", count.getStatus(), null));
                }
            }
            Map<UUID, Environment> environmentsById =
                    byId(environments.findAllById(ids(found, TestRun::getEnvironmentId)), Environment::getId);
            Map<UUID, Build> buildsById = byId(builds.findAllById(ids(found, TestRun::getBuildId)), Build::getId);
            List<TestRunListItemResponse> listed = found.getContent().stream().map(run -> {
                RunSummaryResponse summary = RunSummaries.summarize(items.getOrDefault(run.getId(), List.of()));
                EnvironmentResponse environment = run.getEnvironmentId() == null ? null
                        : RunAttributes.response(environmentsById.get(run.getEnvironmentId()));
                BuildResponse build = run.getBuildId() == null ? null
                        : RunAttributes.response(buildsById.get(run.getBuildId()));
                return new TestRunListItemResponse(run.getId(), run.getProjectId(), run.getName(), run.getSource(),
                        run.getStatus(), environment, build, run.getTagExpression(), run.getCreatedBy(),
                        run.getStartedAt(), run.getFinishedAt(), run.getCreatedAt(), run.getUpdatedAt(),
                        summary.total(), summary.counts(), summary.passRate());
            }).toList();
            return new RunPageResponse(listed, paging[0], paging[1], found.getTotalElements());
        });
    }

    @Override
    public TestRunResponse run(UUID projectId, UUID runId) {
        accessChecker.requireRead(projectId, ProjectRole.READER);
        return readTransaction.execute(status -> views.run(found(projectId, runId)));
    }

    @Override
    public ResultPageResponse results(UUID projectId, UUID runId, String status, Integer page, Integer size) {
        int[] paging = paging(page, size, DEFAULT_RESULT_PAGE_SIZE, MAX_RESULT_PAGE_SIZE, status);
        accessChecker.requireRead(projectId, ProjectRole.READER);
        return readTransaction.execute(transaction -> {
            found(projectId, runId);
            boolean empty = beyondOffsetRange(paging);
            PageRequest request = empty ? FIRST_ROW : PageRequest.of(paging[0], paging[1]);
            Page<TestCaseResult> found = status == null ? results.findByTestRunIdOrderByPosition(runId, request)
                    : results.findByTestRunIdAndStatusOrderByPosition(runId, ExecutionStatus.valueOf(status), request);
            return new ResultPageResponse(empty ? List.of() : found.getContent().stream().map(RunViews::result)
                    .toList(), paging[0], paging[1], found.getTotalElements());
        });
    }

    @Override
    public TestCaseResultDetailResponse result(UUID projectId, UUID runId, UUID resultId) {
        accessChecker.requireRead(projectId, ProjectRole.READER);
        return readTransaction.execute(status -> {
            found(projectId, runId);
            TestCaseResult result = results.findByIdAndTestRunId(resultId, runId)
                    .orElseThrow(ResultNotFoundException::new);
            List<StepResultResponse> rows = steps.findByTestCaseResultIdOrderByPosition(resultId).stream()
                    .map(step -> new StepResultResponse(step.getPosition(), step.getKind(), step.getKeywordText(),
                            step.getStepText(), step.getDataTable(), step.getDocString(), step.getStatus(),
                            step.getDurationMs(), step.getErrorMessage()))
                    .toList();
            return new TestCaseResultDetailResponse(result.getId(), result.getTestRunId(), result.getPosition(),
                    result.getFeatureId(), result.getFeatureVersion(), result.getScenarioId(),
                    result.getExampleRowId(), result.getFeaturePath(), result.getFeatureName(),
                    result.getScenarioName(), result.getTags(), result.getLine(), result.getStatus(),
                    result.getAttempt(), result.getExecutedBy(), result.getComment(), result.getFinishedAt(),
                    result.getDurationMs(), result.getCreatedAt(), result.getUpdatedAt(), rows);
        });
    }

    /**
     * RE13: whether the page starts beyond the int offset the database query can take. Such a page is empty by
     * definition (at most 5,000 results per run, A4); its total comes from the same query at the first row.
     */
    private static boolean beyondOffsetRange(int[] paging) {
        return (long) paging[0] * paging[1] > Integer.MAX_VALUE;
    }

    private TestRun found(UUID projectId, UUID runId) {
        return runs.findByIdAndProjectId(runId, projectId).orElseThrow(RunNotFoundException::new);
    }

    /** Page and size, defaulted and checked (AC-5.4), and the status filter when given; otherwise 400. */
    private static int[] paging(Integer page, Integer size, int defaultSize, int maxSize, String status) {
        Map<String, String> invalid = new TreeMap<>();
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? defaultSize : size;
        if (pageNumber < 0) {
            invalid.put("page", "Page must be 0 or more");
        }
        if (pageSize < 1 || pageSize > maxSize) {
            invalid.put("size", "Size must be 1 to " + maxSize);
        }
        if (status != null
                && Arrays.stream(ExecutionStatus.values()).noneMatch(known -> known.name().equals(status))) {
            invalid.put("status", "Status must be one of PASSED, FAILED, SKIPPED, PENDING, UNDEFINED, AMBIGUOUS, "
                    + "UNKNOWN");
        }
        if (!invalid.isEmpty()) {
            throw new InvalidFieldsException(invalid);
        }
        return new int[]{pageNumber, pageSize};
    }

    private static List<UUID> ids(Page<TestRun> runs, Function<TestRun, UUID> id) {
        return runs.getContent().stream().map(id).filter(Objects::nonNull).distinct().toList();
    }

    private static <T> Map<UUID, T> byId(List<T> items, Function<T, UUID> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
