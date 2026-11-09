package org.scenarioservice.repository;

import org.scenarioservice.model.Feature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Features, the "path taken" check and the project write lock (D10, D14, P13). Path comparisons happen in the
 * database with the unique index's expression ({@code md5(lower(path COLLATE "und-x-icu"))}, so the index is used)
 * plus the lower-cased text itself; nothing is lower-cased in Java.
 */
public interface FeatureRepository extends JpaRepository<Feature, UUID> {

    Optional<Feature> findByIdAndProjectId(UUID id, UUID projectId);

    /**
     * The project's active features (D11).
     */
    List<Feature> findByProjectIdAndArchivedAtIsNull(UUID projectId);

    /**
     * The project's archived features (D11).
     */
    List<Feature> findByProjectIdAndArchivedAtIsNotNull(UUID projectId);

    /**
     * The project's active features whose current version uses the tag anywhere (D12).
     */
    @Query("SELECT DISTINCT f FROM Feature f, TagAssignment a WHERE a.featureId = f.id AND a.tagId = :tagId "
            + "AND f.projectId = :projectId AND f.archivedAt IS NULL")
    List<Feature> findActiveWithTag(@Param("projectId") UUID projectId, @Param("tagId") UUID tagId);

    /**
     * The project's archived features whose current version uses the tag anywhere (D12, D19 #4).
     */
    @Query("SELECT DISTINCT f FROM Feature f, TagAssignment a WHERE a.featureId = f.id AND a.tagId = :tagId "
            + "AND f.projectId = :projectId AND f.archivedAt IS NOT NULL")
    List<Feature> findArchivedWithTag(@Param("projectId") UUID projectId, @Param("tagId") UUID tagId);

    /**
     * Whether an active feature of the project has this path, ignoring case as the unique index does.
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM features
                WHERE project_id = :projectId AND archived_at IS NULL
                AND md5(lower(path COLLATE "und-x-icu")) = md5(lower(CAST(:path AS text) COLLATE "und-x-icu"))
                AND lower(path COLLATE "und-x-icu") = lower(CAST(:path AS text) COLLATE "und-x-icu"))
            """, nativeQuery = true)
    boolean isPathTaken(@Param("projectId") UUID projectId, @Param("path") String path);

    /**
     * As {@link #isPathTaken}, ignoring the feature itself (a save or restore).
     */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM features
                WHERE project_id = :projectId AND archived_at IS NULL AND id <> :featureId
                AND md5(lower(path COLLATE "und-x-icu")) = md5(lower(CAST(:path AS text) COLLATE "und-x-icu"))
                AND lower(path COLLATE "und-x-icu") = lower(CAST(:path AS text) COLLATE "und-x-icu"))
            """, nativeQuery = true)
    boolean isPathTakenByAnother(@Param("projectId") UUID projectId, @Param("path") String path,
                                 @Param("featureId") UUID featureId);

    /**
     * Serialises writes in one project until the current transaction ends (D14): a transaction-scoped advisory lock
     * keyed by the project id. The wait is bounded by the connection's {@code lock_timeout}. Call it as the first
     * statement of a write transaction (P13).
     */
    default void lockProject(UUID projectId) {
        advisoryLock(projectId.getMostSignificantBits() ^ projectId.getLeastSignificantBits());
    }

    @Query(value = "SELECT CAST(pg_advisory_xact_lock(:key) AS text)", nativeQuery = true)
    String advisoryLock(@Param("key") long key);
}
