package org.reportservice.service.impl;

import org.reportservice.dto.response.TestCaseResultResponse;
import org.reportservice.dto.response.TestRunResponse;
import org.reportservice.model.TestCaseResult;
import org.reportservice.model.TestRun;
import org.reportservice.repository.BuildRepository;
import org.reportservice.repository.EnvironmentRepository;
import org.reportservice.repository.RunSummaryGroup;
import org.reportservice.repository.TestCaseResultRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the API views of stored runs and results (contract C1 {@code TestRun}, {@code TestCaseResult}): a run with its
 * environment, build and summary (D12), the summary from one aggregate query. Call it inside a transaction.
 */
@Component
class RunViews {

    private final EnvironmentRepository environments;
    private final BuildRepository builds;
    private final TestCaseResultRepository results;

    RunViews(EnvironmentRepository environments, BuildRepository builds, TestCaseResultRepository results) {
        this.environments = environments;
        this.builds = builds;
        this.results = results;
    }

    TestRunResponse run(TestRun run) {
        return new TestRunResponse(run.getId(), run.getProjectId(), run.getName(), run.getSource(), run.getStatus(),
                run.getEnvironmentId() == null ? null
                        : RunAttributes.response(environments.findById(run.getEnvironmentId()).orElseThrow()),
                run.getBuildId() == null ? null
                        : RunAttributes.response(builds.findById(run.getBuildId()).orElseThrow()),
                run.getTagExpression(), run.getCreatedBy(), run.getStartedAt(), run.getFinishedAt(),
                run.getCreatedAt(), run.getUpdatedAt(),
                RunSummaries.summarize(items(results.summaryGroups(run.getId()))));
    }

    static TestCaseResultResponse result(TestCaseResult result) {
        return new TestCaseResultResponse(result.getId(), result.getTestRunId(), result.getPosition(),
                result.getFeatureId(), result.getFeatureVersion(), result.getScenarioId(), result.getExampleRowId(),
                result.getFeaturePath(), result.getFeatureName(), result.getScenarioName(), result.getTags(),
                result.getLine(), result.getStatus(), result.getAttempt(), result.getExecutedBy(), result.getComment(),
                result.getFinishedAt(), result.getDurationMs(), result.getCreatedAt(), result.getUpdatedAt());
    }

    /**
     * The groups as summary items, in order of first position: one item per result, the group's summed duration on
     * its first item only, so that counts, the duration sum and the feature order are exactly D12's.
     */
    static List<RunSummaries.Item> items(List<RunSummaryGroup> groups) {
        List<RunSummaries.Item> items = new ArrayList<>();
        for (RunSummaryGroup group : groups) {
            for (long i = 0; i < group.getCount(); i++) {
                items.add(new RunSummaries.Item(group.getFeaturePath(), group.getFeatureName(), group.getStatus(),
                        i == 0 ? group.getDurationMs() : null));
            }
        }
        return items;
    }
}
