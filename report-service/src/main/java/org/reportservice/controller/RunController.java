package org.reportservice.controller;

import org.reportservice.dto.request.PlanRunRequest;
import org.reportservice.dto.request.RecordResultRequest;
import org.reportservice.dto.response.ResultPageResponse;
import org.reportservice.dto.response.RunPageResponse;
import org.reportservice.dto.response.TestCaseResultDetailResponse;
import org.reportservice.dto.response.TestCaseResultResponse;
import org.reportservice.dto.response.TestRunResponse;
import org.reportservice.security.CallerPrincipal;
import org.reportservice.service.RunPlanningService;
import org.reportservice.service.RunQueryService;
import org.reportservice.service.RunRecordingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Runs of a project (US-3 to US-5, contract C1 tags "Runs" and "Results").
 */
@RestController
@RequestMapping("/api/projects/{projectId}/runs")
public class RunController {

    private final RunPlanningService planningService;
    private final RunRecordingService recordingService;
    private final RunQueryService queryService;

    public RunController(RunPlanningService planningService, RunRecordingService recordingService,
                         RunQueryService queryService) {
        this.planningService = planningService;
        this.recordingService = recordingService;
        this.queryService = queryService;
    }

    @GetMapping
    public ResponseEntity<RunPageResponse> runs(@PathVariable UUID projectId,
                                                @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(queryService.runs(projectId, page, size));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<TestRunResponse> run(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return ResponseEntity.ok(queryService.run(projectId, runId));
    }

    @GetMapping("/{runId}/results")
    public ResponseEntity<ResultPageResponse> results(@PathVariable UUID projectId, @PathVariable UUID runId,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(queryService.results(projectId, runId, status, page, size));
    }

    @GetMapping("/{runId}/results/{resultId}")
    public ResponseEntity<TestCaseResultDetailResponse> result(@PathVariable UUID projectId,
                                                               @PathVariable UUID runId,
                                                               @PathVariable UUID resultId) {
        return ResponseEntity.ok(queryService.result(projectId, runId, resultId));
    }

    @PostMapping
    public ResponseEntity<TestRunResponse> plan(@AuthenticationPrincipal CallerPrincipal caller,
                                                @PathVariable UUID projectId, @RequestBody PlanRunRequest request) {
        TestRunResponse run = planningService.plan(projectId, caller, request);
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/runs/" + run.id())).body(run);
    }

    @PutMapping("/{runId}/results/{resultId}")
    public ResponseEntity<TestCaseResultResponse> record(@AuthenticationPrincipal CallerPrincipal caller,
                                                         @PathVariable UUID projectId, @PathVariable UUID runId,
                                                         @PathVariable UUID resultId,
                                                         @RequestBody RecordResultRequest request) {
        return ResponseEntity.ok(recordingService.record(projectId, runId, resultId, caller.userId(), request));
    }

    @PostMapping("/{runId}/complete")
    public ResponseEntity<TestRunResponse> complete(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return ResponseEntity.ok(recordingService.complete(projectId, runId));
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<TestRunResponse> cancel(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return ResponseEntity.ok(recordingService.cancel(projectId, runId));
    }
}
