package org.reportservice.enums;

/** How a run came in (ERD {@code test_run_source}, D8): planned by people, or created by a CI upload. */
public enum TestRunSource {
    MANUAL,
    AUTOMATED
}
