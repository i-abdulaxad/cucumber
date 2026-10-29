package org.reportservice.enums;

/**
 * The status of a test case or step result (ERD {@code execution_status}, D8, D9): Cucumber's statuses in upper case.
 * {@code UNKNOWN} means not executed yet (a planned manual test case), or an uploaded test case without steps or hooks.
 */
public enum ExecutionStatus {
    PASSED,
    FAILED,
    SKIPPED,
    PENDING,
    UNDEFINED,
    AMBIGUOUS,
    UNKNOWN
}
