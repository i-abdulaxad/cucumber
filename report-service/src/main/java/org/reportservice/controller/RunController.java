package org.reportservice.controller;

import org.reportservice.dto.request.PlanRunRequest;
import org.reportservice.dto.response.TestRunResponse;
import org.reportservice.security.CallerPrincipal;
import org.reportservice.service.RunPlanningService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** Runs of a project (US-3 to US-5, contract C1 tags "Runs" and "Results"). */
@RestController
@RequestMapping("/api/projects/{projectId}/runs")
public class RunController {

    private final RunPlanningService planningService;

    public RunController(RunPlanningService planningService) {
        this.planningService = planningService;
    }

    @PostMapping
    public ResponseEntity<TestRunResponse> plan(@AuthenticationPrincipal CallerPrincipal caller,
                                                @PathVariable UUID projectId, @RequestBody PlanRunRequest request) {
        TestRunResponse run = planningService.plan(projectId, caller, request);
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/runs/" + run.id())).body(run);
    }
}
