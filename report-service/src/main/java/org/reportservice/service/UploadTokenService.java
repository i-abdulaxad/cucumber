package org.reportservice.service;

import org.reportservice.dto.request.CreateUploadTokenRequest;
import org.reportservice.dto.response.CreatedUploadTokenResponse;
import org.reportservice.dto.response.UploadTokenResponse;

import java.util.List;
import java.util.UUID;

/** Upload token management (US-1, D4): project ADMIN only. */
public interface UploadTokenService {

    CreatedUploadTokenResponse create(UUID projectId, UUID userId, CreateUploadTokenRequest request);

    List<UploadTokenResponse> list(UUID projectId);

    UploadTokenResponse revoke(UUID projectId, UUID tokenId, UUID userId);
}
