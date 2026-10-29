package org.reportservice.controller;

import jakarta.validation.Valid;
import org.reportservice.security.ProjectAccess;
import org.reportservice.security.ProjectRole;
import org.reportservice.service.ProbeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * TEST-ONLY controller (T3; see {@code service/ProbeService}): a project-scoped user path with the real security
 * chain, binder advice and advice, standing in for the run endpoints of T9 and T10.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/probe")
class ProbeController {

    private final ProbeService probeService;

    ProbeController(ProbeService probeService) {
        this.probeService = probeService;
    }

    @GetMapping
    ResponseEntity<ProjectAccess> read(@PathVariable UUID projectId) {
        return ResponseEntity.ok(probeService.read(projectId, ProjectRole.READER));
    }

    @GetMapping("/{runId}")
    ResponseEntity<ProjectAccess> readOne(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return ResponseEntity.ok(probeService.read(projectId, ProjectRole.READER));
    }

    @PostMapping
    ResponseEntity<ProjectAccess> write(@PathVariable UUID projectId, @Valid @RequestBody ProbeService.ProbeBody body) {
        return ResponseEntity.ok(probeService.write(projectId, body));
    }

    @GetMapping("/failures/{kind}")
    ResponseEntity<Void> fail(@PathVariable UUID projectId, @PathVariable String kind) {
        probeService.fail(kind);
        return ResponseEntity.noContent().build();
    }
}
