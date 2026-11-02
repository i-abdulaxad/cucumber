package org.reportservice.repository;

import org.reportservice.model.StepResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Step results (D9, D10). */
public interface StepResultRepository extends JpaRepository<StepResult, UUID> {

    /** A result's steps and hooks in execution order (AC-2.2). */
    List<StepResult> findByTestCaseResultIdOrderByPosition(UUID testCaseResultId);
}
