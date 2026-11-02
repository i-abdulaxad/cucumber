package org.reportservice.repository;

import org.reportservice.enums.ExecutionStatus;
import org.reportservice.model.TestCaseResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Test case results, and the one aggregate query behind a run's summary (D12). */
public interface TestCaseResultRepository extends JpaRepository<TestCaseResult, UUID> {

    /** The result, if it belongs to the run; a result of another run is "not found" (AC-4.7). */
    Optional<TestCaseResult> findByIdAndTestRunId(UUID id, UUID testRunId);

    /**
     * A run's results grouped by feature and status, in order of each group's first position (D12): one aggregate
     * query per run.
     */
    @Query("SELECT r.featurePath AS featurePath, r.featureName AS featureName, r.status AS status, "
            + "count(r) AS count, sum(r.durationMs) AS durationMs, min(r.position) AS firstPosition "
            + "FROM TestCaseResult r WHERE r.testRunId = :runId "
            + "GROUP BY r.featurePath, r.featureName, r.status ORDER BY min(r.position)")
    List<RunSummaryGroup> summaryGroups(@Param("runId") UUID runId);

    /** A run's results by position (AC-2.2, AC-5.3). */
    Page<TestCaseResult> findByTestRunIdOrderByPosition(UUID testRunId, Pageable pageable);

    /** A run's results with one status, by position (AC-5.3). */
    Page<TestCaseResult> findByTestRunIdAndStatusOrderByPosition(UUID testRunId, ExecutionStatus status,
                                                                 Pageable pageable);

    /** The result counts per run and status of a page of runs: the run list's one aggregate query (D12, AC-5.1). */
    @Query("SELECT r.testRunId AS runId, r.status AS status, count(r) AS count FROM TestCaseResult r "
            + "WHERE r.testRunId IN :runIds GROUP BY r.testRunId, r.status")
    List<RunStatusCount> statusCounts(@Param("runIds") Collection<UUID> runIds);
}
