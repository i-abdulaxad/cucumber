package org.scenarioservice.service;

import org.scenarioservice.dto.response.TagResponse;

import java.util.List;
import java.util.UUID;

/**
 * The project's tag catalogue (US-5, D12).
 */
public interface TagService {

    List<TagResponse> catalogue(UUID projectId);
}
