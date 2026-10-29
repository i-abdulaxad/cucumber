package com.example.authservice.controller;

import com.example.authservice.dto.response.ProjectStatusResponse;
import com.example.authservice.service.ProjectStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * C7 (report-service spec D5): a project's status for another service, authenticated by {@code X-Service-Key} in its
 * own security chain ({@code SecurityConfig}).
 */
@RestController
@RequestMapping("/internal/service/projects")
@RequiredArgsConstructor
@Tag(name = "Project status for services")
public class InternalProjectStatusController {

    private final ProjectStatusService projectStatusService;

    @GetMapping("/{projectId}")
    @Operation(description = "A project's organization and archived state, for other services (X-Service-Key)")
    public ResponseEntity<ProjectStatusResponse> projectStatus(@PathVariable UUID projectId) {
        return ResponseEntity.ok(projectStatusService.projectStatus(projectId));
    }
}
