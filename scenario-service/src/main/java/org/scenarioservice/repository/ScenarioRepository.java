package org.scenarioservice.repository;

import org.scenarioservice.model.Scenario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * The {@code Scenario} rows of the current versions' parsed structure.
 */
public interface ScenarioRepository extends JpaRepository<Scenario, UUID> {

    List<Scenario> findByFeatureId(UUID featureId);

    /**
     * Removes the feature's scenarios (steps, examples and rows cascade in the database; ERD, D14).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Scenario s WHERE s.featureId = :featureId")
    void deleteByFeature(@Param("featureId") UUID featureId);
}
