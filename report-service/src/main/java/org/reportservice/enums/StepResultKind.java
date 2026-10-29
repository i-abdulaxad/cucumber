package org.reportservice.enums;

/** What a step result row is (ERD {@code step_result_kind}, D9): a step, or a hook around the test case or a step. */
public enum StepResultKind {
    STEP,
    BEFORE_HOOK,
    AFTER_HOOK
}
