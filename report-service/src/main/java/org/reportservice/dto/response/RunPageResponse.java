package org.reportservice.dto.response;

import java.util.List;

/**
 * One page of runs (contract C1 {@code RunPage}).
 */
public record RunPageResponse(List<TestRunListItemResponse> items, int page, int size, long totalItems) {
}
