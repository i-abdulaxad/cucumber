# Platform hardening: one C5 request, and Problems for requests rejected before Spring MVC
Status: Ready (user decisions of 2026-10-26: Q1–Q4 as recommended, E1–E13 approved, gateway FU-4 abort added)
Last updated: 2026-10-26

Binding rules: `docs/engineering/standards.md` (referenced as "standards §n"). This spec lists only feature-specific rules. It has **no named deviation** from the standards.

## Problem

Two leftovers from the accepted specs, chosen by the user on 2026-10-26 as the next work (before report-service):

1. **The C5 access check can be sent twice.** scenario-service's JDK `HttpClient` retries a GET once when auth-service closes the connection without answering (scenario-service BUG-4, SFU-1). Standards §3 says "no retries". It still fails closed in about 2 s, but doubles C5 load on a failure.
2. **Requests rejected before Spring MVC get no Problem.** In all three services, Spring Security firewall rejections (`;`, `//`, `/./`, encoded `..`), `TRACE` and a direct `GET /error` get Spring Boot's default JSON (`timestamp`, `status`, `error`, `path`) instead of the Problem format (auth-service FU-1 / phase-1 BUG-1; scenario-service SFU-2 / BUG-5; gateway FU-3 / BUG-9 and FU-1 / BUG-6). auth-service also answers **401** to a login and **Boot's 500** to authenticated requests when its connection pool is exhausted (FU-19 / persistence BUG-5).

**Goal:** one design, applied per service (copied, never shared, standards §3), that gives every rejection Spring code can still see a Problem, plus a clear, documented line for what stays out of reach.

## Scope

**In scope**
- scenario-service: SFU-1 (one C5 attempt) and SFU-2 (Problems before MVC).
- auth-service: FU-1 (Problems before MVC) and FU-19 (database connection unavailable → 503 Problem).
- api-gateway: FU-3 (Problem for the firewall's 400 and for 405) and the `/error` part of FU-1 (Q3).
- api-gateway: the abort part of FU-4 (BUG-14, S3): a downstream failure after the response is committed aborts the client connection (D11). Added by the user on 2026-10-26; must land before report-service.
- Contract updates and version bumps for the three services (T6, docs only).

**Out of scope**
- Rejections made by Tomcat before any Spring code runs (D6): accepted as they are and documented.
- The gateway's FU-1 health sub-paths (`/actuator/health/{liveness,readiness,ping}`): moved to FU-5 with the Helm probes (Q3).
- Lowering auth-service's pool `connection-timeout` (default 30 s) below the gateway's 10 s timeout, so the 503 reaches clients through the gateway instead of a 504: follow-up PH-FU-1.
- Mapping connection-pool exhaustion to 503 in scenario-service: follow-up PH-FU-2.
- FU-4's per-route response timeouts: they stay in gateway FU-4, specced with report-service uploads and downloads.
- Any other follow-up (gateway FU-2, FU-4 to FU-7; auth-service FU-5, FU-13 and later; SFU-3 to SFU-7). No ERD change (no stored data changes).

## Glossary

| Term | Meaning here |
|---|---|
| Error dispatch | Tomcat's internal forward to the error page (`/error`) after `sendError` or an exception escaping the filter chain. Spring code (filters registered for ERROR, the error controller, the advice) runs during it. |
| Container-level rejection | Tomcat rejects the request before the application's filters run and writes its own response (no error dispatch). |
| Connection unavailable | The service can't get a database connection: the pool gives none within its `connection-timeout`, or the database refuses the connection. |
| `{valid token}` | As in the gateway spec glossary: HS512, shared secret, `sub` = `7f1c2a9e-0000-4000-8000-000000000001` (ana@example.com). |
| `{P}` | Project `5b0c6a7e-0000-4000-8000-0000000000a1`, where ana is a project MEMBER. `{F}` is a feature `9d2e4f10-0000-4000-8000-0000000000f1` in it. |

## Code facts verified (HEAD `fc178b6` plus working tree, 2026-10-26)

- scenario-service: `AuthServiceClientConfiguration` builds a JDK `HttpClient` (HTTP/1.1, no redirects) inside `JdkClientHttpRequestFactory`; it is the only HTTP client in `src/main` (grep: `HttpClient|RestClient` → 3 files, all C5). Java 17 toolchain.
- qa's `QaAccessCheckFailureMatrixTest:195-198` skips the connection-count assertion only for the row "closes the connection at once" (BUG-4).
- auth-service and scenario-service: `SecurityConfig` permits the **path** `/error`; the gateway permits the **ERROR dispatch type**. No service has its own `ErrorController`; Boot's is used.
- Both services' advice already maps statuses to codes (`400 → MALFORMED_REQUEST`, `404 → NOT_FOUND`, `405 → METHOD_NOT_ALLOWED`, `406 → NOT_ACCEPTABLE`, other 4xx → `MALFORMED_REQUEST`, otherwise `INTERNAL_ERROR`) with fixed detail texts. The auth-service entry point already delegates to the `HandlerExceptionResolver` (precedent for D3).
- auth-service `JwtAuthenticationFilter` loads the token's user from the database; a failure there escapes the filter (Boot's 500). Login goes through the `AuthenticationManager`, which wraps a database failure as an `AuthenticationException` (hence 401). `ErrorCode` has no 503 code.
- Gateway `GatewayProblems` builds the D6 format (`type` `about:blank`, `title`, `status`, `detail`, `instance`, `requestId`; **no `code`**). qa's `QaUnknownPathsTest#theErrorPathIsAnUnroutedPath` is `@Disabled` for FU-1.
- MockMvc does not perform the error dispatch, and no service test runs a real server today (no `RANDOM_PORT` in auth- or scenario-service tests). Under MockMvc, `%00` and `%2F` are rejected by the firewall; on a real Tomcat they are container-level.
- Gateway BUG-14 (qa report, R9): `GatewayExceptionHandler.handleDownstreamFailure` still returns a Problem when `response.isCommitted()`. Tomcat commits after 8 KB; streaming media types are flushed per chunk. `AccessLogFilter` logs 500 whenever the chain throws, even if the client already got 200. No repo test covers the committed branch.
- Contracts: auth-service `1.3.0` (phase-1 deviation note at lines 88-94), scenario-service `1.0.4` (no note), api-gateway `0.2.3` (`Problem.status` deviation list at lines 267-275).

