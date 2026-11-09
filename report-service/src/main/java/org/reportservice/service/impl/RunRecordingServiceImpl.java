package org.reportservice.service.impl;

import org.reportservice.dto.request.RecordResultRequest;
import org.reportservice.dto.response.TestCaseResultResponse;
import org.reportservice.dto.response.TestRunResponse;
import org.reportservice.enums.ExecutionStatus;
import org.reportservice.enums.TestRunSource;
import org.reportservice.enums.TestRunStatus;
import org.reportservice.exception.InvalidFieldsException;
import org.reportservice.exception.ResultNotFoundException;
import org.reportservice.exception.RunClosedException;
import org.reportservice.exception.RunNotFoundException;
import org.reportservice.exception.RunNotManualException;
import org.reportservice.model.TestCaseResult;
import org.reportservice.model.TestRun;
import org.reportservice.repository.TestCaseResultRepository;
import org.reportservice.repository.TestRunRepository;
import org.reportservice.security.ProjectAccessChecker;
import org.reportservice.security.ProjectRole;
import org.reportservice.service.RunRecordingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Recording and the manual run lifecycle (US-4; D7, D8, D13, D14), in D13's order: the request (400), the access check
 * (record and complete {@code TESTER}, cancel {@code MEMBER}; 409 on an archived project), then one transaction whose
 * first statement takes the project lock, so the run's state is re-read under it: 404 RUN_NOT_FOUND, 404
 * RESULT_NOT_FOUND, 409 RUN_NOT_MANUAL, 409 RUN_CLOSED. A recording sets status, comment, {@code executedBy} and
 * {@code finishedAt} (the last write wins; UNKNOWN puts the result back among the unexecuted) and moves a PLANNED run
 * to IN_PROGRESS. Complete and cancel are idempotent on their own end state.
 */
@Service
public class RunRecordingServiceImpl implements RunRecordingService {

    static final int MAX_COMMENT_CODE_POINTS = 2_000;

    private static final Set<String> RECORDABLE = Set.of("PASSED", "FAILED", "SKIPPED", "UNKNOWN");

    private final ProjectAccessChecker accessChecker;
    private final TestRunRepository runs;
    private final TestCaseResultRepository results;
    private final RunViews views;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;

    public RunRecordingServiceImpl(ProjectAccessChecker accessChecker, TestRunRepository runs,
                                   TestCaseResultRepository results, RunViews views, Clock clock,
                                   PlatformTransactionManager transactionManager) {
        this.accessChecker = accessChecker;
        this.runs = runs;
        this.results = results;
        this.views = views;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public TestCaseResultResponse record(UUID projectId, UUID runId, UUID resultId, UUID userId,
                                         RecordResultRequest request) {
        check(request);
        accessChecker.requireWrite(projectId, ProjectRole.TESTER);
        return writeTransaction.execute(status -> {
            TestRun run = lockedRun(projectId, runId);
            TestCaseResult result = results.findByIdAndTestRunId(resultId, runId)
                    .orElseThrow(ResultNotFoundException::new);
            requireOpenManual(run);
            Instant now = Instant.now(clock);
            result.setStatus(ExecutionStatus.valueOf(request.status()));
            result.setComment(request.comment());
            result.setExecutedBy(userId);
            result.setFinishedAt(now);
            result.setUpdatedAt(now);
            if (run.getStatus() == TestRunStatus.PLANNED) {
                run.setStatus(TestRunStatus.IN_PROGRESS);
                run.setStartedAt(now);
                run.setUpdatedAt(now);
            }
            return RunViews.result(result);
        });
    }

    @Override
    public TestRunResponse complete(UUID projectId, UUID runId) {
        accessChecker.requireWrite(projectId, ProjectRole.TESTER);
        return close(projectId, runId, TestRunStatus.COMPLETED);
    }

    @Override
    public TestRunResponse cancel(UUID projectId, UUID runId) {
        accessChecker.requireWrite(projectId, ProjectRole.MEMBER);
        return close(projectId, runId, TestRunStatus.CANCELLED);
    }

    private TestRunResponse close(UUID projectId, UUID runId, TestRunStatus end) {
        return writeTransaction.execute(status -> {
            TestRun run = lockedRun(projectId, runId);
            if (run.getSource() != TestRunSource.MANUAL) {
                throw new RunNotManualException();
            }
            if (run.getStatus() != end) {
                requireOpenManual(run);
                Instant now = Instant.now(clock);
                run.setStatus(end);
                run.setFinishedAt(now);
                run.setUpdatedAt(now);
            }
            return views.run(run);
        });
    }

    private TestRun lockedRun(UUID projectId, UUID runId) {
        runs.lockProject(projectId);
        return runs.findByIdAndProjectId(runId, projectId).orElseThrow(RunNotFoundException::new);
    }

    private static void requireOpenManual(TestRun run) {
        if (run.getSource() != TestRunSource.MANUAL) {
            throw new RunNotManualException();
        }
        if (run.getStatus() == TestRunStatus.COMPLETED || run.getStatus() == TestRunStatus.CANCELLED) {
            throw new RunClosedException();
        }
    }

    private static void check(RecordResultRequest request) {
        Map<String, String> invalid = new TreeMap<>();
        if (request.status() == null || !RECORDABLE.contains(request.status())) {
            invalid.put("status", "Status must be PASSED, FAILED, SKIPPED or UNKNOWN");
        }
        if (request.comment() != null && !isComment(request.comment())) {
            invalid.put("comment", "Comment must be at most 2,000 characters, without control characters other than "
                    + "line feed and tab");
        }
        if (!invalid.isEmpty()) {
            throw new InvalidFieldsException(invalid);
        }
    }

    /**
     * At most 2,000 code points; no control character but LF and TAB; no lone surrogate (D8).
     */
    private static boolean isComment(String comment) {
        int count = 0;
        for (int i = 0; i < comment.length(); ) {
            char c = comment.charAt(i);
            if (Character.isLowSurrogate(c) || Character.isHighSurrogate(c)
                    && (i + 1 == comment.length() || !Character.isLowSurrogate(comment.charAt(i + 1)))) {
                return false;
            }
            int codePoint = comment.codePointAt(i);
            if (Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t') {
                return false;
            }
            count++;
            i += Character.charCount(codePoint);
        }
        return count <= MAX_COMMENT_CODE_POINTS;
    }
}
