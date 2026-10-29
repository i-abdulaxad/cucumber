package org.reportservice.service;

import jakarta.validation.constraints.NotBlank;
import org.reportservice.security.ProjectAccess;
import org.reportservice.security.ProjectAccessChecker;
import org.reportservice.security.ProjectRole;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * TEST-ONLY use case behind {@code controller/ProbeController} (T3): batch A has no real endpoint yet, so the security
 * chain, the access check, strict input, the advice and the logging aspect are exercised through it. It is a
 * {@code @Service} in {@code service}, so the logging aspect advises it like a real use case.
 */
@Service
public class ProbeService {

    private final ProjectAccessChecker accessChecker;

    public ProbeService(ProjectAccessChecker accessChecker) {
        this.accessChecker = accessChecker;
    }

    /** "Require read": the access check with a minimum role, allowed on an archived project. */
    public ProjectAccess read(UUID projectId, ProjectRole minimum) {
        return accessChecker.requireRead(projectId, minimum);
    }

    /** "Require write": the access check with a minimum role, then 409 on an archived project. */
    public ProjectAccess write(UUID projectId, ProbeBody body) {
        return accessChecker.requireWrite(projectId, ProjectRole.MEMBER);
    }

    /** Fails the way the named failure would reach the advice from a real use case. */
    public void fail(String kind) {
        switch (kind) {
            case "unique" -> throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"uk_builds_project_id_name\" for secret-QA");
            case "lock" -> throw new CannotAcquireLockException("canceling statement due to lock timeout QA");
            default -> throw new IllegalStateException("unexpected probe failure with secret-QA");
        }
    }

    /** A request body with one string, for strict JSON (D13). */
    public record ProbeBody(@NotBlank String name) {
    }
}
