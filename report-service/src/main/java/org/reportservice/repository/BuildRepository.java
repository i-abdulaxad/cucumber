package org.reportservice.repository;

import org.reportservice.model.Build;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BuildRepository extends JpaRepository<Build, UUID> {

    /** The project's build with exactly this name (D11: case-sensitive). */
    Optional<Build> findByProjectIdAndName(UUID projectId, String name);
}
