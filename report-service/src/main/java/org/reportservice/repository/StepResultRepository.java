package org.reportservice.repository;

import org.reportservice.model.StepResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StepResultRepository extends JpaRepository<StepResult, UUID> {
}
