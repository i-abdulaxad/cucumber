package org.reportservice.security;

import org.reportservice.exception.AccessCheckUnavailableException;
import org.reportservice.exception.ForbiddenException;
import org.reportservice.exception.ProjectArchivedException;
import org.reportservice.exception.UnauthenticatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The access step of every project-scoped use case (D5, D7, contract C4): "require read" (a minimum role; allowed on
 * an archived project) and "require write" (a minimum role, then the project must not be archived). It reads the
 * caller from the security context, asks C5 once (no cache, no retry), and maps the answer:
 * <ul>
 *     <li>200 with exactly the requested {@code projectId}, a known role and a boolean {@code archived}: the access,
 *     compared by rank;</li>
 *     <li>403: {@link ForbiddenException}; 401: {@link UnauthenticatedException};</li>
 *     <li>anything else (no connection, a timeout, a 5xx or other status, an unreadable body, an unknown role, another
 *     project): {@link AccessCheckUnavailableException}, the service fails closed, with one WARN line naming the
 *     failure type only (no token, URL or stack trace).</li>
 * </ul>
 * A write on an archived project is {@link ProjectArchivedException}, after the role check (D13). Services call it
 * before opening a transaction, so no database connection is held during the call. It's the only caller of C5, and
 * it isn't a {@code @Service}, so the logging aspect never sees the token.
 */
@Component
public class ProjectAccessChecker {

    private static final Logger log = LoggerFactory.getLogger(ProjectAccessChecker.class);
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String REQUEST_ID_MDC_KEY = "requestId";

    private final AccessCheckClient client;
    private final JsonMapper jsonMapper;

    public ProjectAccessChecker(AccessCheckClient client, JsonMapper jsonMapper) {
        this.client = client;
        this.jsonMapper = jsonMapper;
    }

    /** The caller's access, at least {@code minimum}; an archived project is allowed (reads, token list, revoke). */
    public ProjectAccess requireRead(UUID projectId, ProjectRole minimum) {
        return check(projectId, minimum, false);
    }

    /** The caller's access, at least {@code minimum}, on a project that isn't archived (D7). */
    public ProjectAccess requireWrite(UUID projectId, ProjectRole minimum) {
        return check(projectId, minimum, true);
    }

    private ProjectAccess check(UUID projectId, ProjectRole minimum, boolean write) {
        ProjectAccess access = fetch(projectId, currentCaller());
        if (!access.role().isAtLeast(minimum)) {
            throw new ForbiddenException();
        }
        if (write && access.archived()) {
            throw new ProjectArchivedException();
        }
        return access;
    }

    private static CallerPrincipal currentCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CallerPrincipal caller) {
            return caller;
        }
        throw new UnauthenticatedException();
    }

    private ProjectAccess fetch(UUID projectId, CallerPrincipal caller) {
        AccessCheckClient.Answer answer;
        try {
            answer = client.fetch(projectId, caller.token(), MDC.get(REQUEST_ID_MDC_KEY));
        } catch (ResourceAccessException ex) {
            throw unavailable(isTimeout(ex) ? "timeout" : "connection failure");
        } catch (RestClientException ex) {
            throw unavailable(ex.getClass().getSimpleName());
        }
        return switch (answer.status()) {
            case 200 -> access(projectId, answer.body());
            case 401 -> throw new UnauthenticatedException();
            case 403 -> throw new ForbiddenException();
            default -> throw unavailable("status " + answer.status());
        };
    }

    private ProjectAccess access(UUID projectId, String body) {
        JsonNode answer;
        try {
            answer = body == null ? null : jsonMapper.readTree(body);
        } catch (JacksonException ex) {
            throw unavailable("unreadable body");
        }
        if (answer == null || !answer.isObject()) {
            throw unavailable("unreadable body");
        }
        JsonNode answeredProject = answer.get("projectId");
        if (answeredProject == null || !answeredProject.isString()
                || !CANONICAL_UUID.matcher(answeredProject.asString()).matches()
                || !UUID.fromString(answeredProject.asString()).equals(projectId)) {
            throw unavailable("another projectId");
        }
        JsonNode role = answer.get("role");
        if (role == null || !role.isString() || Arrays.stream(ProjectRole.values())
                .noneMatch(known -> known.name().equals(role.asString()))) {
            throw unavailable("unknown role");
        }
        JsonNode archived = answer.get("archived");
        if (archived == null || !archived.isBoolean()) {
            throw unavailable("no archived flag");
        }
        return new ProjectAccess(projectId, ProjectRole.valueOf(role.asString()), archived.asBoolean());
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static AccessCheckUnavailableException unavailable(String reason) {
        log.warn("Access check unavailable: {}", reason);
        return new AccessCheckUnavailableException();
    }
}
