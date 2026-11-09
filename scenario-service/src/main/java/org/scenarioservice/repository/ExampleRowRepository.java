package org.scenarioservice.repository;

import org.scenarioservice.model.ExampleRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The {@code ExampleRow} rows of the current versions' parsed structure.
 */
public interface ExampleRowRepository extends JpaRepository<ExampleRow, UUID> {

    List<ExampleRow> findByExamplesIdIn(Collection<UUID> examplesIds);
}
