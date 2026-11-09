package org.scenarioservice.repository;

import org.scenarioservice.model.Rule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * The {@code Rule} rows of the current versions' parsed structure.
 */
public interface RuleRepository extends JpaRepository<Rule, UUID> {

    List<Rule> findByFeatureId(UUID featureId);

    /**
     * Removes the feature's rules (their scenarios cascade); used when a save replaces the structure (D9).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Rule r WHERE r.featureId = :featureId")
    void deleteByFeature(@Param("featureId") UUID featureId);
}
