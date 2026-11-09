package org.reportservice.dto.response;

import java.util.List;

/**
 * One page of a run's results (contract C1 {@code ResultPage}).
 */
public record ResultPageResponse(List<TestCaseResultResponse> items, int page, int size, long totalItems) {
}
