package org.scenarioservice.repository;

import org.scenarioservice.model.FeatureVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Versions are inserted only; never updated or deleted (D9).
 */
public interface FeatureVersionRepository extends JpaRepository<FeatureVersion, UUID> {

    Optional<FeatureVersion> findByFeatureIdAndVersion(UUID featureId, int version);

    /**
     * A feature's history, newest first (D9).
     */
    List<FeatureVersion> findByFeatureIdOrderByVersionDesc(UUID featureId);

    /**
     * The current version of each of these features.
     */
    @Query("SELECT v FROM FeatureVersion v, Feature f WHERE f.id IN :featureIds AND v.featureId = f.id "
            + "AND v.version = f.currentVersion")
    List<FeatureVersion> findCurrentVersions(@Param("featureIds") Collection<UUID> featureIds);
}
