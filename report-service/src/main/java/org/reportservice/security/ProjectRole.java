package org.reportservice.security;

/**
 * Effective project roles as auth-service's access check (C5) reports them (D7), with explicit ranks. Roles are
 * compared only through {@link #isAtLeast(ProjectRole)}, never by declaration order (guardrail "one owner per
 * concern").
 */
public enum ProjectRole {
    ADMIN(4),
    MEMBER(3),
    TESTER(2),
    READER(1);

    private final int rank;

    ProjectRole(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean isAtLeast(ProjectRole required) {
        return rank >= required.rank;
    }
}
