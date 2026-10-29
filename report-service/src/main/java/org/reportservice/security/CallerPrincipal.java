package org.reportservice.security;

import java.util.UUID;

/**
 * The authenticated user on a user path (D5): the user id from the JWT's {@code sub}, and the raw token, which only
 * the access checker reads, to forward it to auth-service (C4). report-service has no user table; {@code email},
 * {@code orgs} and an {@code X-User-Id} header are never used. {@link #toString()} never shows the token.
 */
public record CallerPrincipal(UUID userId, String token) {

    @Override
    public String toString() {
        return "CallerPrincipal[userId=" + userId + ", token=<redacted>]";
    }
}