## Decisions (recommended defaults)

**D1. One spec, three services, two batches.** Batch A is auth-service (the reference implementation, standards header) and SFU-1; batch B copies the approved pattern to scenario-service and the gateway, then the contracts. *Reason:* the riskiest pieces (security matcher, login failure mapping, a possibly JVM-wide setting) get reviewed before they're copied.

**D2. SFU-1: one C5 attempt per access check.** Every access check sends at most one request and opens at most one connection, whatever the transport failure. Timeouts, fail-closed 503 `ACCESS_CHECK_UNAVAILABLE`, no cache and no redirects stay as they are. backend-swe verifies the mechanism in the JDK 17 sources actually used (candidates to verify: `jdk.httpclient.redirects.retrylimit`, `jdk.httpclient.disableRetryConnect`) and states it with evidence. Preference: (a) a setting on this client or a request factory already in spring-web that verifiably doesn't retry; (b) a JDK networking system property set by scenario-service at start-up and for the test JVM, only if Q1 = yes; (c) a new dependency: not approved, stop. *Reason:* standards §3 forbids C5 retries; a JVM-wide switch is acceptable only because C5 is this service's only HTTP client, and that needs the user's yes.

**D3. One funnel for everything that reaches the error dispatch.** Each service replaces Boot's error controller with its own: it only turns the error dispatch (status, and any escaped exception) into a typed exception, and the service's existing advice renders the Problem (standards §3: the advice is the only place exceptions become responses; no deviation). The firewall keeps Spring Security's default handling (400 via the error dispatch); no firewall rule is relaxed. auth-service is built first (T1) and copied to the others (copied, not shared). *Reason:* one place covers firewall 400s, `TRACE` 405s, any `sendError` and filter exceptions, with the existing status-to-code table.

**D4. A direct `/error` is an unrouted path in every service.** Only the ERROR dispatch type is permitted without a token (like the gateway). A direct `GET /error`: no or invalid token → 401 `UNAUTHENTICATED` with `WWW-Authenticate: Bearer`; valid token → 404 `NOT_FOUND` (gateway: its D6 404, "No API route matches this path."). Answered by Q2. *Reason:* same rule as every other unknown path, and the same rule the gateway's FU-1 already approved.

