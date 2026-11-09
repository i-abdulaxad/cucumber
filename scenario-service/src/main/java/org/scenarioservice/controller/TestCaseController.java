package org.scenarioservice.controller;

import org.scenarioservice.dto.response.TestCaseResponse;
import org.scenarioservice.service.TestCaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/projects/{projectId}/features/{featureId}/test-cases} (contract C1, US-4). HTTP only (PL).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/features/{featureId}/test-cases")
public class TestCaseController {

    private final TestCaseService testCaseService;

    public TestCaseController(TestCaseService testCaseService) {
        this.testCaseService = testCaseService;
    }

    @GetMapping
    public ResponseEntity<List<TestCaseResponse>> testCases(@PathVariable UUID projectId,
                                                            @PathVariable UUID featureId) {
        return ResponseEntity.ok(testCaseService.testCases(projectId, featureId));
    }
}
