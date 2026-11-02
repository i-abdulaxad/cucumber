package org.reportservice.repository;

import org.reportservice.enums.ExecutionStatus;

/** One group of a run's results by feature and status (D12): count, summed durations and first position. */
public interface RunSummaryGroup {

    String getFeaturePath();

    String getFeatureName();

    ExecutionStatus getStatus();

    long getCount();

    Long getDurationMs();

    int getFirstPosition();
}
