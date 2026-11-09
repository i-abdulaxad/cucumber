package org.reportservice.service;

import org.reportservice.dto.response.ResultPageResponse;
import org.reportservice.dto.response.RunPageResponse;
import org.reportservice.dto.response.TestCaseResultDetailResponse;
import org.reportservice.dto.response.TestRunResponse;

import java.util.UUID;

/**
 * Reading runs and results (US-5, D12): {@code READER} or higher, also on an archived project.
 */
public interface RunQueryService {

    RunPageResponse runs(UUID projectId, Integer page, Integer size);

    TestRunResponse run(UUID projectId, UUID runId);

    ResultPageResponse results(UUID projectId, UUID runId, String status, Integer page, Integer size);

    TestCaseResultDetailResponse result(UUID projectId, UUID runId, UUID resultId);
}
