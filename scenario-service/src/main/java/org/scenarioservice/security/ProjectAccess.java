package org.scenarioservice.security;

import org.scenarioservice.enums.ProjectRole;

import java.util.UUID;

/**
 * The caller's effective access to a project, as the access check answered it (D4).
 */
public record ProjectAccess(UUID projectId, ProjectRole role, boolean archived) {
}
