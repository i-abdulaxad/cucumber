package org.reportservice.service;

import org.reportservice.dto.request.RecordResultRequest;
import org.reportservice.dto.response.TestCaseResultResponse;
import org.reportservice.dto.response.TestRunResponse;

import java.util.UUID;

/**
 * Recording and the manual run lifecycle (US-4, D8).
 */
public interface RunRecordingService {

    TestCaseResultResponse record(UUID projectId, UUID runId, UUID resultId, UUID userId, RecordResultRequest request);

    TestRunResponse complete(UUID projectId, UUID runId);

    TestRunResponse cancel(UUID projectId, UUID runId);
}
