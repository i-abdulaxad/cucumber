package org.reportservice.repository;

import org.reportservice.model.Environment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {

    /** The project's environment with exactly this name (D11: case-sensitive). */
    Optional<Environment> findByProjectIdAndName(UUID projectId, String name);
}