**D5. Body rules for D3 Problems.**
1. Each service keeps its own contract format: auth- and scenario-service with `code` (C3/D16), the gateway in D6 without `code`.
2. Status → code: the existing advice table (code facts), plus auth-service 503 → `SERVICE_UNAVAILABLE` (D7). The status is never changed.
3. Detail: the existing texts. The gateway adds `400` "The request URL was rejected." and `405` "This method is not supported for this path."
4. `instance` is the original request path (never `/error`); it's omitted when that path isn't a valid URI reference.
5. `requestId`: services as today (the request's valid `X-Request-Id`); gateway: equals the `X-Request-Id` response header when that header is present.
6. Never: request headers (so `TRACE` can't echo `Authorization`), exception messages, class names, stack traces, server version.

*Reason:* one format per contract, nothing new for clients to learn.

**D6. What each layer can reach.**

| Layer | Examples (verified by qa reports) | Outcome |
|---|---|---|
| Spring Security firewall | `;x=1`, `//`, `/./`, `/../`, `%2E%2E` | 400 Problem (D3) |
| Tomcat, then the error dispatch | `TRACE` 405; `sendError`; exceptions escaping filters; direct `/error` | Problem (D3, D4) |
| Spring MVC | gateway `POST /actuator/health` 405 | Problem through the advice (D5) |
| Tomcat connector, before the application | `%2F`, `%00`, raw `{` `\|`, `CONNECT` (501), malformed request line, oversized URI or headers | **Accepted, out of reach.** Status unchanged, Tomcat's minimal page, no `X-Request-Id`, never forwarded. |

Rejected alternatives for the last row: `encodedSolidusHandling=decode` turns `%2F` into `/` and weakens the path checks (bypass risk at the gateway); a custom Tomcat error valve duplicates the format outside Spring, can't carry `requestId`, and is container-specific. *Reason:* the same line auth-service Q7 and SFU-2 already accepted; now documented as permanent rather than "phase 1".

**D7. FU-19: connection unavailable → 503 Problem in auth-service.** New code `SERVICE_UNAVAILABLE` (503), detail "The service is temporarily unavailable. Try again later.", no `Retry-After`. It applies in the JWT filter, at login (never 401 `INVALID_CREDENTIALS` or `UNAUTHENTICATED`), and in any handler. Other database errors keep their mapping (lock timeouts, conflicts, 500). Logged once at WARN with the failure's class name, no stack trace, nothing from the request (standards §3). The service recovers by itself once a connection is free. *Reason:* clients must not read an outage as "log in again"; a stable code lets them retry.

**D8. Contracts (backend-swe, T6).** Additive, non-breaking (no client is built yet; frontend-swe informed through the handoff):
- auth-service `1.3.0 → 1.4.0`: `SERVICE_UNAVAILABLE` in the code list, 503 on login and on authenticated operations, the phase-1 note replaced by the D6 note.
- scenario-service `1.0.4 → 1.0.5`: the D6 note, and direct `/error` (D4).
- api-gateway `0.2.3 → 0.3.0`: 400 and 405 in `Problem.status`, the FU-3 and FU-1 `/error` deviations removed, the D6 note kept (with `TRACE` moved out of the container list); the BUG-14 committed-response deviation replaced by the D11 abort rule.

*Reason:* new status and code values are minor bumps; notes only are patch.

**D9. Verification on a real server.** The D3, D4 and D7 ACs are proven against a running server on a random port (MockMvc skips the error dispatch). At most one new Spring context per service for D3/D4, plus at most one for D7 (small pool); report the count (standards §5, ≤ 10). *Reason:* a MockMvc test would pass without the fix.

**D10. Backlog closure on acceptance.** Closes SFU-1 (BUG-4), SFU-2 (BUG-5), auth-service FU-1 (phase-1 BUG-1) and FU-19 (persistence BUG-5), gateway FU-3 (BUG-9), FU-1's `/error` part (BUG-6, partly) and FU-4's abort part (BUG-14). Gateway FU-1's health sub-paths move to FU-5 (Q3); FU-4 keeps only per-route timeouts.

**D11. Gateway FU-4: a failure after the response is committed aborts the connection.** Once any part of the response has reached the client (more than Tomcat's 8 KB buffer, or a streamed body), a downstream failure or response timeout:
1. writes nothing more: no Problem, no terminating chunk, and the client connection is closed abnormally, so the truncation is detectable (curl exit 18 or a chunked-encoding error);
2. is logged once at WARN (the existing "Downstream call failed" line, no stack trace), with no ERROR entry and no error dispatch;
3. gets an access entry with the status the client actually received (200 in the reproductions).

Uncommitted responses keep D6 exactly (502/504 Problem, AC-4.6, AC-4.7). *Reason:* an appended Problem makes a corrupted body read as complete (S3, response integrity); aborting is the only signal left once the status is sent.

## Stories and acceptance criteria

Each service's Problem assertions mean: `Content-Type: application/problem+json`, the D5 fields, and nothing from D5 item 6.

### US-1 (scenario-service, SFU-1): one access-check request
As a code owner, I want each access check to be exactly one request, so that an auth-service failure never doubles C5 load (standards §3).

```gherkin
@US-1 @AC-1.1 @P0
Scenario Outline: A transport failure is one attempt and fails closed
  Given auth-service's C5 port is a raw server that <behaviour>
  When ana sends GET /api/projects/{P}/features with {valid token}
  Then the response is 503 with code "ACCESS_CHECK_UNAVAILABLE" within the existing AC-7.3 bound
  And the raw server accepted exactly 1 connection and received at most 1 request
  Examples:
    | behaviour                                   |
    | closes the connection at once               |
    | answers garbage                             |
    | stalls after sending part of the head       |
    | resets the connection after reading the request |

@US-1 @AC-1.2 @P1
Scenario: A normal answer is still one request
  Given auth-service answers C5 with 200 and role "MEMBER"
  When ana sends GET /api/projects/{P}/features with {valid token}
  Then the response is 200 and auth-service received exactly 1 C5 request
```

### US-2 (auth-service, FU-1): Problems before Spring MVC
As a frontend developer, I want every rejection to be a Problem with `code`, so that I parse one error format.

```gherkin
@US-2 @AC-2.1 @P0
Scenario Outline: Firewall rejections are 400 Problems, with or without a token
  When a client sends <method> <path> with <auth> and X-Request-Id "ph-auth-1"
  Then the response is 400 with code "MALFORMED_REQUEST", detail "The request could not be read.", requestId "ph-auth-1"
  And instance is the path as sent, not "/error"
  And no user or organization was created
  Examples:
    | method | path                             | auth          |
    | POST   | /api/auth/register;x=1           | no token      |
    | POST   | /api/auth/./register             | no token      |
    | GET    | /api/orgs//x                     | {valid token} |
    | GET    | /api/orgs/%2E%2E/orgs            | {valid token} |

@US-2 @AC-2.2 @P0
Scenario: TRACE is a 405 Problem that echoes nothing
  When a client sends TRACE /api/orgs with "Authorization: Bearer qa-trace-marker"
  Then the response is 405 with code "METHOD_NOT_ALLOWED"
  And the body does not contain "qa-trace-marker"

@US-2 @AC-2.3 @P0
Scenario Outline: A direct /error is an unrouted path
  When a client sends GET /error with <auth>
  Then the response is <status> with code "<code>"
  Examples:
    | auth            | status | code            |
    | no token        | 401    | UNAUTHENTICATED |
    | an invalid token| 401    | UNAUTHENTICATED |
    | {valid token}   | 404    | NOT_FOUND       |

@US-2 @AC-2.4 @P1
Scenario Outline: Container-level rejections stay as accepted (D6)
  When a client sends GET <path>
  Then the response is 400, never 5xx, with no stack trace and no server version in the body
  Examples:
    | path                              |
    | /api/auth/invitations/a%2Fb/accept |
    | /api/orgs/%00                     |

@US-2 @AC-2.5 @P1
Scenario: Existing Problems are unchanged
  When a client sends GET /api/orgs with no token
  Then the response is 401 with code "UNAUTHENTICATED" and instance "/api/orgs", as before
```

### US-3 (auth-service, FU-19): database connection unavailable
As a client, I want an outage to be a 503 Problem, so that I don't send users to the login page.

```gherkin
Background:
  Given auth-service runs with a pool of 2 connections and a connection-timeout of 500 ms
  And both connections are held

@US-3 @AC-3.1 @P0
Scenario Outline: Connection unavailable is a 503 Problem, never 401 or Boot's body
  When a client sends <request> with X-Request-Id "ph-db-1"
  Then within 2 s the response is 503 with code "SERVICE_UNAVAILABLE"
  And detail "The service is temporarily unavailable. Try again later." and requestId "ph-db-1"
  And there is no Retry-After header
  Examples:
    | request                                                            |
    | GET /api/orgs with {valid token}                                   |
    | POST /api/auth/login with ana@example.com and her correct password |
    | POST /api/auth/login with ana@example.com and a wrong password     |
    | POST /api/auth/register with zed@example.com                       |
    | GET /internal/projects/{P}/access with {valid token}               |

@US-3 @AC-3.2 @P1
Scenario: It is logged once, without detail
  When a client sends GET /api/orgs with {valid token}
  Then exactly one WARN line from the error mapping names the failure class
  And the logging aspect's existing "Slow call" WARN for that call is allowed (pm, 2026-10-26)
  And the log contains no stack trace, token, email or password for that request

@US-3 @AC-3.3 @P1
Scenario: The service recovers by itself
  Given both connections were held and are now released
  When a client sends POST /api/auth/login with ana@example.com and a wrong password
  Then the response is 401 with code "INVALID_CREDENTIALS"
```

### US-4 (scenario-service, SFU-2): Problems before Spring MVC
Same story as US-2, for scenario-service. No C5 call happens for any of these requests.

```gherkin
@US-4 @AC-4.1 @P0
Scenario Outline: Firewall rejections are 400 Problems before any access check
  When ana sends GET <path> with <auth> and X-Request-Id "ph-sc-1"
  Then the response is 400 with code "MALFORMED_REQUEST" and requestId "ph-sc-1"
  And instance is the path as sent, not "/error"
  And auth-service received no C5 request
  Examples:
    | path                                     | auth          |
    | /api/projects/{P}/features;x=1           | {valid token} |
    | /api/projects/{P}//features              | {valid token} |
    | /api/projects/{P}/features/%2E%2E/{F}    | {valid token} |
    | /api/projects/{P}/features;x=1           | no token      |

@US-4 @AC-4.2 @P0
Scenario: TRACE is a 405 Problem that echoes nothing
  When a client sends TRACE /api/projects/{P}/features with "Authorization: Bearer qa-trace-marker"
  Then the response is 405 with code "METHOD_NOT_ALLOWED", the body does not contain "qa-trace-marker"
  And auth-service received no C5 request

@US-4 @AC-4.3 @P0
Scenario Outline: A direct /error is an unrouted path
  When a client sends GET /error with <auth>
  Then the response is <status> with code "<code>" and auth-service received no C5 request
  Examples:
    | auth             | status | code            |
    | no token         | 401    | UNAUTHENTICATED |
    | an invalid token | 401    | UNAUTHENTICATED |
    | {valid token}    | 404    | NOT_FOUND       |

@US-4 @AC-4.4 @P1
Scenario Outline: Container-level rejections stay as accepted (D6)
  When ana sends GET <path> with {valid token}
  Then the response is 400, never 5xx, with no stack trace and no server version, and no C5 request
  Examples:
    | path                                   |
    | /api/projects/{P}/features/a%2Fb       |
    | /api/projects/{P}%00/features          |
```

### US-5 (api-gateway, FU-3 and FU-1 `/error`): gateway-level rejections
As a frontend developer, I want the gateway's own rejections in the D6 format, so that the edge has one error shape.

```gherkin
@US-5 @AC-5.1 @P0
Scenario Outline: Firewall rejections are 400 D6 Problems
  When a client sends <method> <path> with <auth>
  Then the response is 400 application/problem+json with type "about:blank", title "Bad Request"
  And detail "The request URL was rejected." and requestId equal to the X-Request-Id header
  And no downstream stub received a request, and there is exactly one access log entry
  Examples:
    | method | path                                             | auth          |
    | POST   | /api/auth/./login                                | no token      |
    | GET    | /api/orgs//x                                     | {valid token} |
    | POST   | /api;x=1/auth/invitations/inv-QaSemi4Secret/accept | no token    |

@US-5 @AC-5.2 @P0
Scenario: A method a gateway endpoint doesn't support is a 405 D6 Problem
  When a client sends POST /actuator/health with {valid token}
  Then the response is 405 with type "about:blank", detail "This method is not supported for this path."
  And requestId equals the X-Request-Id header

@US-5 @AC-5.3 @P1
Scenario: TRACE is a 405 Problem that echoes nothing
  When a client sends TRACE /api/orgs with "Authorization: Bearer qa-trace-marker"
  Then the response is 405 application/problem+json with type "about:blank"
  And the body does not contain "qa-trace-marker", and no downstream stub received a request

@US-5 @AC-5.4 @P0
Scenario Outline: A direct /error is an unrouted path (FU-1)
  When a client sends GET /error with <auth>
  Then the response is <status> Problem with detail "<detail>"
  Examples:
    | auth          | status | detail                            |
    | no token      | 401    | A valid bearer token is required. |
    | {valid token} | 404    | No API route matches this path.   |

@US-5 @AC-5.5 @P1
Scenario Outline: Container-level rejections stay as accepted (D6)
  When a client sends <method> <path> with {valid token}
  Then the status is <status>, never forwarded to any downstream stub
  Examples:
    | method  | path                           | status |
    | POST    | /api/auth/login%2F..%2Forgs    | 400    |
    | CONNECT | /api/orgs                      | 501    |
```

### US-6 (api-gateway, FU-4 abort): committed responses are aborted, never completed
As a client downloading a large or streamed body, I want a failed download to look failed, so that I never keep a corrupted body. Downstream is a raw socket stub; the gateway's response timeout is 2 s. These rows are BUG-14's reproductions (qa report, BUG-api-gateway-14). qa writes them first, in the same change set as the fix (gateway D10).

```gherkin
@US-6 @AC-6.1 @P0
Scenario Outline: A downstream failure after commit aborts the client connection
  Given the stub answers GET /api/scenarios/<path> with "HTTP/1.1 200 OK", Content-Type "<type>", and <body>
  When ana sends GET /api/scenarios/<path> with {valid token}
  Then the client receives status 200 and then an abnormal end of the connection
  And the received body does not contain "Bad Gateway", "\"status\":502" or "\"status\":500"
  Examples:
    | path            | type              | body                                                                  |
    | big-cut-chunked | application/json  | 25 chunks of 4096 bytes, then closes without the terminating chunk     |
    | big-cut-length  | application/json  | Content-Length 200000, 100,000 bytes, then closes                      |
    | slow-stream     | text/event-stream | 20 bytes, then nothing for longer than the 2 s response timeout        |

@US-6 @AC-6.2 @P1
Scenario Outline: It is logged once, at WARN, with the status the client got
  When the <path> row of AC-6.1 runs
  Then exactly one WARN "Downstream call failed" line is logged, without a stack trace
  And no ERROR entry and no "Exception Processing" entry is logged
  And the access entry for that requestId says status=200
  Examples:
    | path            |
    | big-cut-chunked |
    | big-cut-length  |
    | slow-stream     |

@US-6 @AC-6.3 @P0
Scenario: An uncommitted failure is still the D6 502 Problem
  Given the stub answers with Content-Length 20000, sends 8,000 bytes, then closes
  When ana sends the request with {valid token}
  Then the response is the 502 Problem of AC-4.7, unchanged
```

## E-list: changes to existing code (approved by the user on 2026-10-26; E5, E7, E8 only under their conditions)

| E | Service | File (under `src/main/java/...`) | Change | Reason | Risk | Depends | Task |
|---|---|---|---|---|---|---|---|
| E1 | auth | `configuration/SecurityConfig.java` | Permit the ERROR dispatch type instead of the path `/error` | D4 | M (security) | Q2 | T1 |
| E2 | auth | `exception/ApiExceptionHandler.java` | Render the error controller's typed exception with the existing status table; `instance` per D5 | D3, D5 | L | — | T1 |
| E3 | auth | `exception/ErrorCode.java` | Add `SERVICE_UNAVAILABLE(503)` | D7 | L | — | T2 |
| E4 | auth | `exception/ApiExceptionHandler.java` | Connection unavailable → 503 `SERVICE_UNAVAILABLE`, including an authentication failure caused by it; one WARN line | D7 | M (login path) | E3 | T2 |
| E5 | auth | `configuration/JwtAuthenticationFilter.java` | A connection-unavailable failure while loading the token's user ends in the D7 Problem. **Only if** the T1 error route can't produce it; backend-swe states which | D7 | M | E4 | T2 |
| E6 | scenario | `configuration/AuthServiceClientConfiguration.java` | One attempt per C5 request (D2 option a or b) | D2 | L | — | T3 |
| E7 | scenario | `ScenarioServiceApplication.java` | Set the verified JDK property before the context starts. **Only if** Q1 = yes and option (a) doesn't exist | D2 | M (JVM-wide) | Q1 | T3 |
| E8 | scenario | `build.gradle` (test task) | The same property for the test JVM. Same condition as E7 | D2 | L | E7 | T3 |
| E9 | scenario | `configuration/SecurityConfig.java` | As E1 | D4 | M (security) | Q2, RP-A | T4 |
| E10 | scenario | `exception/ApiExceptionHandler.java` | As E2 | D3, D5 | L | RP-A | T4 |
| E11 | gateway | `exception/GatewayExceptionHandler.java` | Render the error controller's typed exception and MVC's 405 in D6 (`type`, `requestId`) | D3, D5 | M (edge) | RP-A | T5 |
| E12 | gateway | `exception/GatewayProblems.java` | The two D5 detail texts | D5 | L | — | T5 |
| E13 | gateway | `exception/GatewayExceptionHandler.java` (committed branch of the downstream-failure handling); `filter/AccessLogFilter.java` (status recorded when the response was already committed) | D11: abort instead of appending; log status as received. Approved by the user 2026-10-26 | D11 | M (edge, response integrity) | T5 (same file) | T7 |

**Will not be touched:** controllers' handlers, services, repositories, entities, migrations and the ERD; auth-service's C5 endpoint and `ProblemAuthenticationEntryPoint`; scenario-service's `AccessCheckClient`, `ProjectAccessChecker` and C5 mapping; scenario-service and gateway JWT filters; gateway routes, CORS, the request-id filter, the access log's format and masking, `SecurityConfiguration`, `DownstreamResponseFailureConfiguration`; any `application*.yaml`; every `.../qa/**` file (qa's, standards §2); report-service; any `.env`.

## Tasks

Common to every task: standards §2, §5, §6, §7 apply. New tests are not under `qa/`. Report the context count.

### Batch A: auth-service reference and SFU-1 (risky first)

**T1. auth-service FU-1: error controller (E1, E2)**
- **Allowed files:** E1, E2; new `controller/ProblemErrorController.java` and at most one new typed exception in `exception/`; new test classes under `auth-service/src/test/java/com/example/authservice/` (outside `qa/`).
- **Must not:** relax or customise the firewall; change Tomcat settings; change any existing Problem; touch the entry point or access-denied handler.
- **ACs:** AC-2.1 to AC-2.5.
- **Test first (real server, D9):** AC-2.1 rows → red: Boot JSON, no `code`. AC-2.2 → red: Boot JSON. AC-2.3 no-token row → red: 500 Boot body (today permitted). AC-2.4, AC-2.5 → green before and after (guards).
- **Done when:** all rows green; full `clean build` green; context count reported.
- **Stop if:** an ERROR dispatch of an anonymous request turns into 401; an existing test changes outcome; the original path isn't available during the error dispatch.

**T2. auth-service FU-19: 503 on connection unavailable (E3, E4, E5)**
- **Allowed files:** E3, E4, E5 (only under E5's condition); new test classes as in T1.
- **Must not:** change pool settings in `application*.yaml`; map any other database error to 503; catch broadly (`Exception`) in the filter; log arguments or the exception's message.
- **ACs:** AC-3.1 to AC-3.3.
- **Test first:** AC-3.1 rows → red: 500 Boot body (`GET /api/orgs`), 401 (login rows). AC-3.3 → green guard.
- **Done when:** all rows green 3 times in a row (pool timing); full `clean build` green.
- **Stop if:** the failure can't be told apart from other database errors without matching exception messages; a wrong password at login ever becomes 503 when connections are free.

**T3. scenario-service SFU-1: one C5 attempt (E6; E7, E8 only if option (a) doesn't exist; Q1 = yes, 2026-10-26)**
- **Allowed files:** E6; E7 and E8 only when the handoff shows option (a) doesn't exist; new test classes under `scenario-service/src/test/java/org/scenarioservice/` (outside `qa/`).
- **Must not:** add a dependency; change timeouts, redirects, HTTP version or the C5 mapping; set any property not verified in the JDK 17 sources.
- **ACs:** AC-1.1, AC-1.2.
- **Test first:** AC-1.1 "closes the connection at once" → red: 2 connections. Other rows and AC-1.2 → green guards.
- **Done when:** green; the handoff names the mechanism and its evidence (JDK source file and line).
- **Stop if:** only option (c) works; option (b) needs more than one property or any file beyond E7, E8; the property also changes other HTTP client behaviour (redirects, timeouts).

**Review point A.** The main session checks T1–T3 against the ACs; the user approves the auth-service pattern before it's copied.

**Review point A result: passed, accepted by the user 2026-10-26.** Builds: auth-service 2043 tests, scenario-service 869 tests, 0 failed.
- **E5 applied:** `JwtAuthenticationFilter` catches only `CannotCreateTransactionException` and hands it to the `handlerExceptionResolver` (otherwise Tomcat logs ERROR with a stack trace first).
- **SFU-1, option (b) accepted by the user:** the JDK 17 `MultiExchange` has no per-client switch, so `jdk.httpclient.redirects.retrylimit=1` is set JVM-wide at start-up (E7) and for the test JVM (E8). **Constraint:** any future HTTP client in scenario-service must not rely on following redirects, or must use a dedicated mechanism (also in standards §3).
- **AC-3.2 (pm, 2026-10-26):** the aspect's "Slow call" WARN at login and register when the pool times out is acceptable. It is the aspect's standard outcome line, not a second failure log, and it carries no request data. AC-3.2 is reworded accordingly.
- New follow-up PH-FU-3 and refactoring candidates RC-1 and RC-2 (below).

### Batch B: copy the pattern, gateway FU-4 abort, then contracts (order: T4, T5, T7, T6)

**T4. scenario-service SFU-2 (E9, E10)**
- **Allowed files:** E9, E10; new `controller/ProblemErrorController.java` and at most one typed exception in `exception/`; new test classes as in T3.
- **Must not:** as T1; never call C5 during an error dispatch.
- **ACs:** AC-4.1 to AC-4.4.
- **Test first (real server):** AC-4.1, AC-4.2 → red: Boot JSON. AC-4.3 no-token row → red. AC-4.4 → green guard.
- **Done when / stop if:** as T1.

**T5. api-gateway FU-3 and FU-1 `/error` (E11, E12)**
- **Allowed files:** E11, E12; at most one new error controller in `org/apigateway/exception/` or a new `controller/` package; new test classes under `api-gateway/src/test/java/org/apigateway/` (outside `qa/`).
- **Must not:** add `code` to the D6 format; change routing, CORS, `SecurityConfiguration` or the access log; touch the health sub-paths (FU-5); enable qa's `@Disabled` tests (qa does).
- **ACs:** AC-5.1 to AC-5.5.
- **Test first:** AC-5.1 → red: Boot JSON. AC-5.2 → red: no `type` or `requestId` (re-check first; record if already green). AC-5.3 → red: Boot JSON. AC-5.4 valid-token row → red: 500. AC-5.5 → green guard.
- **Done when / stop if:** as T1; also stop if any downstream failure Problem (D6 502/503/504) changes.

**T7. api-gateway FU-4 abort (E13; after T5, same file)**
- **Step 0 (qa):** qa writes AC-6.1 to AC-6.3 under `api-gateway/src/test/java/org/apigateway/qa/` and shows them red (AC-6.3 green), in the same change set as the fix; never committed red or disabled (gateway D10).
- **Allowed files:** E13's two files; new test classes under `api-gateway/src/test/java/org/apigateway/` (outside `qa/`), including any new stub class they need. Existing `support/*` files stay unchanged (no E-item for them).
- **Must not:** change D6 for uncommitted responses; add retries or buffering of whole bodies; add per-route timeouts; change the access log format or masking; touch `DownstreamResponseFailureConfiguration`.
- **ACs:** AC-6.1 to AC-6.3; regression AC-4.6, AC-4.7 and the T5 ACs.
- **Test first:** chunked row → red: Problem appended, clean end. Length and stream rows → red: access `status=500` and ERROR entries. AC-6.3 → green guard.
- **Done when:** green 3 times in a row (timing); full `clean build` green.
- **Stop if:** aborting needs a file outside E13; a committed response can't be aborted without Tomcat- or gateway-library internals (report the options); any uncommitted 502/504 changes.

**T6. Contracts (docs, backend-swe; D8; after T4, T5, T7)**
- **Allowed files:** `docs/api/auth-service.openapi.yaml`, `docs/api/scenario-service.openapi.yaml`, `docs/api/api-gateway.openapi.yaml`.
- **Must not:** touch code, config, tests or `docs/specs/`.
- **Task:** the D8 edits and version bumps, describing behaviour as built in T1–T5 and T7.
- **Done when:** each file parses as OpenAPI 3.1; versions `1.4.0`, `1.0.5`, `0.3.0`.

**Review point B.** Then qa verifies every AC, restores the `QaAccessCheckFailureMatrixTest` connection-count assertion, re-enables `QaUnknownPathsTest#theErrorPathIsAnUnroutedPath`, re-points `#healthSubPathsAre404Problems` to FU-5 (Q3), and updates its own tests that pinned the old behaviour (with the reason).

## Follow-ups created here

| ID | Priority | Item | Owner |
|---|---|---|---|
| PH-FU-1 | P2, with production config / Helm | auth-service pool `connection-timeout` below the gateway's 10 s, so D7's 503 reaches clients through the gateway (today: 504 after 10 s) | pm, backend-swe |
| PH-FU-2 | P2 | scenario-service: connection unavailable → 503 Problem (copy D7) | pm |
| PH-FU-3 | P2 | auth-service has 12 cached test contexts against the standards §5 limit of 10 (pre-existing); bring it to ≤ 10 | backend-swe, qa |

**Refactoring candidates (not approved; recorded only):** RC-1: the unused `userDetailsService` field in auth-service `SecurityConfig`. RC-2: `localhost` test servers can collide with local listeners on the same port; pick ports collision-safe.

## Resolved questions (user, 2026-10-26, relayed by the main session)

- **Q1** SFU-1 fallback (JVM-wide JDK property, E7, E8): yes, as recommended.
- **Q2** Direct `/error`: 401 without a token, 404 with one (E1, E9): yes.
- **Q3** Gateway FU-1: `/error` now; health sub-paths move to FU-5: yes.
- **Q4** E1–E12 approved with their conditions; **E13** (FU-4 abort) added and approved.
- **E13 scope** (confirmed 2026-10-26): covers both `GatewayExceptionHandler.java` and `AccessLogFilter.java`.
- **FU-4 scope** (confirmed 2026-10-26): abort part only; per-route timeouts stay in gateway FU-4 for report-service.

## Changelog
- 2026-10-26: created by pm (Draft).
- 2026-10-26: review point A passed (E5, E7, E8 applied; AC-3.2 reworded; PH-FU-3, RC-1, RC-2; standards §3 note).
- 2026-10-26: user decisions recorded; FU-4 abort added (D11, US-6, E13, T7); T3 Allowed files and stop conditions updated for Q1; Status Ready.
