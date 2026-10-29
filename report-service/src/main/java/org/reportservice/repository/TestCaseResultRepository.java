package org.reportservice.repository;

import org.reportservice.model.TestCaseResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TestCaseResultRepository extends JpaRepository<TestCaseResult, UUID> {
}
