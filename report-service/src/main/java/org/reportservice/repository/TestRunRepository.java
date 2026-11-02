package org.reportservice.repository;

import org.reportservice.model.TestRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Test runs and the project write lock (D14). */
public interface TestRunRepository extends JpaRepository<TestRun, UUID> {

    /** The run, if it belongs to the project; a run of another project is "not found" (D13). */
    Optional<TestRun> findByIdAndProjectId(UUID id, UUID projectId);

    /** The project's runs, newest first ({@code createdAt}, then {@code id}, both descending; AC-5.1). */
    Page<TestRun> findByProjectIdOrderByCreatedAtDescIdDesc(UUID projectId, Pageable pageable);

    /**
     * Serialises writes in one project until the current transaction ends (D14, as scenario-service): a
     * transaction-scoped advisory lock keyed by the project id. The wait is bounded by the connection's
     * {@code lock_timeout}. Call it as the first statement of every write transaction (token create and revoke,
     * upload, plan, record, complete, cancel).
     */
    default void lockProject(UUID projectId) {
        advisoryLock(projectId.getMostSignificantBits() ^ projectId.getLeastSignificantBits());
    }

    @Query(value = "SELECT CAST(pg_advisory_xact_lock(:key) AS text)", nativeQuery = true)
    String advisoryLock(@Param("key") long key);
}
