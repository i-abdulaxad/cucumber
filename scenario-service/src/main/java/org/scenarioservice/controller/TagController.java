package org.scenarioservice.controller;

import org.scenarioservice.dto.response.TagResponse;
import org.scenarioservice.service.TagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/projects/{projectId}/tags} (contract C1, US-5). HTTP only (PL).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public ResponseEntity<List<TagResponse>> catalogue(@PathVariable UUID projectId) {
        return ResponseEntity.ok(tagService.catalogue(projectId));
    }
}
