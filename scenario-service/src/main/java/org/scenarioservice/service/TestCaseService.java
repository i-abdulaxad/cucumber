package org.scenarioservice.service;

import org.scenarioservice.dto.response.TestCaseResponse;

import java.util.List;
import java.util.UUID;

/**
 * Test cases of a feature's current version (US-4, D13), derived on read and never stored.
 */
public interface TestCaseService {

    List<TestCaseResponse> testCases(UUID projectId, UUID featureId);
}
