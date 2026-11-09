package org.scenarioservice.repository;

import org.scenarioservice.model.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The tag catalogue; lookups by name compare {@code md5(name)} (so the unique index is used) and the name itself.
 */
public interface TagRepository extends JpaRepository<Tag, UUID> {

    @Query(value = "SELECT * FROM tags WHERE project_id = :projectId AND md5(name) = md5(CAST(:name AS text)) "
            + "AND name = :name", nativeQuery = true)
    Optional<Tag> findByProjectIdAndName(@Param("projectId") UUID projectId, @Param("name") String name);

    /**
     * Every tag row of the project, in one query (BUG-6): a write maps tag names to rows in memory, comparing names
     * exactly as {@code uk_tags_project_id_name} does, instead of one lookup per name.
     */
    List<Tag> findByProjectId(UUID projectId);

    /**
     * The catalogue (D12): every tag used in an active feature's current version, with the number of such features,
     * by name in code-point order (the database's C.UTF-8 collation).
     */
    @Query("SELECT new org.scenarioservice.repository.TagUsage(t.name, COUNT(DISTINCT a.featureId)) FROM Tag t "
            + "JOIN TagAssignment a ON a.tagId = t.id JOIN Feature f ON f.id = a.featureId "
            + "WHERE t.projectId = :projectId AND f.archivedAt IS NULL GROUP BY t.id, t.name ORDER BY t.name")
    List<TagUsage> findUsage(@Param("projectId") UUID projectId);
}
