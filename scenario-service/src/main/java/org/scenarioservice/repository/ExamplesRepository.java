package org.scenarioservice.repository;

import org.scenarioservice.model.Examples;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The {@code Examples} rows of the current versions' parsed structure.
 */
public interface ExamplesRepository extends JpaRepository<Examples, UUID> {

    List<Examples> findByScenarioIdIn(Collection<UUID> scenarioIds);
}
