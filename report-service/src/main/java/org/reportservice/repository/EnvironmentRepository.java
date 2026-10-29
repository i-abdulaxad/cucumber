package org.reportservice.repository;

import org.reportservice.model.Environment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {
}
