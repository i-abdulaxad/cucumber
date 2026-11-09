package org.scenarioservice.repository;

import org.scenarioservice.model.TagAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * The {@code TagAssignment} rows of the current versions' parsed structure.
 */
public interface TagAssignmentRepository extends JpaRepository<TagAssignment, UUID> {

    /**
     * Removes the feature's tag assignments; used when a save replaces the structure (D12).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM TagAssignment a WHERE a.featureId = :featureId")
    void deleteByFeature(@Param("featureId") UUID featureId);
}
