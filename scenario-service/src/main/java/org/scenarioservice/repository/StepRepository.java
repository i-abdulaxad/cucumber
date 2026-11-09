package org.scenarioservice.repository;

import org.scenarioservice.model.Step;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The {@code Step} rows of the current versions' parsed structure.
 */
public interface StepRepository extends JpaRepository<Step, UUID> {

    List<Step> findByScenarioIdIn(Collection<UUID> scenarioIds);
}
