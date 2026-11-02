package org.reportservice.controller;

import org.reportservice.dto.request.CiUploadQuery;
import org.reportservice.dto.request.UploadBody;
import org.reportservice.dto.response.TestRunResponse;
import org.reportservice.security.UploadTokenPrincipal;
import org.reportservice.service.UploadService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URI;

/**
 * The CI upload (US-2, contract C1 {@code uploadCucumberJson}). Only {@code application/json} is mapped (otherwise
 * 415, D9). The body and its headers are passed on unread and unchecked, so the service checks charset, encoding and
 * length first and reads the body bounded; the query parameters are passed as received, so the service checks them
 * after the size limit (D13, BD-15).
 */
@RestController
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(path = "/api/ci/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TestRunResponse> upload(@AuthenticationPrincipal UploadTokenPrincipal uploader,
                                                  @RequestParam(required = false) String name,
                                                  @RequestParam(required = false) String environment,
                                                  @RequestParam(required = false) String build,
                                                  @RequestParam(required = false) String branch,
                                                  @RequestParam(required = false) String commit,
                                                  @RequestParam(required = false) String ciUrl,
                                                  @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
                                                  @RequestHeader(value = HttpHeaders.CONTENT_ENCODING,
                                                          required = false) String contentEncoding,
                                                  @RequestHeader(value = HttpHeaders.CONTENT_LENGTH,
                                                          required = false) Long contentLength,
                                                  InputStream body) {
        TestRunResponse run = uploadService.upload(uploader,
                new CiUploadQuery(name, environment, build, branch, commit, ciUrl),
                new UploadBody(contentType, contentEncoding, contentLength, body));
        return ResponseEntity.created(URI.create("/api/projects/" + run.projectId() + "/runs/" + run.id())).body(run);
    }
}
