package org.reportservice.repository;

import org.reportservice.enums.ExecutionStatus;

import java.util.UUID;

/**
 * How many results of a run have a status (D12, the run list).
 */
public interface RunStatusCount {

    UUID getRunId();

    ExecutionStatus getStatus();

    long getCount();
}
