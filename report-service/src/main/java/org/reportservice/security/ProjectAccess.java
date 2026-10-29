package org.reportservice.security;

import java.util.UUID;

/** The caller's effective access to a project, as the access check answered it (contract C4). */
public record ProjectAccess(UUID projectId, ProjectRole role, boolean archived) {
}
