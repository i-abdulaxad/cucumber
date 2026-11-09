package org.scenarioservice.controller;

import jakarta.validation.Valid;
import org.scenarioservice.configuration.ArchivedFilter;
import org.scenarioservice.configuration.VersionNumber;
import org.scenarioservice.dto.request.CreateFeatureRequest;
import org.scenarioservice.dto.request.SaveFeatureRequest;
import org.scenarioservice.dto.response.FeatureResponse;
import org.scenarioservice.dto.response.FeatureSummaryResponse;
import org.scenarioservice.dto.response.FeatureVersionResponse;
import org.scenarioservice.dto.response.FeatureVersionSummaryResponse;
import org.scenarioservice.security.CallerPrincipal;
import org.scenarioservice.service.FeatureService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Feature endpoints (contract C1, D2). HTTP only: ids, {@code {version}} and {@code archived} are bound by the strict
 * editors (D16), bodies are validated, and each handler makes exactly one service call (PL).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/features")
public class FeatureController {

    private final FeatureService featureService;

    public FeatureController(FeatureService featureService) {
        this.featureService = featureService;
    }

    /**
     * {@code query} carries the raw {@code tag} values, so a repeated or comma-containing tag isn't rewritten.
     */
    @GetMapping
    public ResponseEntity<List<FeatureSummaryResponse>> list(@PathVariable UUID projectId,
                                                             @RequestParam(required = false) ArchivedFilter archived,
                                                             @RequestParam MultiValueMap<String, String> query) {
        return ResponseEntity.ok(featureService.list(projectId, archived != null && archived.value(),
                query.get("tag")));
    }

    @PostMapping
    public ResponseEntity<FeatureResponse> create(@AuthenticationPrincipal CallerPrincipal caller,
                                                  @PathVariable UUID projectId,
                                                  @Valid @RequestBody CreateFeatureRequest request) {
        FeatureResponse feature = featureService.create(projectId, caller.userId(), request);
        return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/features/" + feature.id()))
                .body(feature);
    }

    @GetMapping("/{featureId}")
    public ResponseEntity<FeatureResponse> get(@PathVariable UUID projectId, @PathVariable UUID featureId) {
        return ResponseEntity.ok(featureService.get(projectId, featureId));
    }

    @PutMapping("/{featureId}")
    public ResponseEntity<FeatureResponse> save(@AuthenticationPrincipal CallerPrincipal caller,
                                                @PathVariable UUID projectId, @PathVariable UUID featureId,
                                                @Valid @RequestBody SaveFeatureRequest request) {
        return ResponseEntity.ok(featureService.save(projectId, featureId, caller.userId(), request));
    }

    @PostMapping("/{featureId}/archive")
    public ResponseEntity<FeatureResponse> archive(@AuthenticationPrincipal CallerPrincipal caller,
                                                   @PathVariable UUID projectId, @PathVariable UUID featureId) {
        return ResponseEntity.ok(featureService.archive(projectId, featureId, caller.userId()));
    }

    @PostMapping("/{featureId}/restore")
    public ResponseEntity<FeatureResponse> restore(@AuthenticationPrincipal CallerPrincipal caller,
                                                   @PathVariable UUID projectId, @PathVariable UUID featureId) {
        return ResponseEntity.ok(featureService.restore(projectId, featureId, caller.userId()));
    }

    @GetMapping("/{featureId}/versions")
    public ResponseEntity<List<FeatureVersionSummaryResponse>> versions(@PathVariable UUID projectId,
                                                                        @PathVariable UUID featureId) {
        return ResponseEntity.ok(featureService.versions(projectId, featureId));
    }

    @GetMapping("/{featureId}/versions/{version}")
    public ResponseEntity<FeatureVersionResponse> version(@PathVariable UUID projectId, @PathVariable UUID featureId,
                                                          @PathVariable VersionNumber version) {
        return ResponseEntity.ok(featureService.version(projectId, featureId, version.value()));
    }
}
