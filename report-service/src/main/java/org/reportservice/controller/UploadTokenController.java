package org.reportservice.controller;

import jakarta.validation.Valid;
import org.reportservice.dto.request.CreateUploadTokenRequest;
import org.reportservice.dto.response.CreatedUploadTokenResponse;
import org.reportservice.dto.response.UploadTokenResponse;
import org.reportservice.security.CallerPrincipal;
import org.reportservice.service.UploadTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Upload tokens (US-1, contract C1 tag "Upload tokens"). */
@RestController
@RequestMapping("/api/projects/{projectId}/upload-tokens")
public class UploadTokenController {

    private final UploadTokenService uploadTokenService;

    public UploadTokenController(UploadTokenService uploadTokenService) {
        this.uploadTokenService = uploadTokenService;
    }

    @PostMapping
    public ResponseEntity<CreatedUploadTokenResponse> create(@AuthenticationPrincipal CallerPrincipal caller,
                                                             @PathVariable UUID projectId,
                                                             @Valid @RequestBody CreateUploadTokenRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(uploadTokenService.create(projectId, caller.userId(), request));
    }

    @GetMapping
    public ResponseEntity<List<UploadTokenResponse>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(uploadTokenService.list(projectId));
    }

    @PostMapping("/{tokenId}/revoke")
    public ResponseEntity<UploadTokenResponse> revoke(@AuthenticationPrincipal CallerPrincipal caller,
                                                      @PathVariable UUID projectId, @PathVariable UUID tokenId) {
        return ResponseEntity.ok(uploadTokenService.revoke(projectId, tokenId, caller.userId()));
    }
}
