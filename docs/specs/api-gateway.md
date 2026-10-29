# API gateway: single entry point (phase 1)
Status: Accepted (with follow-ups FU-1 to FU-7, 2026-10-12)
Last updated: 2026-10-13 (docs only: auth-service FU-9 and the D2 routing note, from `docs/specs/auth-service-projects.md` X2)

## Problem and goal

The platform is split into services (`auth-service`, `scenario-service`, `report-service`), but clients have no single entry point. At aa2234c:

- `api-gateway` doesn't build. `api-gateway/build.gradle` declares both `spring-cloud-starter-gateway-server-webmvc` and `spring-cloud-starter-gateway`, and the second one has no version in the Spring Cloud 2025.1.2 BOM. `api-gateway/src/main/java/org/router/GatewayRouter.java` is an empty bean with missing imports and no return statement. No routes exist.
- The main class `org.ApiGatewayApplication` sits in the root package `org`, so `@SpringBootApplication` component-scans every `org.*` class on the classpath. Spring Boot warns about this package by name.
- No service sets `server.port`, so they all try to use 8080 and can't run side by side.
- Only auth-service checks JWTs (`auth-service/.../configuration/JwtAuthenticationFilter.java`).

**Goal:** a working gateway that the future frontend and the other services can build on. It provides:
- one base URL for every API call;
- tokens checked once at the edge, so unauthenticated traffic never reaches a service;
- predictable errors for unknown routes and for services that are down or slow;
- browser access (CORS), request tracing, and a health check.

**Success:** using only `http://localhost:8080`, a client can register, log in, and call a protected auth-service endpoint, and every P0 acceptance criterion passes.

## Users and roles affected

| Persona | What they need from the gateway |
|---|---|
| Frontend developer (frontend not built yet, stack TBD) | One base URL, one "log in again" signal (401), one error shape, CORS |
| Service developer (scenario-service and report-service later) | Known path prefixes, request ID and caller identity headers, no CORS or token plumbing to build per service |
| Operator / developer running the stack | Ports, service URLs and the secret set per environment, a health check, access logs |

**Platform roles.** No role changes, and no new roles. The gateway authenticates (is the token valid?) but doesn't authorize. Organization roles (`OWNER`, `ADMIN`, `MEMBER`, `READER`) and project roles (`ADMIN`, `MEMBER`, `TESTER`, `READER`) are still checked by the owning service. So the acceptance criteria compare anonymous, valid-token and invalid-token callers, not roles.

## Glossary

| Term | Meaning in this spec |
|---|---|
| Gateway | The `api-gateway` service, the single entry point for clients. |
| Downstream service | A service the gateway forwards requests to: auth-service, scenario-service or report-service. |
| Route | A mapping from an external path prefix to one downstream service's base URL (see D2). |
| Public operation | A method + path pair the gateway forwards without a token (see D4). Every other path is protected. |
| Shared secret | The value of `JWT_SECRET`: the standard Base64 encoding (RFC 4648 §4, alphabet `A–Z a–z 0–9 + /`) of at least 64 random bytes. auth-service and the gateway use the same value. |
| Valid token | A JWS signed with HS512 using the shared secret, with `exp` in the future and a `sub` of 1 or more printable ASCII characters, U+0021–U+007E (D4). In scenarios, `{valid token}` means such a token with `sub` = `7f1c2a9e-0000-4000-8000-000000000001`, `email` = `ana@example.com`, and `exp` 1 hour after issue. |
| Gateway-generated error | A response the gateway produces itself (401, 404, 502, 503, 504, 500), as opposed to a response passed through from a downstream service. |
| Stub | An in-process HTTP server that stands in for a downstream service in tests. **Unless a scenario says otherwise, every downstream service is a stub that answers any request with status 200 and body `{}`.** Paths used against stubs don't have to exist in the real service; they only prove forwarding. |
| Nothing listening at the URL | The configured base URL points at a local port with no server, so the connection is refused. |
| Within N seconds | Measured from when the client sends the request to when it receives the status line. |
| Replay | Sending a request to a service again after the service may already have received it. Retrying a connection that was never established is not a replay. |

## Scope

### In scope
- Make `api-gateway` build and start: dependency cleanup, servlet gateway, proper base package (D1).
- A config-driven routing table: auth-service now, provisional placeholders for scenario-service and report-service (D2).
- Static service URLs per environment, and a local port plan for all four services (D3).
- JWT validation at the edge, public operations, identity header propagation with anti-spoofing, and shared-secret handling (D4).
- CORS for a future browser frontend (D5).
- Edge error behaviour: 401, 404, 502, 503, 504, timeouts, and a Problem Details error body (D6).
- Request ID propagation, access logging, and an actuator health endpoint (D7).
- Automated tests against stubbed downstream services (D8).
- An edge contract at `docs/api/api-gateway.openapi.yaml` (D9).
- Minimal cross-service config change in auth-service: port 8081, and the JWT secret read from `JWT_SECRET` (T3).
- The fix round after QA (T13–T19, approved by the user on 2026-10-12) for the phase-1 bugs triaged under Acceptance, with the decisions in D10 and D11.

### Out of scope
- Rate limiting and throttling.
- Retries, circuit breakers and bulkheads. The gateway must **not** replay timed-out requests or requests whose method isn't GET or HEAD (AC-4.3, AC-4.8). The only replay allowed is D6's single safe-method replay.
- Service discovery (Eureka, Consul) and client-side load balancing.
- Helm and Kubernetes wiring (the `cucumber/` chart stays a scaffold), Dockerfiles, docker-compose, CI.
- TLS termination and HTTP→HTTPS redirects. We assume an ingress or load balancer handles these later.
- Authorization at the gateway (org or project role checks). That stays in the services.
- Token issuance, refresh, logout, revocation and key rotation; OAuth2/OIDC/SSO; Okta; WebAuthn/passkeys; MFA enforcement.
- Asymmetric token signing (RS256/EdDSA with JWKS). HS512 with a shared secret is kept for now (see NFR risk).
- API versioning (`/api/v1/...`) and path rewriting.
- OpenAPI aggregation or a Swagger UI at the gateway.
- Request and response body size limits, compression, caching, WebSockets/SSE/streaming.
- A platform-wide error format in downstream services (follow-up; see D6).
- Fixing known auth-service defects other than the T3 config change (see "Known issues in other services").
- Structured JSON logging, distributed tracing (OpenTelemetry), metrics endpoints.
- Network isolation or mTLS between the gateway and services. Trusted-header authentication follows from that.
- Downstream services adopting `X-Request-Id` and `X-User-Id` in their own logs (recommended, not required here).
- Routes for project resources (`/api/projects/**`) and nested cross-service paths.
- Trusted-proxy and client-IP handling (`X-Forwarded-*`). Until that's decided, services should emit relative `Location` URLs: the gateway replaces `Host`, so absolute URLs built from the request would expose internal hosts (qa risk, 2026-10-12).
- Requests the servlet container rejects before any gateway code runs (D6, TF-6).

### Follow-ups (deferred on 2026-10-12)

FU-1 to FU-6 each need their own spec, or a phase-2 section of this one, before work starts. FU-7 is fully specified here and can be done at any time. Disabled reproduction tests (D10) are re-enabled as the follow-up's acceptance tests. For bugs found in re-verification (BUG-14, BUG-16), qa adds the reproduction tests as the first step of the follow-up's fix, in the same change set as the fix, so no test is committed red or disabled (D10).

| ID | Priority and gate | Follow-up | Source | Target behaviour | Owner | Tests |
|---|---|---|---|---|---|---|
| FU-1 | P2 | Unrouted gateway-internal paths | BUG-6, TF-5 | With a valid token, `GET /error` and `/actuator/health/{liveness,readiness,ping}` answer the D6 404 Problem with `requestId`, like any other unrouted path. Without a token they keep answering 401. | pm (spec), backend-swe, qa | Disabled (D10): `QaUnknownPathsTest#healthSubPathsAre404Problems`, `#theErrorPathIsAnUnroutedPath` |
| FU-2 | P2. Do it before any service relies on signed URLs or exact query bytes, and before a non-Spring service is routed. | Byte-exact forwarding | BUG-8, TF-1 | The D2 phase-1 deviations are removed. <ul><li>The raw query string is forwarded byte for byte: order, encoding and valueless parameters.</li><li>The request `Content-Type` is unchanged.</li><li>POST form bodies are forwarded byte for byte.</li><li>Content coding is passed through: no added `Accept-Encoding`, no transparent decompression.</li><li>No h2c upgrade headers are sent downstream.</li></ul> | pm (spec), backend-swe, qa | Disabled (D10): `QaRequestFidelityTest#theRawQueryStringIsForwardedUnchanged`, `#theContentTypeHeaderIsForwardedUnchanged` |
| FU-3 | P2 | One error format for gateway-level rejections | BUG-9, TF-6 | The firewall's 400 (non-canonical paths) and 405 (a method a gateway endpoint doesn't support, for example `POST /actuator/health`) use the D6 Problem Details body, with `type` and `requestId`. | pm (spec), backend-swe, qa | None yet |
| FU-4 | **P1. Must land before any routed service can return a body over 8 KB or a streaming response, and at the latest before report-service downloads or uploads.** | Large and streamed bodies | backend-swe Q3, qa gap, BUG-14 (S3), TF-12 | <ul><li>Per-route response timeouts, for example for report uploads and downloads.</li><li>If a service fails after the gateway has started sending the response, the gateway aborts the client connection. It never appends a body and never terminates a chunked stream cleanly, so a truncated body can't be read as complete.</li><li>The failure is logged once, at WARN, with no stack trace.</li><li>The access entry records the status the client actually received.</li><li>Acceptance tests include BUG-14's three reproductions (qa report, BUG-api-gateway-14): a chunked body cut after 100 KB, a `Content-Length` body cut after 100,000 bytes, and a `text/event-stream` body slower than the timeout.</li></ul> | pm (spec), backend-swe, qa | qa writes BUG-14's reproductions in the FU-4 change set |
| FU-5 | P2. Specced with the Helm wiring. | Kubernetes probes | backend-swe Q2 | Liveness and readiness groups. | pm (spec), backend-swe | None |
| FU-6 | P2 | Headers of gateway errors after the downstream headers arrived | BUG-16 (S4), TF-13, qa test weaknesses 5 and 8 | <ul><li>New AC-4.9, P2, written in the follow-up's spec. A 502 or 504 that happens after the downstream headers arrived carries no header from the failed response, and each of the gateway's own headers appears exactly once. That includes multi-valued headers such as the three `Vary` values. It needs rows for 502 (body cut short) and 504 (body slower than the timeout).</li><li>backend-swe fixes BUG-16, so header names are restored once each.</li><li>backend-swe's `support/RawSocketStub` sends `Connection: close` with each reply.</li><li>qa tags `QaDownstreamResponseFailureTest#theProblemAfterHeadersCarriesOnlyTheGatewaysHeaders` `AC-4.9`, and adds a 504 row and a BUG-16 reproduction.</li></ul> | pm (AC-4.9), backend-swe, qa | qa writes the BUG-16 reproduction in the FU-6 change set |
| FU-7 | P2, docs only, no spec needed. Can be done now. | Documentation catch-up | TF-13, TF-14, BUG-14 | <ul><li>`api-gateway/README.md`: the startup-failure check names both markers, `Application run failed` and `APPLICATION FAILED TO START` (TF-14).</li><li>`docs/api/api-gateway.openapi.yaml`: the `BadGateway` and `GatewayTimeout` responses state the D6 rule that a gateway error carries only the gateway's own headers, never one from the failed downstream response. The phase-1 deviations add the committed-response behaviour (BUG-14: a large or streamed response can end with the Problem JSON appended to a 200, until FU-4). Bump `info.version` to `0.2.1`.</li></ul> **Allowed files:** those two files. **Must not:** touch code, config or tests. | backend-swe | None (docs) |

## Decisions (recommended defaults)

Each decision has a recommended default and a one-line reason. The user approved D1 and the changes to existing code listed under it on 2026-10-11 (item 6 on 2026-10-12). D2–D11 apply as written unless the user overrides them. Amendments made after QA on 2026-10-12 are marked "(2026-10-12)" and summarised in D11.

### D1. Servlet gateway and dependency cleanup (approved by the user on 2026-10-11)

**Status: approved as listed by the user on 2026-10-11.**

**Default:** a servlet gateway (`spring-cloud-starter-gateway-server-webmvc`).
**Why:** it uses the same blocking servlet stack and Spring Security model as auth-service and scenario-service (`spring-boot-starter-webmvc`), and current traffic doesn't justify a reactive stack.

Changes to `api-gateway/build.gradle`:

| Change | Dependency | Reason |
|---|---|---|
| Remove | `spring-cloud-starter-gateway` | No version in the 2025.1.2 BOM. It's the reactive flavour and conflicts with the servlet starter. |
| Remove | `spring-cloud-starter-loadbalancer` | Only useful with discovery (`lb://` URIs). We use static URLs (D3). |
| Remove | `spring-boot-starter-security-oauth2-client` | The gateway doesn't do OAuth2/OIDC login. auth-service issues tokens. |
| Remove | `com.okta.spring:okta-spring-boot-starter:3.1.0` | There's no Okta tenant, and it pulls in OAuth2 client configuration. |
| Remove | `spring-security-webauthn` | Passkeys, if ever added, belong in auth-service, not at the edge. |
| Remove | `spring-boot-starter-webservices`, `spring-boot-starter-webservices-test` | SOAP. Nothing uses it. |
| Remove | `com.h2database:h2` | The gateway has no database. |
| Fix | `lombok`: change `implementation` to `compileOnly` + `annotationProcessor` (and the test equivalents) | As declared, annotations aren't processed. This matches auth-service. |
| Add | `spring-boot-starter-actuator` (Boot-managed) | Health endpoint (US-8). |
| Add | `spring-boot-starter-webmvc-test` (Boot-managed, `testImplementation`) | Replaces the test support that came with `webservices-test`: `spring-boot-starter-test` plus MockMvc. |
| Keep | `spring-cloud-starter-gateway-server-webmvc`, `spring-boot-starter-security`, `spring-security-test`, `jjwt-api/impl/jackson` 0.12.5, `spring-boot-devtools`, `junit-platform-launcher` | — |

No new third-party libraries are added. JWTs are validated with jjwt 0.12.5, which is already declared. Spring's OAuth2 resource server would work too, but it's a new starter and isn't needed.

**Changes to existing code (approved by the user on 2026-10-11).** Apart from the `build.gradle` changes in the table above, these are the only changes allowed to files that existed at aa2234c:
1. Delete `api-gateway/src/main/java/org/router/GatewayRouter.java`. It uses the reactive `RouteLocator` API, and routes move to configuration (D2).
2. Move `ApiGatewayApplication` from package `org` to `org.apigateway`. That matches the existing test package (`api-gateway/src/test/java/org/apigateway/`) and the `org.scenarioservice` / `org.reportservice` convention.
3. Replace the gateway's `api-gateway/src/main/resources/application.properties` with `application.yaml`.
4. T3: in auth-service, change only `application.yaml` and `application-dev.yaml`: port 8081, the JWT secret read from `JWT_SECRET`, and a dev-only secret in the `dev` profile. No Java changes.
5. T6: in `api-gateway/src/test/java/org/apigateway/ApiGatewayApplicationTests.java`, add a test-only secret through `@SpringBootTest(properties = …)`. The test method body stays unchanged.
6. **Approved by the user on 2026-10-12 (BUG-1, T13).** In `api-gateway/src/main/resources/application.yaml`, delete `spring.profiles.active: dev` (the `profiles:` key and its `active:` line). The user added it to run the gateway from IntelliJ IDEA. For local runs, the `dev` profile is set outside the file instead: "Active profiles: dev" in the IntelliJ IDEA Spring Boot run configuration, `SPRING_PROFILES_ACTIVE=dev`, or `--spring.profiles.active=dev`. The file was created in T1, so it didn't exist at aa2234c. The item is recorded here because it reverses a change the user made.

**Version risk (not a decision yet).** Spring Cloud 2025.1.x was released for Spring Boot 4.0.x, and the gateway uses Boot 4.1.0. If dependency resolution or Spring Cloud's startup compatibility check rejects that pair, backend-swe stops at T1 and reports it. The recommended fix then is to move to the Spring Cloud train that supports Boot 4.1, which keeps all services on the same Boot version. Don't downgrade Boot, and don't disable the compatibility check.

### D2. Routing table in configuration, with no path rewriting

**Default:** routes are defined in the gateway's YAML configuration, not in the Java DSL.
**Why:** per-environment URLs and new routes are config changes, and the existing Java DSL stub targets the reactive API anyway.

Rules:
- The external path equals the downstream path. There's no prefix stripping or rewriting, because auth-service already serves `/api/auth` and `/api/orgs`, and clients and service contracts share one set of paths.
- Each prefix maps to exactly one service, and prefixes must not overlap.
- Placeholder prefixes are provisional. Each service's first spec decides its final prefixes. No client uses the placeholders, so renaming them isn't a breaking change.
- Nested cross-service paths (for example `/api/projects/{id}/features` when `Project` lives in auth-service) need an explicit decision when scenario-service is specced.
- `/api/projects/**` isn't routed in phase 1 because no endpoints exist yet.
- **Routing note (2026-10-13, auth-service-projects D2 option B, approved by the user):**
  - User-facing project operations live under `/api/orgs/{orgId}/projects/**`, which the existing `orgs` route already covers. No gateway change.
  - `/api/projects/**` stays unrouted and is **reserved for project-scoped content services** (for example `/api/projects/{projectId}/features/**` for scenario-service, `/api/projects/{projectId}/runs/**` for report-service). Each of those specs adds its own non-overlapping route.
  - `/internal/**` is **never routed**. It holds service-to-service operations such as auth-service's project access check `GET /internal/projects/{projectId}/access`, which services call directly at auth-service's base URL.

| Route | External path prefix | Target service | Base URL setting | Public operations (no token) | Status |
|---|---|---|---|---|---|
| auth | `/api/auth/**` | auth-service | `AUTH_SERVICE_URL` | `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/invitations/{token}/accept` | Active |
| orgs | `/api/orgs` and `/api/orgs/**` | auth-service | `AUTH_SERVICE_URL` | none | Active |
| scenarios | `/api/projects/*/features`, `/api/projects/*/features/**`, `/api/projects/*/tags`; and `/api/scenarios/**` (placeholder kept for existing gateway tests) | scenario-service | `SCENARIO_SERVICE_URL` | none | Active once scenario-service T13 lands (GE1, approved 2026-10-14, `docs/specs/scenario-service.md` D2). Other `/api/projects/**` paths stay unrouted. |
| reports | `/api/reports/**` | report-service | `REPORT_SERVICE_URL` | none | Provisional placeholder |
| (gateway-owned) | `GET /actuator/health` | the gateway itself | — | yes | Active |

Any other path gets **401** without a valid token and **404** with one (deny by default; see A9). In particular, auth-service's `/h2-console`, `/swagger-ui/**`, `/v3/api-docs` and `/actuator/**` are not reachable through the gateway.

The placeholder routes stay enabled. While their services aren't running, they return 503, which is accurate and lets QA test downstream failures without stopping auth-service.

**Forwarding fidelity (2026-10-12, TF-1).** Here is what "no rewriting" means.
- **Byte for byte in phase 1:**
  - the method and the path;
  - the request body, for every method and content type (JSON in AC-1.3, form-encoded in AC-1.7);
  - the response status and body, and the response headers except the ones listed below (AC-1.4).
- **Never moved:** form fields stay in the body. They never move into the query string or the path (AC-1.7).
- **Changes the gateway may make:**
  - the headers it owns: `X-Request-Id`, `X-User-Id`, CORS headers and `Host`;
  - hop-by-hop and framing headers: `Connection`, `Keep-Alive`, `Transfer-Encoding`, `TE`, `Upgrade`, and `Content-Length` when the body is re-framed;
  - on responses that don't have them, the security headers Spring Security adds: `Cache-Control`, `Pragma`, `Expires`, `X-Content-Type-Options`, `X-Frame-Options` and `X-XSS-Protection`. A value set by the service always wins.
- **Known phase-1 deviations** (follow-up FU-2, BUG-8). They are semantically equivalent for the current Spring services.
  - Query strings are decoded and re-encoded, so parameter order and valueless parameters can change.
  - `;charset=UTF-8` is appended to a request `Content-Type` that has no charset.
  - POST form bodies are re-serialised. The fields and values stay the same.
  - gzip or deflate responses are decoded.
  - The downstream HTTP client adds `Accept-Encoding` and h2c upgrade headers.

**Why:** an intact body and path protect every service, and keeping form values out of URLs keeps possible credentials out of downstream access logs. Exact query and header bytes only matter for signed URLs and strict non-Spring servers, and there are none yet.

### D3. Locating services without discovery, and the local port plan

**Default:** static base URLs per environment, read from environment variables with localhost defaults.
**Why:** there's no discovery or orchestration yet, and env vars map directly onto Kubernetes or compose later.

| Service | Local port | Status |
|---|---|---|
| api-gateway | 8080 | Set in T1. Clients and the frontend use the default port. |
| auth-service | 8081 | Set in T3 (cross-service). |
| scenario-service | 8082 | Reserved. Set it when the service gets its first endpoint. |
| report-service | 8083 | Reserved. The service needs a web starter before it can serve HTTP. |

Operator-facing settings. The names are part of the operator contract. If backend-swe has to rename one, record the new name in the contract (T2), the README (T12) and the handoff.

| Setting | Env var | Default (no profile) | `dev` profile |
|---|---|---|---|
| Gateway port | `SERVER_PORT` | 8080 | 8080 |
| auth-service base URL | `AUTH_SERVICE_URL` | `http://localhost:8081` | same |
| scenario-service base URL | `SCENARIO_SERVICE_URL` | `http://localhost:8082` | same |
| report-service base URL | `REPORT_SERVICE_URL` | `http://localhost:8083` | same |
| JWT shared secret | `JWT_SECRET` | none, so startup fails (AC-8.3) | committed dev-only value, identical to auth-service's `application-dev.yaml` |
| CORS allowed origins | `CORS_ALLOWED_ORIGINS` (comma-separated) | empty, so no cross-origin access | `http://localhost:3000,http://localhost:5173` |
| Connect timeout | `GATEWAY_CONNECT_TIMEOUT` | 2 s | 2 s |
| Response timeout | `GATEWAY_RESPONSE_TIMEOUT` | 10 s | 10 s |

The profile is named `dev` in both services, because auth-service already has an (empty) `application-dev.yaml`.

### D4. JWT handling at the edge

**Default:** validate tokens at the edge, and also keep validation in the downstream services.
**Why:** clients get one consistent 401, unauthenticated traffic never reaches services, and services stay safe if someone reaches them directly.

- **What is checked.** The `Authorization: Bearer <jwt>` header; an HS512 signature using the shared secret; `exp` in the future (no clock-skew allowance, see A3); and a `sub` in the allowed format (see "Subject format" below). Unsigned (`alg: none`) and tampered tokens are rejected.
- **Several `Authorization` headers (2026-10-12, TF-8).** A protected request that carries more than one `Authorization` header gets 401 and isn't forwarded, whatever the values are (AC-2.9). **Why:** RFC 9110 allows only one, and a service that reads a different copy would authenticate a credential the gateway never checked. Public operations still forward whatever they receive, unevaluated (AC-2.5).
- **Scheme letter case (2026-10-12, TF-8).** The scheme is matched case-insensitively, so `bearer` is accepted, as RFC 9110 §11.1 requires. auth-service accepts only `Bearer ` exactly (Known issue 6), so a lower-case request fails closed at the service. Clients must send `Bearer`.
- **Subject format (2026-10-12, BUG-12).** `sub` must be 1 or more printable ASCII characters (U+0021–U+007E), with no spaces, control characters or non-ASCII characters. Otherwise the token is invalid, and the request gets 401 and isn't forwarded (AC-2.10). **Why:** `sub` is copied into `X-User-Id` and the space-separated access log, so CR, LF or spaces could forge headers, log lines or log fields. auth-service issues UUIDs, which comply. Validation happens locally, with no call to auth-service, so there's no extra hop and existing tokens still validate while auth-service is down.
- **Forwarding.** The original `Authorization` header is forwarded unchanged. auth-service's `JwtAuthenticationFilter` still needs it.
- **Public operations.** Only the three listed in D2. Matching is by method and path, and every other path is protected. The list lives in configuration, so a new public auth endpoint (for example "forgot password") needs a config change and a spec update. On public operations the gateway doesn't evaluate the token at all. It forwards any `Authorization` header unchanged and lets auth-service decide (AC-2.5). That way a user holding an expired token can still log in again.
- **Identity header.** On protected requests the gateway sets `X-User-Id` to the token's `sub`. It removes every client-supplied `X-User-Id` on all requests, public and protected, whatever the header-name casing. Downstream services may use `X-User-Id` for logging and attribution only. Until network isolation exists, they must not authorize on it; authorization still uses the validated JWT.
- **Secret handling.** Both services read `JWT_SECRET`, which is standard Base64 that decodes to at least 64 bytes (the 512-bit minimum for HS512). The gateway refuses to start if the secret is missing, isn't valid Base64, or is too short. A dev-only value is committed only in the `dev` profile files of both services, with a "DEV ONLY" comment. A production value is never committed. The secret value and tokens are never logged.
- **CSRF.** Disabled at the gateway. It's stateless and uses bearer tokens, not cookies. Revisit if cookies are ever introduced.
- **Authorization.** None at the gateway (see Out of scope).

### D5. CORS

**Default:** the gateway is the only CORS authority, and its allowed origins come from configuration.
**Why:** the frontend will be served from its own origin, and duplicate CORS headers from services break browsers.

- Allowed origins: `CORS_ALLOWED_ORIGINS`, a comma-separated list of exact origins with no wildcards. The default is empty. The `dev` profile allows `http://localhost:3000` and `http://localhost:5173`, the usual frontend dev-server ports (the stack isn't chosen yet).
- Allowed methods: `GET, POST, PUT, PATCH, DELETE, OPTIONS`.
- Allowed request headers: `Authorization, Content-Type, Accept, X-Request-Id`.
- Exposed response headers: `X-Request-Id, Location`.
- Credentials: not allowed (`Access-Control-Allow-Credentials` is absent) because tokens travel in a header, not cookies.
- Preflight cache: 1800 s.
- Preflight requests need no token and are never forwarded to a service.
- **Refused CORS requests.** A preflight from an origin that isn't allowed gets:
  - status 403;
  - the D6 Problem Details body (title `Forbidden`, with `requestId`);
  - the `X-Request-Id` header;
  - no `Access-Control-Allow-*` headers.

  It's never forwarded, and it gets one access log entry like any other request (D7). A non-preflight request that carries a non-allowed `Origin` is refused in the same way.
  **Why:** this matches every other gateway-generated error (D6, D7). Spring's default response is a plain-text `Invalid CORS request` body that has neither the Problem Details shape nor a `requestId`.
- **Disallowed method or header (2026-10-12, TF-10).** A preflight from an allowed origin that requests a method or header outside the policy is refused in the same way: 403 with the same Problem body. The `detail` text ("This origin is not allowed…") is kept. **Why:** the `detail` wording isn't part of the contract, and the status and shape are correct.
- Gateway-generated errors, including 401, carry CORS headers for allowed origins so browsers can read them.
- Downstream services must not emit CORS headers. If they do, the client still gets only one `Access-Control-Allow-Origin` header (AC-6.6).

### D6. Edge errors and timeouts

**Default:** gateway-generated errors use RFC 9457 Problem Details (`application/problem+json`).
**Why:** it's a standard that Spring supports out of the box, and it can become the platform-wide format later.

Body fields: `type` (`about:blank`), `title` (the HTTP reason phrase), `status`, `detail`, `instance` (the request path without the query string), and the extension field `requestId` (equal to the `X-Request-Id` response header). Bodies must never contain stack traces, exception class names, or downstream URLs, hosts or ports.

| Situation | Status | `title` | Suggested `detail` |
|---|---|---|---|
| Protected path with a missing, invalid or expired token | 401 | Unauthorized | A valid bearer token is required. |
| Valid token, but no route matches the path | 404 | Not Found | No API route matches this path. |
| Couldn't connect to the downstream service (connection refused, unknown host, connect timeout) | 503 | Service Unavailable | The service handling this request is unavailable. Try again later. |
| Connected, but the response (status line, headers and the whole body) wasn't complete within the response timeout (2026-10-12: the body is included) | 504 | Gateway Timeout | The service handling this request did not respond in time. |
| The connection dropped before the response was complete, or the response was invalid: a malformed status line, a status code outside 100–599, or a body shorter than its declared length (2026-10-12: failures after the headers arrive are included) | 502 | Bad Gateway | The service handling this request returned an invalid response. |
| CORS request (preflight, or an actual request with an `Origin` header) from an origin that isn't allowed (D5) | 403 | Forbidden | This origin is not allowed to call the API. |
| Unexpected failure inside the gateway | 500 | Internal Server Error | Unexpected gateway error. |

- **Downstream responses** (any status, including 4xx and 5xx) are passed through unchanged: status, headers and body. auth-service has no standard error body today (backend-swe notes: custom `@ResponseStatus` exceptions, no `@ControllerAdvice`). Clients will therefore see two error shapes until a platform-wide format is decided. That's a follow-up, not a blocker.
- **Timeouts:** 2 s to connect, because a refused connection fails at once and 2 s bounds unreachable hosts. 10 s for the response, which is far above any current auth-service operation but stops UIs from hanging. Both are configurable. Per-route timeouts are deferred until large report uploads exist (FU-4).
- **What the response timeout covers (2026-10-12, backend-swe Q3).** It covers the whole downstream exchange, from sending the request to receiving the last byte of the response body. When it runs out, the client gets 504 (AC-4.2, AC-4.6). **Why:** every phase-1 response is small and streaming is out of scope. Streaming and large bodies are FU-4.
- **Never 500 for a downstream failure (2026-10-12, TF-3).** A failure that happens after the response headers arrive is still 502 or 504 according to the table, never 500 (AC-4.6, AC-4.7). Downstream failures (502, 503, 504) are logged at WARN with the route, the status and the failure type, never with a stack trace or the downstream URL. ERROR with a stack trace is reserved for unexpected failures inside the gateway (500).
- **Replays (amended 2026-10-12, TF-2, BUG-3).**
  - The gateway never replays a timed-out request.
  - It never replays a request whose method isn't GET or HEAD.
  - A GET or HEAD whose connection closed before any byte of the response arrived may be sent once more, so at most two attempts in total (AC-4.3, AC-4.8).

  **Why:** the no-retry rule exists because writes aren't idempotent. Replaying a safe read once is standard HTTP client behaviour (RFC 9110 §9.2.2), and it avoids spurious 502s when a pooled connection was closed by the service. qa observed the current HTTP client replay a GET once and send a POST once. AC-4.8 verifies the other methods.
- **Headers on gateway errors (decided at acceptance, 2026-10-12, TF-13, backend-swe Q1).** A gateway-generated error never carries a header from a downstream response. That includes a failure after the downstream headers arrived. The error carries only the gateway's own headers: `X-Request-Id`, CORS headers for allowed origins, and the security headers. **Why:** a failed response's `Set-Cookie`, caching directives or `Content-Length` must not reach the client, and a leftover `Content-Length` breaks framing (qa mutant M4). This is current behaviour, verified for the 502 path by both test suites. The AC for it (AC-4.9, which also requires each gateway header exactly once) comes with FU-6, together with the BUG-16 fix. A response that's already committed can't meet this rule (next bullet).
- **Failures after the response has started (follow-up FU-4).** If the gateway has already started sending the response to the client when the downstream fails, it can't change the status. It must abort the client connection instead, so a truncated body can't be read as complete. This isn't tested in phase 1. **Known phase-1 deviation (BUG-14, S3, found at re-verification):** once more than 8 KB, or a streamed body, has been sent, a downstream failure doesn't abort the connection.
  - A chunked 200 gets the 502 Problem JSON appended and reads as complete.
  - A `Content-Length` response is cut off detectably, but the access entry says 500 and Tomcat logs ERROR stack traces.

  This pre-dates the fix round. Phase-1 traffic doesn't hit it, because auth-service returns small JSON. FU-4 must fix it before any routed service can return larger or streamed bodies.
- **Rejections before routing (2026-10-12, TF-6, BUG-9).**
  - Requests the gateway itself rejects before routing already get `X-Request-Id` and an access log entry. Examples are the request firewall's 400 for a non-canonical path such as `/api/auth/./login`, and 405 for a method a gateway endpoint doesn't support, such as `POST /actuator/health`. Their target body is the Problem Details format with `type` and `requestId`, which is follow-up FU-3. In phase 1 they may carry Spring Boot's default error body.
  - Requests the servlet container rejects before any gateway code runs are out of scope. Examples are an encoded slash or `%00` in the path, and the `TRACE` and `CONNECT` methods. They get a container-generated error response with no `X-Request-Id` and no access log entry. **Why:** they never reach a service, and handling them needs container-level changes.

### D7. Cross-cutting concerns

**Request ID.** Header `X-Request-Id`. A client value matching `^[A-Za-z0-9._-]{1,128}$` is kept; otherwise the gateway generates a UUID. The ID is forwarded to the service, returned on every response (including gateway-generated errors), included in error bodies, and written to the access log. **Why:** trace one call end-to-end, and validation prevents log injection.
- **Exactly one per response (2026-10-12, TF-7, backend-swe Q4).** Every response carries exactly one `X-Request-Id`, with the gateway's value. If a service puts an `X-Request-Id` on its response (in any letter case), the gateway drops it, as it does with downstream CORS headers (AC-7.6). **Why:** the contract promises one value equal to `requestId`. A browser would read duplicates as a single comma-joined value.

**Access log.** Exactly one entry per request, including rejected ones. Fields: request ID, method, path, status, duration in milliseconds, target service, and user ID (only on authenticated protected requests). Never logged: `Authorization` values, tokens, request or response bodies, query strings, the secret. The invitation token path segment is masked as `/api/auth/invitations/***/accept`. **Why:** operators can debug without leaking credentials. An invitation token grants org membership.
- **Target service (2026-10-12, TF-9, backend-swe Q1).** `service` names the target service of the route the request was handed to.
  - It's present whenever a route matched, including when the call then fails (502, 503, 504) and when the gateway fails after routing (500).
  - It's absent when the request was rejected before routing (401, refused-CORS 403, firewall 400) or matched no route (404). See AC-7.4 and AC-7.8.

  **Why:** operators need it most on 5xx responses.
- **Masking in every path form (2026-10-12, TF-4, BUG-2; scope narrowed at acceptance, TF-11, BUG-15).** No invitation token appears in any log line for any path that resolves to `/api/auth/invitations/{token}/accept`, whether the request is forwarded or rejected. A path resolves to it if it matches after the resolution the servlet container and Spring apply: path parameters after `;` are removed, the path is percent-decoded once, letter case is ignored, and empty, `.` and `..` segments are resolved. The access entry shows `***` where the token was (AC-7.5, AC-7.7).
  - Paths that don't resolve to the accept operation are logged as received. Examples are a double-encoded `%2569nvitations`, an encoded `%3B`, which is data rather than a delimiter, and `..;`, which removes the `invitations` segment. The request firewall rejects all three with 400, and none reaches a service.
  - **Why:** only a broken or malicious client sends such paths, so the token it exposes is its own. Masking more broadly would also hide segments of unrelated paths, such as invitation IDs under `/api/orgs/{id}/invitations/…`.

**Health.** `GET /actuator/health` is public and returns `{"status":"UP"}` with no details. It doesn't check downstream services, so a down service never marks the gateway unhealthy. No other actuator endpoint is exposed, including `/actuator/gateway/**`. Kubernetes probe groups (`/actuator/health/liveness`, `/readiness`) and the `/actuator` links page stay off until the Helm wiring is specced (confirmed 2026-10-12, backend-swe Q2; FU-5). The media type follows the `Accept` header: `application/json` when the client asks for it, otherwise `application/vnd.spring-boot.actuator.v3+json`. The body is the same in both cases (2026-10-12, BUG-13; documented in the contract by T19).

**Out of scope this phase** (see the Out of scope list): rate limiting, retries, circuit breakers, Helm/K8s wiring, service discovery.

### D8. Testing approach

**Default:** use only what's already available. **Why:** backend-swe rule 8 forbids new libraries without approval, and none are needed.

- Gateway tests are `@SpringBootTest` runs of the real gateway. Each downstream service is replaced by a stub built on the JDK's built-in HTTP server (`com.sun.net.httpserver`, shipped with JDK 17), listening on an ephemeral port that the tests wire in with dynamic properties.
- The stub records method, path, query, headers, body and request count. It can answer with a scripted status, headers and body, wait before answering, or close the connection without answering.
- Tests mint tokens with jjwt 0.12.5 and a test-only secret.
- Log assertions use Spring Boot's `OutputCaptureExtension`. Startup-failure cases (AC-8.3) start the application with specific properties and assert that startup fails.
- Tests that prove an AC are tagged `@Tag("AC-x.y")`.
- WireMock and Testcontainers aren't used; adding them would need approval.
- The cross-service checks (AC-1.6, AC-2.8, AC-8.6) are manual this phase, following the README steps from T12. No cross-service harness exists.

### D9. Contract documentation

**Default:** `docs/api/api-gateway.openapi.yaml` documents only the **edge conventions**:
- the bearer security scheme and the JWT contract;
- the public operations and the route table (in a description or an `x-routes` extension, linking each prefix to its service's contract file);
- the Problem schema;
- the `X-Request-Id` and `X-User-Id` headers;
- the CORS policy;
- `GET /actuator/health`.

It doesn't copy downstream operations. Each service documents its own operations in `docs/api/<service>.openapi.yaml`, using external paths (which are the same as internal paths, because there's no rewriting).

**Why:** one place for edge behaviour, and no duplicated paths that drift out of date.

### D10. Deferred bugs and their failing reproduction tests (2026-10-12)

**Default:** qa marks each failing reproduction test of a deferred bug with `@Disabled("BUG-api-gateway-<n> deferred: docs/specs/api-gateway.md <FU-id>")`. It keeps the test's `@Tag`, `@DisplayName` and body unchanged.
**Why:** a red build can't be committed. The test stays in the repo as the follow-up's acceptance test, visible in the report as skipped and one line away from re-enabling. `build.gradle` isn't changed (for example, to exclude tags), and no assertion is weakened.

| Bug | Tests in `api-gateway/src/test/java/org/apigateway/qa/` | `@Disabled` reason |
|---|---|---|
| BUG-6 | `QaUnknownPathsTest#healthSubPathsAre404Problems` (3 rows), `QaUnknownPathsTest#theErrorPathIsAnUnroutedPath` | `BUG-api-gateway-6 deferred: docs/specs/api-gateway.md FU-1` |
| BUG-8 | `QaRequestFidelityTest#theRawQueryStringIsForwardedUnchanged`, `QaRequestFidelityTest#theContentTypeHeaderIsForwardedUnchanged` | `BUG-api-gateway-8 deferred: docs/specs/api-gateway.md FU-2` |

Rules:
- This decision authorises qa to make exactly these edits. qa makes them before re-verification, and should make them now so the fix round can reach a green build. They are the only `@Disabled` allowed. G6 still binds backend-swe.
- BUG-9 has no automated reproduction, so there's nothing to disable.
- **BUG-3 isn't deferred.** It's closed by the D6 replay amendment. qa rewrites `QaDownstreamRobustnessTest#aDroppedGetIsNotRetried` to assert AC-4.8's GET row (status 502, at most 2 requests), and tags it `AC-4.8` instead of `BUG-api-gateway-3`. This follows a spec decision; it isn't weakening a test. qa records it in the report.
- If a phase-1 fix makes a disabled test pass, qa may re-enable it during re-verification and report that. pm then closes the matching follow-up.
- **Bugs found at re-verification (decided at acceptance, 2026-10-12, qa question 6).** D10 is **not** extended to them.
  - qa adds no disabled reproductions for BUG-14 or BUG-16 now. It writes them as the first step of FU-4 and FU-6, in the same change set as the fix, so they are never committed red or disabled.
  - BUG-15 is closed by the narrowed D7 rule, so it gets no reproduction.
  - **Why:** the build is green as verified and stays green with no further test edits. The steps to reproduce are in the qa report. Adding skipped tests to the accepted tree, with no fix scheduled, would change what was verified.

### D11. Decisions on qa's findings (2026-10-12)

The detailed rules are in the sections named in the last column.

| Finding | Decision | Why | Recorded in |
|---|---|---|---|
| TF-1 (byte-for-byte or semantic) | Path and body byte for byte, and form fields never move into the URL, in phase 1. Exact query and header bytes are the target but are deferred (FU-2). AC-1.3 covers JSON bodies and now states the content type. Form bodies get AC-1.7. | Body integrity and keeping form values out of URLs matter now. Exact query bytes only matter for signed URLs, which don't exist yet. | D2, AC-1.3, AC-1.7, FU-2 |
| TF-2 (GET replayed) | GET and HEAD may be replayed once when the connection closed before any response byte arrived. Nothing else is replayed. BUG-3 is closed by this decision. | Standard HTTP behaviour that avoids spurious 502s on reused connections. The no-retry rule protects writes, and writes are still never replayed. | D6, AC-4.3, AC-4.8, D10 |
| TF-3 (failures after the headers arrive) | Body cut short or an invalid status line: 502. Response not complete within the response timeout: 504. Never 500. | D6 reserves 500 for faults inside the gateway. | D6, AC-4.6, AC-4.7 |
| TF-4 (masking) | Mask the invitation token in every path form, forwarded or rejected. | An invitation token grants org membership. | D7, AC-7.7 |
| TF-5 (`/error`, health sub-paths) | Target: the 404 Problem. Deferred. | S4: nothing leaks, and the health sub-path status is already right. | FU-1, D10 |
| TF-6 (400, 405, container rejections) | Gateway-level 400 and 405 should use Problem Details with `requestId` (deferred). Container-level rejections are out of scope. | The statuses are already right. Container rejections never reach gateway code or a service. | D6, FU-3 |
| TF-7, Q4 (duplicate `X-Request-Id`) | Exactly one `X-Request-Id`, with the gateway's value. Downstream values are dropped. | The contract promises one value equal to `requestId`. | D7, C3, AC-7.6 |
| TF-8 (several `Authorization` headers, letter case) | More than one `Authorization` header on a protected request gets 401. The scheme stays case-insensitive. | Stops the gateway and a service from checking different credentials. RFC 9110 §11.1 makes the scheme case-insensitive, and auth-service fails closed. | D4, AC-2.9, Known issue 6 |
| TF-9, Q1 (`service` on 502/503/504) | Logged whenever a route matched. | Operators need it most on 5xx responses. | D7, AC-7.8 |
| TF-10 (403 detail for a disallowed method or header) | Accepted as is. | The `detail` wording isn't part of the contract, and the status and shape are right. | D5 |
| Q2 (probes, links page) | Keep both off. | Nothing consumes them until the Helm wiring is specced. | D7, FU-5 |
| Q3 (timeout includes the body) | Accepted for phase 1. It maps to 504 and is logged at WARN. | Every phase-1 response is small, and streaming is out of scope. | D6, AC-4.6, FU-4 |
| BUG-12 (CR/LF in `sub`) | `sub` must be printable ASCII with no spaces; otherwise 401. | `sub` is copied into a header and the access log. | D4, AC-2.10 |
| BUG-13 (health media type) | Document both media types. No code change. | The body is identical, and clients parse JSON either way. | D7, T19 |
| Red build (deferred tests) | qa disables only the listed reproductions, with a reason that points to the follow-up. | A red build can't be committed, and the tests stay as acceptance tests for the follow-ups. | D10 |
| TF-13, backend-swe Q1 (decided at acceptance) | A gateway error never carries a header from the failed downstream response. The rule goes into D6 now; AC-4.9 comes with FU-6, and the contract wording with FU-7. | It's verified current behaviour, and it protects cookies, caching and framing. Adding the AC now would fail on BUG-16, or would have no result for the 504 row. | D6, FU-6, FU-7 |
| backend-swe Q2, `RequestContextHolder` in an SCG header filter (decided at acceptance) | Accepted for phase 1, with no change. The risk is recorded as A12. | It uses public SCG and Spring APIs, and qa verified it under concurrency. A thread change in a future SCG version would fail every proxied call, and AC-4.6 and AC-4.7 would catch that. | A12 |
| TF-12, BUG-14 (decided at acceptance) | Attached to FU-4. Its reproductions become FU-4's acceptance tests. FU-4 is P1 and must land before any service can return a body over 8 KB or stream. | It pre-dates the fix round, D6 had already deferred the area, and phase-1 traffic is small JSON. | D6, FU-4 |
| TF-11, BUG-15 (decided at acceptance) | D7 is narrowed to paths that resolve to the accept operation. BUG-15 is closed by decision. | The three forms are rejected with 400 and resolved by no server. Broader masking would hide unrelated path segments. | D7 |
| TF-14 (decided at acceptance) | The README names both startup-failure markers. | `JWT_SECRET` failures print `APPLICATION FAILED TO START`, not `Application run failed`. | FU-7 |
| qa question 6 (decided at acceptance) | No new disabled tests. Reproductions arrive with their fixes. | It keeps the verified tree and the green build unchanged. | D10 |

## User stories

### US-1: Route API requests to the owning service (P0)
As a frontend developer, I want to call every backend API through one base URL, so that the client never needs to know where each service runs.

```gherkin
Feature: Route API requests to the owning service

  @US-1 @AC-1.1 @P0
  Scenario: A login request is forwarded to auth-service and its response is returned unchanged
    Given the auth-service stub answers "POST /api/auth/login" with status 200, header "Content-Type: application/json" and body {"accessToken":"stub-token"}
    When the client sends "POST /api/auth/login" to the gateway with body {"email":"ana@example.com","password":"Secret123!"}
    Then the auth-service stub receives exactly 1 request "POST /api/auth/login" with body {"email":"ana@example.com","password":"Secret123!"}
    And the client receives status 200, header "Content-Type: application/json" and body {"accessToken":"stub-token"}

  @US-1 @AC-1.2 @P0
  Scenario Outline: Each path prefix is forwarded to its owning service
    When the client sends "<method> <path>" to the gateway with header "Authorization: Bearer {valid token}"
    Then the <service> stub receives exactly 1 request "<method> <path>"
    And no other stub receives a request

    Examples:
      | method | path                                                       | service          |
      | POST   | /api/auth/register                                         | auth-service     |
      | POST   | /api/auth/invitations/inv-7Kq2/accept                      | auth-service     |
      | POST   | /api/orgs                                                  | auth-service     |
      | POST   | /api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa/invitations | auth-service     |
      | GET    | /api/scenarios/42                                          | scenario-service |
      | GET    | /api/reports/42                                            | report-service   |

  @US-1 @AC-1.3 @P0
  Scenario: Method, path, query string, headers and body are forwarded without rewriting
    When the client sends "PATCH /api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa?notify=false&page=2" to the gateway with header "Authorization: Bearer {valid token}", header "Content-Type: application/json", header "Accept-Language: pt-BR" and body {"name":"Acme QA"}
    Then the auth-service stub receives method "PATCH", path "/api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa" and query string "notify=false&page=2"
    And the auth-service stub receives header "Accept-Language: pt-BR" and body {"name":"Acme QA"}

  @US-1 @AC-1.4 @P0
  Scenario Outline: Error responses from a service are passed through unchanged
    Given the auth-service stub answers "POST /api/auth/register" with status <status>, header "Content-Type: application/json" and body <body>
    When the client sends "POST /api/auth/register" to the gateway
    Then the client receives status <status>, header "Content-Type: application/json" and body <body>

    Examples:
      | status | body                                                       |
      | 400    | {"message":"Password must be at least 8 characters long"} |
      | 403    | {"message":"Forbidden"}                                    |
      | 409    | {"message":"Email already exists: ana@example.com"}        |
      | 500    | {"message":"Internal error"}                               |

  @US-1 @AC-1.5 @P0
  Scenario Outline: A path that matches no route returns 404 and reaches no service
    When the client sends "<method> <path>" to the gateway with header "Authorization: Bearer {valid token}"
    Then the client receives status 404
    And no stub receives a request

    Examples:
      | method | path                   |
      | GET    | /api/projects/1        |
      | POST   | /api/authx/login       |
      | GET    | /api                   |
      | GET    | /h2-console            |
      | GET    | /swagger-ui/index.html |
      | GET    | /v3/api-docs           |

  @US-1 @AC-1.6 @P0
  Scenario: Log in through the gateway against the real auth-service (manual end-to-end)
    Given auth-service runs locally on port 8081 and the gateway on port 8080, both with the "dev" profile
    And user "ana@example.com" with password "Secret123!" and full name "Ana Lima" registered through "POST http://localhost:8080/api/auth/register" and received status 201
    When the client sends "POST http://localhost:8080/api/auth/login" with body {"email":"ana@example.com","password":"Secret123!"}
    Then the client receives status 200
    And the response body contains a non-empty "accessToken"

  @US-1 @AC-1.7 @P1
  Scenario Outline: Form-encoded bodies stay in the body and never move into the URL
    When the client sends "<method> /api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa?notify=false&page=2" to the gateway with header "Authorization: Bearer {valid token}", header "Content-Type: application/x-www-form-urlencoded" and body <body>
    Then the auth-service stub receives method "<method>", path "/api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa" and query string "notify=false&page=2"
    And the auth-service stub receives body <body>, byte for byte

    Examples:
      | method | body               |
      | PATCH  | name=Acme+QA       |
      | PUT    | name=Acme+QA       |
      | DELETE | name=Acme+QA       |
      | PATCH  | {"name":"Acme QA"} |
```

Notes for US-1 (2026-10-12):
- AC-1.3 now states the `Content-Type` it always assumed (JSON). Its meaning and ID are unchanged.
- AC-1.7's last row is the AC-1.3 request as `curl -d` sends it, with curl's default form content type.
- POST form bodies and exact query bytes are FU-2 (D2).

### US-2: Reject unauthenticated requests at the edge (P0)
As a frontend developer, I want the gateway to reject missing or invalid tokens with 401 before they reach any service, so that I have one reliable signal to send the user to log in, and services only receive authenticated traffic.

```gherkin
Feature: Authenticate requests at the edge

  @US-2 @AC-2.1 @P0
  Scenario Outline: Public auth operations are forwarded without a token
    When the client sends "<method> <path>" to the gateway without an "Authorization" header
    Then the auth-service stub receives exactly 1 request "<method> <path>"
    And the client receives status 200

    Examples:
      | method | path                                  |
      | POST   | /api/auth/register                    |
      | POST   | /api/auth/login                       |
      | POST   | /api/auth/invitations/inv-7Kq2/accept |

  @US-2 @AC-2.2 @P0
  Scenario: A protected request with a valid token is forwarded with its Authorization header unchanged
    Given the auth-service stub answers "POST /api/orgs" with status 201
    When the client sends "POST /api/orgs" to the gateway with header "Authorization: Bearer {valid token}" and body {"name":"Acme QA"}
    Then the auth-service stub receives exactly 1 request "POST /api/orgs" whose "Authorization" header is byte-for-byte "Bearer {valid token}"
    And the client receives status 201

  @US-2 @AC-2.3 @P0
  Scenario Outline: A protected request without valid credentials is rejected with 401
    When the client sends "POST /api/orgs" to the gateway with Authorization header <authorization>
    Then the client receives status 401
    And no stub receives a request

    Examples:
      | case                          | authorization                                                                                              |
      | header missing                | (none)                                                                                                     |
      | wrong scheme                  | "Basic YW5hOlNlY3JldDEyMyE="                                                                               |
      | empty bearer value            | "Bearer "                                                                                                  |
      | not a JWT                     | "Bearer not-a-jwt"                                                                                         |
      | signed with another secret    | "Bearer {valid token's claims signed with a different 64-byte secret}"                                     |
      | expired                       | "Bearer {valid token whose exp is 1 hour in the past}"                                                     |
      | unsigned                      | "Bearer {valid token's claims with header alg none and no signature}"                                      |
      | payload altered after signing | "Bearer {valid token with sub changed to 00000000-0000-0000-0000-000000000666, original signature kept}"   |

  @US-2 @AC-2.4 @P1
  Scenario: A signed, unexpired token without a subject is rejected
    When the client sends "POST /api/orgs" with a token signed with the shared secret, expiring in 1 hour, and no "sub" claim
    Then the client receives status 401
    And no stub receives a request

  @US-2 @AC-2.5 @P1
  Scenario Outline: Public operations ignore an invalid token and forward it unchanged
    When the client sends "POST /api/auth/login" with header "Authorization: Bearer <token>"
    Then the auth-service stub receives exactly 1 request whose "Authorization" header is "Bearer <token>"
    And the client receives status 200

    Examples:
      | token                                         |
      | not-a-jwt                                     |
      | {valid token whose exp is 1 hour in the past} |

  @US-2 @AC-2.6 @P1
  Scenario Outline: Only the listed auth operations are public
    When the client sends "<method> <path>" to the gateway without an "Authorization" header
    Then the client receives status 401
    And no stub receives a request

    Examples:
      | method | path                                  |
      | GET    | /api/auth/login                       |
      | POST   | /api/auth/logout                      |
      | GET    | /api/auth/invitations/inv-7Kq2/accept |

  @US-2 @AC-2.7 @P2
  Scenario: A 401 from the gateway tells the client to use a bearer token
    When the client sends "POST /api/orgs" without an "Authorization" header
    Then the client receives status 401
    And the response has a "WWW-Authenticate" header whose value starts with "Bearer"

  @US-2 @AC-2.8 @P0
  Scenario: A token issued by the real auth-service is accepted by the gateway (manual end-to-end)
    Given auth-service runs locally on port 8081 and the gateway on port 8080, both with the "dev" profile
    And user "ana@example.com" registered through the gateway, receiving user id U, then logged in through the gateway, receiving accessToken T
    When the client sends "POST http://localhost:8080/api/orgs" with header "Authorization: Bearer T" and body {"name":"Acme QA"}
    Then the response status is not 401
    And the gateway access log entry for that request shows service "auth-service" and user id U

  @US-2 @AC-2.9 @P1
  Scenario Outline: A protected request with more than one Authorization header is rejected
    When the client sends "POST /api/orgs" to the gateway with two "Authorization" headers, first <first> and then <second>
    Then the client receives status 401
    And no stub receives a request

    Examples:
      | first                                                  | second                                                 |
      | "Bearer {valid token}"                                 | "Bearer {valid token whose exp is 1 hour in the past}" |
      | "Bearer {valid token whose exp is 1 hour in the past}" | "Bearer {valid token}"                                 |
      | "Bearer {valid token}"                                 | "Bearer {valid token}"                                 |

  @US-2 @AC-2.10 @P2
  Scenario Outline: A signed token whose subject is unsafe for headers or logs is rejected
    When the client sends "POST /api/orgs" with header "X-Request-Id: sub-<n>" and a token signed with the shared secret, expiring in 1 hour, whose "sub" is <sub>
    Then the client receives status 401
    And no stub receives a request
    And the gateway log contains exactly one access entry with request ID "sub-<n>", written on a single line and with no user id
    And the gateway log contains no "Unexpected gateway error" entry

    Examples:
      | n | sub                                                       |
      | 1 | "abc" + CR + LF + "X-Injected: 1"                         |
      | 2 | "abc" + LF + "requestId=forged"                           |
      | 3 | "abc service=forged-service" (contains spaces)            |
      | 4 | "usuário-1" (contains a non-ASCII character)              |
```

Note for AC-2.8: because of known auth-service defects, auth-service's own answer is currently a 500 or 403 (see Known issues). That's expected and not a gateway failure. This criterion only proves that the gateway accepts real tokens.

### US-3: Pass the caller's identity to services safely (P1)
As a service developer, I want the gateway to tell my service which user made a request, and to guarantee that clients can't forge that information, so that I can attribute and log requests without parsing tokens myself.

```gherkin
Feature: Pass the caller's identity downstream safely

  @US-3 @AC-3.1 @P1
  Scenario: The gateway tells the service which user made the request
    When the client sends "POST /api/orgs" with header "Authorization: Bearer {valid token}"
    Then the auth-service stub receives header "X-User-Id: 7f1c2a9e-0000-4000-8000-000000000001"

  @US-3 @AC-3.2 @P1
  Scenario: A forged identity header on a protected request is replaced
    When the client sends "POST /api/orgs" with header "Authorization: Bearer {valid token}" and header "X-User-Id: 00000000-0000-0000-0000-000000000666"
    Then the auth-service stub receives exactly one "X-User-Id" header, with value "7f1c2a9e-0000-4000-8000-000000000001"

  @US-3 @AC-3.3 @P1
  Scenario Outline: A forged identity header on a public request is removed
    When the client sends "POST /api/auth/login" without a token and with header "<header name>: 00000000-0000-0000-0000-000000000666"
    Then the auth-service stub receives the request without any "X-User-Id" header, in any letter case

    Examples:
      | header name |
      | X-User-Id   |
      | x-user-id   |
```

### US-4: Fail fast when a service is down or slow (P1)
As a frontend developer, I want a fast, distinct error when a backend service is down or too slow, so that the UI can show "service unavailable, try again" instead of hanging.

```gherkin
Feature: Fail fast when a downstream service is down or slow

  @US-4 @AC-4.1 @P1
  Scenario: A service that is not running produces 503 quickly
    Given nothing is listening at the configured scenario-service URL
    When the client sends "GET /api/scenarios/42" with header "Authorization: Bearer {valid token}"
    Then the client receives status 503 within 3 seconds

  @US-4 @AC-4.2 @P1
  Scenario: A service slower than the response timeout produces 504
    Given the response timeout is configured to 2 seconds
    And the scenario-service stub waits 5 seconds before answering "GET /api/scenarios/42"
    When the client sends "GET /api/scenarios/42" with header "Authorization: Bearer {valid token}"
    Then the client receives status 504 within 4 seconds

  @US-4 @AC-4.3 @P1
  Scenario: The gateway never retries a timed-out request
    Given the response timeout is configured to 2 seconds
    And the auth-service stub waits 5 seconds before answering "POST /api/orgs"
    When the client sends "POST /api/orgs" once with header "Authorization: Bearer {valid token}" and receives status 504
    Then 6 seconds later the auth-service stub has received exactly 1 request

  @US-4 @AC-4.4 @P1
  Scenario: Other routes keep working while one service is down
    Given nothing is listening at the configured scenario-service URL
    And the auth-service stub answers "POST /api/auth/login" with status 200
    When the client sends "POST /api/auth/login"
    Then the client receives status 200

  @US-4 @AC-4.5 @P2
  Scenario: A service that drops the connection produces 502
    Given the scenario-service stub accepts the connection for "GET /api/scenarios/42" and closes it without sending a response
    When the client sends "GET /api/scenarios/42" with header "Authorization: Bearer {valid token}"
    Then the client receives status 502

  @US-4 @AC-4.6 @P1
  Scenario: A response body slower than the response timeout produces 504, not 500
    Given the response timeout is configured to 2 seconds
    And the scenario-service stub answers "GET /api/scenarios/42" with status 200 and its headers at once, then sends its body in 4 parts 1 second apart
    When the client sends "GET /api/scenarios/42" with header "Authorization: Bearer {valid token}"
    Then the client receives status 504 within 4 seconds, with header "Content-Type: application/problem+json" and body field "title" "Gateway Timeout"
    And the gateway log contains no "Unexpected gateway error" entry

  @US-4 @AC-4.7 @P2
  Scenario Outline: An incomplete or invalid downstream response produces 502, not 500
    Given the scenario-service stub answers "GET /api/scenarios/42" with <reply>
    When the client sends "GET /api/scenarios/42" with header "Authorization: Bearer {valid token}"
    Then the client receives status 502 with header "Content-Type: application/problem+json" and body field "title" "Bad Gateway"
    And the body field "requestId" equals the response header "X-Request-Id"
    And the gateway log contains no "Unexpected gateway error" entry

    Examples:
      | reply                                                                                                      |
      | status 200, header "Content-Length: 100" and only the first 11 bytes of the body, then closes the connection |
      | the status line "HTTP/1.1 999 Weird", header "Content-Length: 2" and body {}                                |

  @US-4 @AC-4.8 @P1
  Scenario Outline: Only a safe read is ever sent again after the service drops the connection
    Given the <service> stub accepts the connection for "<method> <path>" and closes it without sending a response
    When the client sends "<method> <path>" once with header "Authorization: Bearer {valid token}"
    Then the client receives status 502
    And 3 seconds later the <service> stub has received at least 1 and at most <max> requests "<method> <path>"

    Examples:
      | method | path                                            | service        | max |
      | POST   | /api/orgs                                       | auth-service   | 1   |
      | PUT    | /api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa  | auth-service   | 1   |
      | PATCH  | /api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa  | auth-service   | 1   |
      | DELETE | /api/orgs/3b9d5c1e-0000-4000-8000-0000000000aa  | auth-service   | 1   |
      | GET    | /api/reports/42                                 | report-service | 2   |
```

Notes for US-4 (2026-10-12):
- AC-4.3's title said "a failed request". It now says "a timed-out request" so it matches the amended D6. Its steps and data are unchanged.
- AC-4.8 replaces the strict no-replay reading for GET (BUG-3, closed by decision).

### US-5: Consistent, safe error responses from the gateway (P1)
As a frontend developer, I want every error the gateway generates to have the same documented JSON shape, so that I can handle all of them in one place.

```gherkin
Feature: Consistent, safe gateway error responses

  @US-5 @AC-5.1 @P1
  Scenario Outline: Gateway-generated errors use the Problem Details format
    Given <condition>
    When the client sends "<method> <path>"
    Then the client receives status <status> with header "Content-Type: application/problem+json"
    And the body field "status" is <status> and the body field "title" is "<title>"
    And the body field "instance" is "<path>"
    And the body field "requestId" equals the response header "X-Request-Id"
    And the body field "detail" is a non-empty string

    Examples:
      | condition                                                                              | method | path              | status | title               |
      | no token is sent                                                                       | POST   | /api/orgs         | 401    | Unauthorized        |
      | a valid token is sent                                                                  | GET    | /api/projects/1   | 404    | Not Found           |
      | a valid token is sent and nothing is listening at the scenario-service URL             | GET    | /api/scenarios/42 | 503    | Service Unavailable |
      | a valid token is sent and the scenario-service stub is slower than the response timeout | GET    | /api/scenarios/42 | 504    | Gateway Timeout     |

  @US-5 @AC-5.2 @P1
  Scenario: Gateway errors don't reveal internal details
    Given the scenario-service URL is configured as "http://localhost:59999" and nothing is listening there
    When the client sends "GET /api/scenarios/42" with header "Authorization: Bearer {valid token}"
    Then the client receives status 503
    And the response body contains none of "localhost", "59999", "Exception", "at org.", "at java."
```

### US-6: Call the API from a browser frontend (P1)
As a frontend developer, I want my browser app, served from its own origin, to call the gateway, so that the frontend can be developed and deployed separately from the API.

```gherkin
Feature: Cross-origin access for a browser frontend

  @US-6 @AC-6.1 @P1
  Scenario: The gateway answers a preflight request from an allowed origin
    Given the allowed origins are "http://localhost:5173"
    When the client sends "OPTIONS /api/orgs" without a token, with headers "Origin: http://localhost:5173", "Access-Control-Request-Method: POST" and "Access-Control-Request-Headers: authorization, content-type"
    Then the client receives status 200
    And the header "Access-Control-Allow-Origin" is "http://localhost:5173"
    And the header "Access-Control-Allow-Methods" includes "POST"
    And the header "Access-Control-Allow-Headers" includes "authorization" and "content-type"
    And no stub receives a request

  @US-6 @AC-6.2 @P1
  Scenario: A preflight request from an unknown origin is refused
    Given the allowed origins are "http://localhost:5173"
    When the client sends "OPTIONS /api/orgs" with headers "Origin: https://evil.example" and "Access-Control-Request-Method: POST"
    Then the client receives status 403 with header "Content-Type: application/problem+json"
    And the body field "title" is "Forbidden" and the body field "requestId" equals the response header "X-Request-Id"
    And the response has no header whose name starts with "Access-Control-Allow-"
    And no stub receives a request

  @US-6 @AC-6.3 @P1
  Scenario: An allowed origin can read the response and its request ID
    Given the allowed origins are "http://localhost:5173"
    When the client sends "POST /api/auth/login" with header "Origin: http://localhost:5173"
    Then the client receives status 200
    And the header "Access-Control-Allow-Origin" is "http://localhost:5173"
    And the header "Access-Control-Expose-Headers" includes "X-Request-Id"

  @US-6 @AC-6.4 @P1
  Scenario: An allowed origin can read a 401 from the gateway
    Given the allowed origins are "http://localhost:5173"
    When the client sends "POST /api/orgs" without a token and with header "Origin: http://localhost:5173"
    Then the client receives status 401
    And the header "Access-Control-Allow-Origin" is "http://localhost:5173"

  @US-6 @AC-6.5 @P1
  Scenario Outline: Allowed origins come from configuration
    Given the gateway runs <profile> and CORS_ALLOWED_ORIGINS is <setting>
    When the client sends "OPTIONS /api/orgs" with headers "Origin: <origin>" and "Access-Control-Request-Method: POST"
    Then the preflight is <outcome>

    Examples:
      | profile                 | setting                                       | origin                  | outcome                                                     |
      | without the dev profile | "https://app.example.com"                     | https://app.example.com | allowed (status 200, Access-Control-Allow-Origin = origin) |
      | without the dev profile | "https://app.example.com"                     | http://localhost:5173   | refused (status 403)                                        |
      | without the dev profile | "https://a.example.com,https://b.example.com" | https://b.example.com   | allowed (status 200, Access-Control-Allow-Origin = origin) |
      | without the dev profile | not set                                       | http://localhost:5173   | refused (status 403)                                        |
      | with the dev profile    | not set                                       | http://localhost:5173   | allowed (status 200, Access-Control-Allow-Origin = origin) |

  @US-6 @AC-6.6 @P2
  Scenario: CORS headers set by a service are not duplicated
    Given the allowed origins are "http://localhost:5173"
    And the auth-service stub answers "POST /api/auth/login" with status 200 and header "Access-Control-Allow-Origin: *"
    When the client sends "POST /api/auth/login" with header "Origin: http://localhost:5173"
    Then the response has exactly one "Access-Control-Allow-Origin" header, with value "http://localhost:5173"
```

### US-7: Trace a request through the gateway (P1)
As an operator, I want every request to carry a request ID that appears in the response, in the downstream request and in the gateway log, so that I can trace a failing call from end to end.

```gherkin
Feature: Request tracing and access logging

  @US-7 @AC-7.1 @P1
  Scenario: The gateway assigns a request ID when the client sends none
    When the client sends "POST /api/auth/login" without an "X-Request-Id" header
    Then the response has an "X-Request-Id" header whose value is a UUID
    And the auth-service stub receives an "X-Request-Id" header with the same value

  @US-7 @AC-7.2 @P1
  Scenario: A well-formed client request ID is kept
    When the client sends "POST /api/auth/login" with header "X-Request-Id: fe-2026-10-11.abc_123"
    Then the response has header "X-Request-Id: fe-2026-10-11.abc_123"
    And the auth-service stub receives header "X-Request-Id: fe-2026-10-11.abc_123"

  @US-7 @AC-7.3 @P1
  Scenario Outline: A malformed client request ID is replaced
    When the client sends "POST /api/auth/login" with an "X-Request-Id" header whose value is <value>
    Then the response "X-Request-Id" header is a newly generated UUID
    And the auth-service stub receives that same UUID in its "X-Request-Id" header

    Examples:
      | value                              |
      | a string of 129 "a" characters     |
      | "req id with spaces"               |
      | "abc;def"                          |
      | an empty string                    |

  @US-7 @AC-7.4 @P1
  Scenario Outline: The gateway writes exactly one access log entry per request
    Given the auth-service stub answers any request with status <stub status>
    When the client sends "<method> <path>" <token> and with header "X-Request-Id: trace-<n>"
    Then the gateway log contains exactly one access entry with request ID "trace-<n>"
    And that entry contains method "<method>", path "<path>", status <status> and a duration in milliseconds
    And that entry's service is <service> and its user id is <user>

    Examples:
      | n | method | path            | token                | stub status | status | service        | user                                   |
      | 1 | POST   | /api/auth/login | without a token      | 200         | 200    | "auth-service" | absent                                 |
      | 2 | POST   | /api/orgs       | with a valid token   | 201         | 201    | "auth-service" | "7f1c2a9e-0000-4000-8000-000000000001" |
      | 3 | POST   | /api/orgs       | without a token      | 201         | 401    | absent         | absent                                 |

  @US-7 @AC-7.5 @P1
  Scenario: Secrets never appear in gateway logs
    When the client sends "POST /api/orgs" with header "Authorization: Bearer {valid token}"
    And the client sends "POST /api/auth/login" with body {"email":"ana@example.com","password":"Secret123!"}
    And the client sends "POST /api/auth/invitations/inv-7Kq2SecretToken/accept"
    Then the gateway log output contains neither {valid token}, nor "Secret123!", nor "inv-7Kq2SecretToken"
    And the access entry for the invitation request shows path "/api/auth/invitations/***/accept"

  @US-7 @AC-7.6 @P1
  Scenario Outline: The response carries exactly one request ID, the gateway's
    Given the auth-service stub answers "POST /api/auth/login" with status 200 and header "x-request-id: downstream-echo"
    When the client sends "POST /api/auth/login" <client request id>
    Then the response has exactly one "X-Request-Id" header, in any letter case
    And its value is <expected>

    Examples:
      | client request id                       | expected                                                       |
      | with header "X-Request-Id: client-rid"  | "client-rid"                                                   |
      | without an "X-Request-Id" header        | a UUID, the same value the auth-service stub received          |

  @US-7 @AC-7.7 @P1
  Scenario Outline: Invitation tokens are masked whatever form the path takes
    When the client sends "POST <path>" without a token and with header "X-Request-Id: mask-<n>"
    Then the gateway log contains exactly one access entry with request ID "mask-<n>"
    And that entry's path contains "***"
    And no gateway log line contains "<token>"

    Examples:
      | n | path                                              | token          |
      | 1 | /api/auth/%69nvitations/inv-Enc1Secret/accept     | inv-Enc1Secret |
      | 2 | /api/%61uth/invitations/inv-Enc2Secret/accept     | inv-Enc2Secret |
      | 3 | /api/auth/inv%69tations/inv-Enc3Secret/accept     | inv-Enc3Secret |
      | 4 | /api/auth//invitations/inv-Dbl4Secret/accept      | inv-Dbl4Secret |
      | 5 | /api/auth/invitations/./inv-Dot5Secret/accept     | inv-Dot5Secret |

  @US-7 @AC-7.8 @P2
  Scenario Outline: The access entry names the target service when that service fails
    Given <condition>
    When the client sends "GET /api/scenarios/42" with header "Authorization: Bearer {valid token}" and header "X-Request-Id: fail-<n>"
    Then the client receives status <status>
    And the gateway log contains exactly one access entry with request ID "fail-<n>", status <status> and service "scenario-service"

    Examples:
      | n | condition                                                                                                       | status |
      | 1 | nothing is listening at the configured scenario-service URL                                                     | 503    |
      | 2 | the response timeout is configured to 2 seconds and the scenario-service stub waits 5 seconds before answering | 504    |
      | 3 | the scenario-service stub accepts the connection and closes it without sending a response                       | 502    |
```

Note for US-7 (2026-10-12): in AC-7.7, the paths in rows 1–3 are forwarded as the public accept operation, and rows 4–5 are rejected by the request firewall. The paths must be sent exactly as written, without client-side normalisation (for example `curl --path-as-is`).

### US-8: Configure and run the gateway per environment (P0)
As an operator or developer, I want to set where each service lives, the gateway's port and the shared secret per environment, so that the same build runs locally now and in other environments later.

```gherkin
Feature: Configure and run the gateway

  @US-8 @AC-8.1 @P0
  Scenario: Service locations come from configuration
    Given the auth-service stub listens on a random local port P
    And the gateway is started with AUTH_SERVICE_URL set to "http://localhost:P"
    When the client sends "POST /api/auth/login"
    Then the auth-service stub on port P receives exactly 1 request "POST /api/auth/login"

  @US-8 @AC-8.2 @P1
  Scenario Outline: The gateway listens on 8080 unless configured otherwise
    Given the gateway is started <how>
    When the client sends "GET /actuator/health" to port <port>
    Then the client receives status 200

    Examples:
      | how                               | port |
      | with no port setting              | 8080 |
      | with environment SERVER_PORT=9080 | 9080 |

  @US-8 @AC-8.3 @P1
  Scenario Outline: The gateway refuses to start without a usable JWT secret
    Given the "dev" profile is not active and JWT_SECRET is <value>
    When the gateway starts
    Then startup fails
    And the startup error message mentions "JWT_SECRET"
    And the log output doesn't contain the configured secret value

    Examples:
      | value                                                                  |
      | not set                                                                |
      | "some-secret-key-that-is-long-enough" (not standard Base64)            |
      | the Base64 encoding of 32 random bytes (below the 64-byte HS512 minimum) |

  @US-8 @AC-8.4 @P1
  Scenario: Health is public and doesn't depend on downstream services
    Given nothing is listening at the auth-service, scenario-service or report-service URLs
    When the client sends "GET /actuator/health" without a token
    Then the client receives status 200 with body {"status":"UP"}
    And the body has no field other than "status"

  @US-8 @AC-8.5 @P1
  Scenario Outline: Other management endpoints are not exposed
    When the client sends "GET <path>" with header "Authorization: Bearer {valid token}"
    Then the client receives status 404

    Examples:
      | path                     |
      | /actuator/env            |
      | /actuator/beans          |
      | /actuator/configprops    |
      | /actuator/gateway/routes |

  @US-8 @AC-8.6 @P0
  Scenario: The gateway and auth-service run side by side locally (manual)
    Given the "dev" profile is active for both services and neither has a port setting
    When auth-service and the gateway are started on the same machine
    Then both start without a port conflict
    And auth-service answers on port 8081
    And "GET http://localhost:8080/actuator/health" returns status 200
```

## Breakdown

| Area | Work | Depends on |
|---|---|---|
| api-gateway | T1, T2, T4–T12 (build cleanup, contract, routes, request ID, JWT, errors, identity header, CORS, access log, actuator, end-to-end check). Fix round: T13–T19 (BUG-1 first). | Nothing (D1 approved on 2026-10-11). Fix round: approved by the user on 2026-10-12; qa applies D10. |
| auth-service (cross-service) | T3: `server.port` 8081, and the JWT secret read from `JWT_SECRET`, with a dev-only value in `application-dev.yaml`. Config only, no Java changes. | Nothing. Unblocks AC-1.6, AC-2.8, AC-8.6. |
| scenario-service | None now. Reserved: port 8082 and prefix `/api/scenarios/**` (provisional). When it gets endpoints: emit no CORS headers, keep validating JWTs, and optionally log `X-Request-Id` / `X-User-Id`. | Its own first spec |
| report-service | None now. Reserved: port 8083 and `/api/reports/**` (provisional). It needs a web starter before it can serve HTTP. | Its own first spec (reporting ownership is TBD) |
| docs/api | `docs/api/api-gateway.openapi.yaml` (T2, by backend-swe). Update for the 2026-10-12 decisions in T19. | Spec `Ready`. T19: T13–T18. |
| qa | Apply D10 now. Then re-verify per the scope under Acceptance, and update the report and test plan. | D10: nothing. Re-verification: T13–T19. |
| Frontend (not in repo) | None now. Later: base URL `http://localhost:8080`; on 401 send the user to log in; handle `application/problem+json`; read `X-Request-Id`. | T2 contract |
| Helm chart `cucumber/`, Docker, CI | None (out of scope) | — |

## Engineering guardrails (binding for every task)

Every task must meet all of these rules. A task isn't done while any rule is broken. If a rule blocks you, stop and ask in your handoff; don't work around it.

### Scope control
- **G1. Allowed files only.** A task creates, modifies or deletes only the files on its **Allowed files** line. New files may only go under:
  - `api-gateway/src/main/java/org/apigateway/**`
  - `api-gateway/src/test/java/org/apigateway/**`
  - `api-gateway/src/main/resources/**`
  - `docs/api/api-gateway.openapi.yaml`
  - `api-gateway/README.md`

  Needing any other file means stop and ask. `api-gateway/src/test/resources/` is **not** on this list, so supply test properties in test code (`@SpringBootTest(properties = …)`, `@DynamicPropertySource`).
- **G2. Existing code.** Files that existed at aa2234c may change only through the D1 `build.gradle` changes and the approved items under D1. Nothing else changes in auth-service, scenario-service, report-service, `cucumber/` or `.claude/`.
- **G3. Files from earlier tasks.** A later task may change a file created earlier in this feature only if its Allowed files line names that file.
- **G4. No drive-by changes.** No refactors, reformatting, import reshuffles, renames or "while I'm here" fixes. That includes the known auth-service defects: report them, don't fix them.
- **G5. No version or dependency changes beyond D1.** Spring Boot, Spring Cloud, the Gradle wrapper, the Java toolchain and plugins stay as they are. Add no other library, including `spring-boot-starter-validation` (see P1).
- **G6. Tests stay honest.** Don't disable, skip, delete or weaken tests: no `@Disabled`, no assumptions, no looser assertions or timeouts. Every task ends with `cd api-gateway && ./gradlew build` green. During the fix round, F2 defines which failures a task may leave. The only `@Disabled` tests allowed are the ones D10 lists, and only qa applies them.
- **G7. No commits or pushes.** Leave changes in the working tree. Each task's handoff includes the output of `git status --short` and `git diff --stat`.
- **G8. Self-review the diff before handing off.** Check that:
  - only allowed files were touched;
  - there are no unrelated whitespace or formatting changes;
  - there's no debug output, commented-out code or stray file;
  - there are no secrets other than the labelled dev-only `JWT_SECRET` value in the two `application-dev.yaml` files.

### Package layout (decided)

This mirrors auth-service's `configuration`, `security` and `exception` packages. `filter` is new because the gateway has several request filters. Add no other package unless your handoff says why.

| Package (main and test) | Contents |
|---|---|
| `org.apigateway` | `ApiGatewayApplication` only. Tests for behaviour that is YAML-only (routes, actuator exposure). |
| `org.apigateway.configuration` | Typed `@ConfigurationProperties` records, and `@Configuration` classes: security filter chain, CORS source, HTTP-client timeouts |
| `org.apigateway.security` | Token validation, secret decoding, the 401 `AuthenticationEntryPoint` |
| `org.apigateway.filter` | Cross-cutting request filters: request ID, identity header, access log |
| `org.apigateway.exception` | Mapping of gateway failures to `ProblemDetail` |
| `org.apigateway.support` (test only) | Shared test helpers: the stub server, the token factory, common test setup |

### Patterns and practices
- **P1. Configuration over code.**
  - Routes, public operations, CORS origins, timeouts and service URLs live in YAML.
  - Gateway settings bind to typed `@ConfigurationProperties` records with `@Validated` constraints, and startup fails on invalid values. Don't scatter `@Value` across classes.
  - Check the resolved classpath for a Bean Validation provider (`./gradlew dependencies --configuration runtimeClasspath`). Without one, constraint annotations are silently ignored. In that case, enforce the same rules in the record's compact constructor and name the property in the error (G5 forbids adding the validation starter).
- **P2. Framework extension points, not hand-rolled code.**
  - Authentication: a Spring Security `SecurityFilterChain` (stateless, deny by default) with an `AuthenticationEntryPoint` for 401.
  - Error bodies: Spring's `ProblemDetail`. Never hand-built JSON strings.
  - Routing and per-route behaviour: Spring Cloud Gateway Server MVC route configuration and filter functions.
  - Cross-cutting servlet concerns (request ID, access log): a `OncePerRequestFilter`.
  - CORS: a `CorsConfigurationSource` built from properties.
  - Logging: SLF4J, with the request ID in the MDC. Set it when the request starts and remove it in a `finally` block.
- **P3. Filter order.**
  - Request ID and access logging run before security, so 401 responses also get a request ID and a log entry (AC-5.1, AC-7.4).
  - CORS runs before authentication, so preflights and 401 responses carry CORS headers (AC-6.1, AC-6.4).
- **P4. Single responsibility and injection.**
  - One small class per concern: request ID, token validation, identity header, access log, error handling, CORS, security configuration.
  - Constructor injection with `private final` fields (`@RequiredArgsConstructor`). No field injection, no static mutable state.
- **P5. Java hygiene.**
  - Records for immutable data.
  - `Optional` instead of returning `null` where it fits.
  - Named constants or configuration instead of magic numbers and strings (for example the request-ID pattern and the 64-byte secret minimum).
  - Meaningful names.
  - No `catch (Exception)` that swallows errors; don't copy the pattern in auth-service's `JwtAuthenticationFilter`. Catch the specific exception and turn it into the outcome this spec defines.
  - No TODO unless it names an AC or a follow-up listed in your handoff.
- **P6. Security.**
  - Never log secrets, tokens or `Authorization` values.
  - Validate every untrusted header (`X-Request-Id`, `Authorization`, `X-User-Id`) before using or forwarding it.
  - Startup fails on a missing or weak secret.
- **P7. Tests.**
  - Behaviour-focused, one AC per test where practical, tagged `@Tag("AC-x.y")`. Name classes `<Subject>Test` and place them per the package layout table.
  - A Given/When/Then structure in each test body.
  - AssertJ assertions.
  - Deterministic: ephemeral ports for the gateway and the stubs, no external network.
  - No `Thread.sleep` for synchronisation. Use Awaitility (part of `spring-boot-starter-test`) to wait for an event, or to assert that one doesn't happen. Stub-side delays for timeout tests are fine.
- **P8. Don't guess framework APIs.** You may be unsure of an exact Spring Cloud Gateway Server MVC 2025.1 property name or API, for example route definitions, HTTP-client timeouts, how connection and timeout failures surface, or the gateway actuator endpoint id. If so, verify it against the 2025.1 reference docs and the resolved jars, and say in your handoff what you verified.

## Tasks for backend-swe

Do the tasks in order. Each one is small and independently verifiable. Every task follows the Engineering guardrails above. A task may touch only the files on its **Allowed files** line, and must respect its **Must not** line. Tag tests with `@Tag("AC-x.y")`. Run `cd api-gateway && ./gradlew build` at the end of every task, and include `git status --short` and `git diff --stat` in each handoff (G7).

**T1. Build and skeleton cleanup.** Prerequisite: none (D1 approved on 2026-10-11).
- Files:
  - `api-gateway/build.gradle`: apply the D1 table.
  - Delete `api-gateway/src/main/java/org/router/GatewayRouter.java`.
  - Move `api-gateway/src/main/java/org/ApiGatewayApplication.java` to package `org.apigateway`.
  - Replace `api-gateway/src/main/resources/application.properties` with `application.yaml` (application name, `server.port: 8080`).
- ACs: enabler for all; part of AC-8.2.
- Done when:
  - `./gradlew build` passes, with the existing `contextLoads` test now in the same package as the app.
  - `./gradlew bootRun` starts on 8080 with no "ComponentScan of 'org'" warning and no Spring Cloud compatibility error.
- **Stop condition:** if dependency resolution or the Spring Cloud compatibility check fails for Boot 4.1.0 with Spring Cloud 2025.1.2, stop and report to pm with the exact error (see the version risk under D1). Don't change versions or disable the check yourself.
- **Allowed files:**
  - modify `api-gateway/build.gradle` (the `dependencies` block only, per D1);
  - delete `api-gateway/src/main/java/org/router/GatewayRouter.java` and `api-gateway/src/main/resources/application.properties`;
  - move `api-gateway/src/main/java/org/ApiGatewayApplication.java` to `api-gateway/src/main/java/org/apigateway/ApiGatewayApplication.java`;
  - create `api-gateway/src/main/resources/application.yaml`.
- **Must not:**
  - touch plugins, versions, `group`, the toolchain, `dependencyManagement` or the wrapper;
  - change `ApiGatewayApplication` beyond its package line;
  - edit `ApiGatewayApplicationTests.java`;
  - add any class.

**T2. Edge contract.**
- File: `docs/api/api-gateway.openapi.yaml`, with the content listed in D9: servers `http://localhost:8080`; the bearer security scheme with the JWT contract from D4; the route table and public operations from D2; the Problem schema and error table from D6; the `X-Request-Id` and `X-User-Id` headers; the CORS policy from D5; `GET /actuator/health` with no security.
- ACs: documents the behaviour behind US-2, US-3, US-5, US-6, US-7 and AC-8.4.
- Done when: the file parses as OpenAPI 3.x (name the validator you used, or say none was available), and every row of the D2 and D6 tables appears in it.
- **Allowed files:** create `docs/api/api-gateway.openapi.yaml`.
- **Must not:**
  - touch code or config;
  - document downstream operations;
  - describe behaviour that isn't in this spec (raise gaps with pm instead).

**T3. [Cross-service] auth-service port and JWT secret.**
- Files:
  - `auth-service/src/main/resources/application.yaml`: `server.port: 8081`; `app.jwtSecret: ${JWT_SECRET}`.
  - `auth-service/src/main/resources/application-dev.yaml`: `app.jwtSecret` set to a dev-only value with a "DEV ONLY" comment. The value is the standard Base64 of 64 random bytes on a single line (for example `openssl rand -base64 64 | tr -d '\n'`).
  - T6 copies the same dev value into the gateway's `application-dev.yaml`.
- No Java changes.
- ACs: AC-8.6; enables AC-1.6 and AC-2.8.
- Done when:
  - With `--spring.profiles.active=dev`, auth-service starts on 8081.
  - `POST localhost:8081/api/auth/register` returns 201, then `POST /api/auth/login` returns 200 with a non-empty `accessToken`.
  - Your handoff says whether login failed before the change. Code reading predicts that it did (see Known issues, item 1).
  - The auth-service `./gradlew build` still fails only for the baseline reason (test package mismatch). Report that, don't fix it.
- If login still fails for a reason unrelated to the secret, stop and report it. Don't fix unrelated auth-service defects in this feature.
- **Allowed files:**
  - modify `auth-service/src/main/resources/application.yaml`: add `server.port: 8081` and change the `app.jwtSecret` value to `${JWT_SECRET}`, nothing else;
  - modify `auth-service/src/main/resources/application-dev.yaml`: add `app.jwtSecret` with the dev-only value.
- **Must not:**
  - touch any other auth-service file or key, including Java code, `build.gradle` and the misplaced `spring.datasource` keys;
  - fix known defects;
  - commit a non-dev secret.

**T4. Routing table, service URLs, timeouts and the stub test harness.**
- Files:
  - `api-gateway/src/main/resources/application.yaml`: routes per D2, base URLs and env vars per D3, connect and response timeouts per D6. The `dev` and default values are the same here, so `application-dev.yaml` isn't needed until T6.
  - An interim security configuration (stateless, CSRF off, all requests permitted). T6 replaces it, and it must not survive T6.
  - Under `api-gateway/src/test/java/org/apigateway/`: a reusable JDK-HttpServer stub (D8) and routing tests.
- ACs: AC-1.1, AC-1.2, AC-1.3, AC-1.4, AC-1.5 (status only; the body comes in T7), AC-8.1, AC-8.2.
- Done when: the tests tagged with these ACs pass.
- **Allowed files:**
  - modify `api-gateway/src/main/resources/application.yaml`;
  - create classes in `api-gateway/src/main/java/org/apigateway/configuration/`: typed properties for service URLs and timeouts, the interim security configuration, and HTTP-client timeout wiring if configuration alone can't do it (P8);
  - create `api-gateway/src/test/java/org/apigateway/support/` (stub server, common test setup);
  - create route tests in `api-gateway/src/test/java/org/apigateway/` and `api-gateway/src/test/java/org/apigateway/configuration/`.
- **Must not:**
  - define routes in Java;
  - rewrite or strip paths;
  - hard-code URLs, ports or timeouts in Java;
  - add retries or `lb://` URIs;
  - let the interim permit-all configuration survive T6.

**T5. Request ID.**
- Area: request handling that runs before security, so 401 responses get an ID too. Validation rule and behaviour per D7. The ID is forwarded downstream, returned on the response, and made available to logging and error handling.
- ACs: AC-7.1, AC-7.2, AC-7.3.
- Done when: those tests pass.
- **Allowed files:**
  - create the request ID filter and its constants in `api-gateway/src/main/java/org/apigateway/filter/`;
  - create one filter-registration class in `.../configuration/`, only if ordering ahead of security needs one (P3);
  - create tests in `api-gateway/src/test/java/org/apigateway/filter/`;
  - modify the `support/` test classes created in T4.
- **Must not:**
  - forward or echo an unvalidated client value;
  - leave the request ID in the MDC after the request ends;
  - run the filter after security.

**T6. JWT validation at the edge, and secret loading.**
- Area: replace the interim security configuration. Implement:
  - the public operations list from configuration (D2/D4);
  - deny by default, stateless, CSRF disabled;
  - a 401 response with a `WWW-Authenticate: Bearer` header;
  - JWT validation with jjwt 0.12.5 (HS512, shared secret, `exp`, non-empty `sub`);
  - the secret from `JWT_SECRET`: Base64-decoded, at least 64 bytes, fail fast otherwise, never logged;
  - the `Authorization` header forwarded unchanged.
- Tests supply a test-only secret in test code (G1). Update the T4 tests to send valid tokens where routes are protected.
- ACs: AC-2.1, AC-2.2, AC-2.3, AC-2.4, AC-2.5, AC-2.6, AC-2.7, AC-8.3.
- Done when: those tests pass, and no permit-all configuration remains.
- **Allowed files:**
  - create classes in `api-gateway/src/main/java/org/apigateway/security/`: token validation, secret decoding, the 401 entry point;
  - create the JWT and public-operations properties records in `.../configuration/`, and modify the security configuration created in T4;
  - modify `api-gateway/src/main/resources/application.yaml`;
  - create `api-gateway/src/main/resources/application-dev.yaml` with the dev secret only, the same value as auth-service's;
  - create tests in `api-gateway/src/test/java/org/apigateway/security/` and `.../configuration/`;
  - modify `support/` (add a token factory) and the T4 route tests;
  - modify `api-gateway/src/test/java/org/apigateway/ApiGatewayApplicationTests.java`, only to add a test-only secret through `@SpringBootTest(properties = …)` (approved item 5 under D1). The test method body stays unchanged.
- **Must not:**
  - call auth-service;
  - use `@Value`;
  - swallow exceptions;
  - log tokens, `Authorization` values or the secret;
  - give `JWT_SECRET` a default outside the `dev` profile;
  - leave any permit-all rule.

**T7. Gateway error responses and downstream failure handling.**
- Area: Problem Details bodies for 401, 404, 502, 503, 504 and 500 per D6, including `requestId`. Map connection failures to 503, response timeouts to 504, and dropped or invalid responses to 502. No retries. No internal details in bodies. Downstream responses are passed through untouched.
- ACs: AC-1.5 (body), AC-4.1, AC-4.2, AC-4.3, AC-4.4, AC-4.5, AC-5.1, AC-5.2.
- Done when: those tests pass.
- **Allowed files:**
  - create classes in `api-gateway/src/main/java/org/apigateway/exception/`;
  - modify the 401 entry point in `.../security/` so it uses the shared Problem Details format;
  - modify `application.yaml` only for error-handling settings;
  - create tests in `api-gateway/src/test/java/org/apigateway/exception/`;
  - modify `support/` (stub delay and drop-connection modes).
- **Must not:**
  - change the status, headers or body of downstream responses;
  - retry;
  - put exception messages, class names, URLs, hosts or ports in bodies;
  - build JSON by hand instead of using `ProblemDetail`.

**T8. Identity header.**
- Area: set `X-User-Id` from `sub` on protected requests, and strip client-supplied `X-User-Id` (any letter case) on every request (D4).
- ACs: AC-3.1, AC-3.2, AC-3.3.
- Done when: those tests pass.
- **Allowed files:**
  - create the identity-header filter in `.../filter/`;
  - modify the security configuration in `.../configuration/`, only to register or order the filter;
  - create tests in `api-gateway/src/test/java/org/apigateway/filter/`.
- **Must not:**
  - set `X-User-Id` on public operations;
  - read or trust an incoming `X-User-Id`;
  - parse the token a second time when the validated principal is already available.

**T9. CORS.**
- Area: CORS policy per D5, with origins from `CORS_ALLOWED_ORIGINS` and the dev-profile defaults. CORS headers also go on gateway-generated errors. Downstream CORS headers are deduplicated or overridden.
- ACs: AC-6.1 through AC-6.6.
- Done when: those tests pass.
- **Allowed files:**
  - create the CORS properties record and the `CorsConfigurationSource` configuration in `.../configuration/`;
  - create one class in `.../configuration/` that writes the refused-CORS 403 response (D5/D6). It reuses the T7 Problem Details code in `.../exception/` rather than duplicating it;
  - modify the security configuration to use it;
  - modify `docs/api/api-gateway.openapi.yaml` (created in T2) to add the refused-CORS 403 response to the CORS policy and the error table;
  - modify `application.yaml` (the `CORS_ALLOWED_ORIGINS` binding) and `application-dev.yaml` (the dev origins);
  - create tests in `api-gateway/src/test/java/org/apigateway/configuration/`.
- **Must not:**
  - use wildcard origins or origin patterns;
  - allow credentials;
  - add `@CrossOrigin` anywhere;
  - hard-code origins in Java.

**T10. Access log.**
- Area: one access entry per request, with the fields and redactions in D7, including the invitation-token masking.
- ACs: AC-7.4, AC-7.5.
- Done when: those tests pass (use `OutputCaptureExtension`).
- **Allowed files:**
  - create the access log filter in `.../filter/`;
  - modify the T5 filter-registration class (if there is one), only to order the new filter;
  - modify `application.yaml` only to set the access logger's level;
  - create tests in `api-gateway/src/test/java/org/apigateway/filter/`.
- **Must not:**
  - log bodies, query strings, `Authorization` values or other credentials;
  - use `System.out`;
  - write more than one entry per request.

**T11. Actuator.**
- Area: expose only `health`, with details never shown and no downstream checks. Health is public. `/actuator/gateway/**` and every other endpoint are not exposed.
- ACs: AC-8.4, AC-8.5.
- Done when: those tests pass.
- **Allowed files:**
  - modify `api-gateway/src/main/resources/application.yaml` (management endpoint exposure);
  - modify the security configuration, only if `GET /actuator/health` isn't already public after T6;
  - create tests in `api-gateway/src/test/java/org/apigateway/`.
- **Must not:**
  - expose any endpoint other than `health`;
  - show health details or components;
  - add downstream health indicators.

**T12. Local end-to-end check and run instructions.**
- File: `api-gateway/README.md`. Cover: running auth-service and the gateway locally with the `dev` profile, the port plan, the D3 settings table, how to generate a production `JWT_SECRET`, and the manual steps for AC-1.6, AC-2.8 and AC-8.6.
- ACs: AC-1.6, AC-2.8, AC-8.6 (manual).
- Done when: `cd api-gateway && ./gradlew build` passes, and you've run the manual steps and recorded the results (status codes and the access log line for AC-2.8) in your handoff.
- **Allowed files:** create `api-gateway/README.md`.
- **Must not:**
  - change code or config to make a manual check pass (report the failure instead);
  - fix auth-service defects;
  - put a real secret in the README.

### Fix round after QA (T13–T19, approved by the user on 2026-10-12)

These tasks fix the phase-1 bugs from `docs/qa/api-gateway-report.md` (see the triage under Acceptance). They can start now. Guardrails G1–G8 and P1–P8 still apply, and so do these rules:

- **F1. qa's tests are read-only.**
  - Don't edit, move, rename, disable or import from anything under `api-gateway/src/test/java/org/apigateway/qa/`.
  - "Passes unmodified" means you didn't touch the file.
  - If you believe a qa test contradicts this spec, stop and report the test, the AC or decision it contradicts, and your evidence. Don't work around it.
- **F2. Build state.**
  - qa applies D10 first. You may start T13 before that.
  - At the end of each task, `cd api-gateway && ./gradlew build` may fail only on:
    - (a) qa reproductions of bugs that a later task fixes, as listed in each task;
    - (b) the tests D10 names, until qa has applied it.
  - From T16 on, only (b) is allowed. T19 ends with a fully green `./gradlew clean build`.
  - Each handoff reports the total, failed and skipped test counts, and the name of every test still failing.
- **F3. Tests for new behaviour.**
  - Tag them with the new AC ID (`@Tag("AC-x.y")`) and put them in backend-swe's packages, not in `qa/`.
  - Never tag a test `BUG-api-gateway-n`; those tags are qa's.
- **F4. Order and handoffs.**
  - Do T13 first, then the rest in order.
  - You may do several tasks in one run, but report on each task separately: status, files, test counts, and `git status --short` and `git diff --stat` (G7).
  - Note that `git diff` doesn't show untracked files, which include `application.yaml`. List the changed lines of untracked files in the handoff.

**T13. BUG-1: the base configuration activates no profile.** Prerequisite: none. Do it first.
- Bug and ACs: BUG-api-gateway-1 (S1); AC-6.5, AC-8.3. Approved item 6 under D1.
- Change:
  - Delete the `profiles:` key and its `active: dev` line under `spring:` in `api-gateway/src/main/resources/application.yaml`.
  - In the "Run locally" section of `api-gateway/README.md`, add that the `dev` profile can also be set as "Active profiles: dev" in an IntelliJ IDEA Spring Boot run configuration, or with `SPRING_PROFILES_ACTIVE=dev`, and that it must never be set in `application.yaml`.
- Done when:
  - qa's `QaConfigurationTest#theBaseConfigurationActivatesNoProfile` passes unmodified;
  - backend-swe's `JwtSecretStartupTest` ("JWT_SECRET not set") and `CorsAllowedOriginsTest` (row 4) pass;
  - `./gradlew build` meets F2. qa reproductions still allowed to fail: BUG-2, BUG-4, BUG-5, BUG-7.
- **Allowed files:**
  - modify `api-gateway/src/main/resources/application.yaml` (delete those two lines only);
  - modify `api-gateway/README.md` ("Run locally" section only).
- **Must not:**
  - set or default a profile any other way, for example with `spring.profiles.default`, `spring.profiles.include`, profile groups, `spring.config.import`, `@Profile` or test configuration;
  - touch `application-dev.yaml` or any other key;
  - give `JWT_SECRET` a default;
  - change any test.

**T14. BUG-2: mask invitation tokens in every path form.**
- Bug and ACs: BUG-api-gateway-2 (S3); AC-7.7 (new). AC-7.5 must still pass. Also covers AC-7.8 (new), which is expected to pass without a code change because the D7 decision describes current behaviour.
- Done when:
  - qa's `QaLeakageTest#invitationTokensInNonCanonicalPathsAreNotLogged` (5 rows) passes unmodified, and every other `QaLeakageTest` and `QaAccessLogTest` test still passes;
  - new tests tagged `AC-7.7` and `AC-7.8` pass. If AC-7.8 needs a code change, make it in `AccessLogFilter` only;
  - `./gradlew build` meets F2. Still allowed to fail: BUG-4, BUG-5, BUG-7.
- **Allowed files:**
  - modify `api-gateway/src/main/java/org/apigateway/filter/AccessLogFilter.java`;
  - modify `api-gateway/src/test/java/org/apigateway/filter/AccessLogFilterTest.java`;
  - create at most one test class in `api-gateway/src/test/java/org/apigateway/filter/`, only if AC-7.8 needs a different test configuration (for example a 2 s response timeout).
- **Must not:**
  - log a raw path anywhere;
  - change the access entry's other fields or its format;
  - change which requests are public, or how paths are matched, routed or rejected;
  - start rejecting requests the gateway forwards today.

**T15. BUG-4 and BUG-7: classify downstream response failures. Also add tests for AC-4.8 (BUG-3, closed by decision).**
- Bugs and ACs:
  - BUG-api-gateway-4 (S3) and BUG-api-gateway-7 (S4); AC-4.6 and AC-4.7 (new).
  - AC-4.8 (new): tests only. No code change is expected, because D6 now allows the single GET/HEAD replay.
  - AC-4.2, AC-4.3, AC-4.5, AC-5.1 and AC-5.2 must still pass.
- Done when:
  - qa's `QaDownstreamRobustnessTest#aBodyCutOffMidWayIsNotSuccess`, `#aSlowBodyIsNotAnInternalError` and `#anInvalidStatusCodeIs502` pass unmodified;
  - new tests tagged `AC-4.6`, `AC-4.7` and `AC-4.8` pass;
  - downstream failures are logged at WARN, with no stack trace and no downstream URL (D6);
  - `./gradlew build` meets F2. Still allowed to fail: BUG-5.
- **Stop conditions** (report what you verified, per P8):
  - BUG-7: if mapping an invalid status code to 502 needs more than a supported extension point of Spring Framework or Spring Cloud Gateway Server MVC (for example, replacing the gateway's proxy handler), stop. pm will then defer BUG-7.
  - AC-4.8: if the PUT, PATCH or DELETE row fails because the HTTP client replays it, stop. Don't swap or reconfigure the HTTP client library.
- **Allowed files:**
  - modify `api-gateway/src/main/java/org/apigateway/exception/GatewayExceptionHandler.java` and `api-gateway/src/main/java/org/apigateway/exception/DownstreamFailure.java`;
  - create at most one class in `api-gateway/src/main/java/org/apigateway/exception/` or `.../configuration/`, if a framework extension point has to be registered. Name the class and the extension point in your handoff;
  - modify `api-gateway/src/test/java/org/apigateway/exception/DownstreamFailureTest.java`, `GatewayExceptionHandlerTest.java`, `DownstreamTimeoutTest.java` and `GatewayErrorResponseTest.java`;
  - create at most one test class in `api-gateway/src/test/java/org/apigateway/exception/`;
  - modify `api-gateway/src/test/java/org/apigateway/support/StubServer.java` and `StubResponse.java` to add stub modes (truncated body, slow body, invalid status line). If the JDK `HttpServer` can't produce one of these, you may create one raw-socket stub class in `support/` instead.
- **Must not:**
  - change the status, headers or body of a complete downstream response that has a valid status;
  - retry or replay;
  - add, swap or reconfigure the HTTP client library, or set JVM-wide system properties;
  - change timeout values or defaults;
  - put exception messages, class names, URLs, hosts or ports in bodies or logs;
  - catch `Exception` broadly outside the existing unexpected-failure handler.

**T16. BUG-5: form-encoded bodies stay in the body.**
- Bug and ACs: BUG-api-gateway-5 (S3); AC-1.7 (new). AC-1.1 to AC-1.4 must still pass.
- Done when:
  - qa's `QaRequestFidelityTest#formEncodedBodiesStayInTheBody` (3 rows) and `#theAc13RequestWithAFormContentTypeKeepsItsBody` pass unmodified, and `#formEncodedPostBodyKeepsItsContent` still passes;
  - new tests tagged `AC-1.7` pass;
  - `./gradlew build` meets F2 with no qa reproduction failing, apart from D10's tests if qa hasn't applied it yet.
- Prefer configuration (P1). If code is needed, put it in one class.
- **Allowed files:**
  - modify `api-gateway/src/main/resources/application.yaml` (request-handling settings only);
  - create at most one class in `api-gateway/src/main/java/org/apigateway/configuration/` or `.../filter/`, only if configuration can't do the job;
  - modify `api-gateway/src/test/java/org/apigateway/RoutingTest.java` (AC-1.7 tests).
- **Must not:**
  - parse or re-serialise request bodies in gateway code;
  - move any value between the body, the query string and the path;
  - change how JSON or multipart bodies are handled;
  - work on BUG-8, which is deferred (FU-2). If your fix happens to make a BUG-8 test pass, say so in your handoff, and qa will re-enable it (D10).

**T17. BUG-10: exactly one request ID on every response.**
- Bug and ACs: BUG-api-gateway-10 (S4); AC-7.6 (new). AC-7.1 to AC-7.3 and AC-6.6 must still pass.
- Done when:
  - new tests tagged `AC-7.6` pass;
  - the AC-7.1 and AC-7.3 tests in `RequestIdFilterTest` assert on all `X-Request-Id` values, not only the first (qa's test weakness 4);
  - `./gradlew build` meets F2.
- **Allowed files:**
  - create one class in `api-gateway/src/main/java/org/apigateway/configuration/` or `.../filter/` that drops `X-Request-Id` (any letter case) from downstream responses;
  - modify `api-gateway/src/test/java/org/apigateway/filter/RequestIdFilterTest.java`.
- **Must not:**
  - change how the request ID is handled on the request side;
  - drop any other downstream header;
  - modify `CorsPolicyConfiguration` (P4: one class per concern).

**T18. BUG-11 and BUG-12: reject ambiguous credentials and unsafe subjects.**
- Bugs and ACs: BUG-api-gateway-11 and BUG-api-gateway-12 (S4); AC-2.9 and AC-2.10 (new). AC-2.1 to AC-2.7 and AC-3.1 to AC-3.3 must still pass.
- Done when:
  - new tests tagged `AC-2.9` and `AC-2.10` pass. The AC-2.10 tests assert the access entry, and assert that there's no 500 and no ERROR entry;
  - every qa test in `QaTokenForgeryTest` and `QaHeaderAbuseTest` still passes;
  - `./gradlew build` meets F2.
- **Allowed files:**
  - modify `api-gateway/src/main/java/org/apigateway/security/JwtAuthenticationFilter.java` and `api-gateway/src/main/java/org/apigateway/security/JwtTokenValidator.java`;
  - modify `api-gateway/src/test/java/org/apigateway/security/JwtAuthenticationTest.java` and `JwtTokenValidatorTest.java`;
  - modify `api-gateway/src/test/java/org/apigateway/support/TestTokens.java` (a token with a chosen `sub`).
- **Must not:**
  - evaluate tokens, or count `Authorization` headers, on public operations;
  - change the case-insensitive scheme matching;
  - modify or drop the forwarded `Authorization` header;
  - log a rejected `sub` or any token;
  - restrict `sub` beyond the D4 rule (for example, to UUIDs).

**T19. Contract update, BUG-13, and the final build.**
- Bug and decisions: BUG-api-gateway-13 (S4, contract only). This task also documents the 2026-10-12 decisions (D2 and D4–D7) and the new ACs.
- Changes to `docs/api/api-gateway.openapi.yaml`:
  1. `GET /actuator/health` 200: document both media types, `application/json` (when the client asks for it) and `application/vnd.spring-boot.actuator.v3+json` (otherwise), with the same `Health` schema (BUG-13).
  2. Authentication section and `bearerAuth`:
     - the `sub` format (1 or more printable ASCII characters, U+0021–U+007E);
     - more than one `Authorization` header on a protected operation gets 401;
     - the scheme is matched case-insensitively, but clients must send `Bearer` because auth-service requires it.
  3. Headers table: the gateway → client `X-Request-Id` is exactly one header with the gateway's value. Any downstream `X-Request-Id` is dropped, so services must not rely on setting it.
  4. Errors:
     - reword the 502 and 504 rows to match D6;
     - state that the response timeout covers the whole exchange, body included;
     - replace "The gateway never retries a request…" with the D6 replay rule;
     - in the `Problem.status` description, note the phase-1 deviations: 400 and 405 bodies (FU-3); container-level rejections, which carry no `X-Request-Id`; and `/error` and the health sub-paths (FU-1).
  5. Routes: add the D2 forwarding-fidelity rules, the phase-1 deviations (FU-2), and the security response headers the gateway adds when a response lacks them.
  6. CORS: a preflight from an allowed origin that asks for a disallowed method or header gets the same 403 response.
  7. Set `info.version` to `0.2.0`.
- Done when:
  - the file parses as OpenAPI 3.1 (name the validator, or say none was available);
  - every item above is present;
  - `cd api-gateway && ./gradlew clean build` is fully green, with only the D10 tests skipped. Report the test counts. This needs qa to have applied D10; if it hasn't, report that rather than working around it.
- **Allowed files:** modify `docs/api/api-gateway.openapi.yaml`.
- **Must not:**
  - touch code or config;
  - document behaviour that isn't in this spec. Raise any gaps with pm.

## Contracts needed before parallel work

Only backend-swe is active in this phase, and no frontend exists. These contracts are what later frontend and service work depends on.

1. **C1. Edge contract** `docs/api/api-gateway.openapi.yaml` (T2, backend-swe). It must exist before frontend-swe starts any API integration. It doesn't block backend tasks.
2. **C2. JWT contract** (agreed in D4, recorded in C1): HS512; a shared secret that is standard Base64 of at least 64 bytes; the env var `JWT_SECRET`; the required claims `sub` and `exp`; the informational claims `email`, `orgs` (`[{orgId, role}]`) and `mfa`, as issued by `auth-service/.../service/impl/JwtTokenProvider.java`. auth-service must not change the algorithm, the secret encoding or `sub` without a spec change.
3. **C3. Downstream header contract** (recorded in C1). scenario-service and report-service consume it when they get endpoints.
   - `Authorization` is forwarded unchanged.
   - `X-Request-Id` is always present.
   - `X-User-Id` appears on protected requests and is informational only.
   - The gateway is the only CORS authority.
   - (2026-10-12) The gateway drops any `X-Request-Id` a service sets on its response, so services must not rely on setting it.
   - (2026-10-12) Request bodies arrive byte for byte. Query strings may be re-encoded until FU-2 (D2).
4. **C4. auth-service contract** `docs/api/auth-service.openapi.yaml`. Not needed in this phase because tests use stubs. Recommended as a follow-up before frontend-swe builds login screens; until then, use auth-service's runtime `/v3/api-docs` on port 8081.

## Cross-service dependencies

| Provider | Provides | Consumer | Blocks parallel work? |
|---|---|---|---|
| auth-service | A token format matching C2, signed with `JWT_SECRET` (T3) | gateway | No for automated gateway tests (tests mint their own tokens). Yes for the manual checks AC-1.6 and AC-2.8. |
| auth-service | Listens on 8081 and keeps the `/api/auth/**` and `/api/orgs/**` paths unchanged | gateway | Only the end-to-end checks |
| auth-service | The list of operations that must be public (register, login, accept invitation). A new public endpoint means updating the gateway config and this spec. | gateway | No |
| gateway | The C3 headers and the CORS authority | scenario-service, report-service (later) | No |
| scenario-service, report-service | Final path prefixes (the current ones are provisional) | gateway | No |

### Known issues in other services that affect verification

These are not gateway bugs. qa: don't file them against the gateway.

**Status update (2026-10-13, auth-service FU-9):** items 2–6 are fixed by auth-service phase 1 (`docs/specs/auth-service.md`, accepted 2026-10-12). They are kept below as history.

1. **auth-service couldn't issue tokens with the old secret. Verified by backend-swe, and fixed by T3.**
   - Cause: `JwtTokenProvider.generateToken` calls jjwt 0.11.5 `signWith(SignatureAlgorithm.HS512, String)`, which Base64-decodes the string. The old value `some-secret-key-that-is-long-enough` contains `-`, which isn't standard Base64.
   - Measured before T3: `POST /api/auth/login` failed with `io.jsonwebtoken.io.DecodingException: Illegal base64 character: '-'`. The client saw **403 with an empty body**, not 500, because of item 3.
   - Measured after T3, with the `dev` profile on 8081: register returns 201 and login returns 200 with an `accessToken`.
2. **Fixed by auth-service phase 1.** **`OrganizationController`** used to use `@RequiredArgsConstructor` with non-final fields, so its dependencies are null and `POST /api/orgs` fails in auth-service (500 or 403). This affects the response in AC-2.8 only.
3. **Fixed by auth-service phase 1.** **Verified by backend-swe during T3:** `auth-service/.../configuration/SecurityConfig.java` permits `/api/auth/**` but not `/error`. When auth-service fails on a public endpoint for an anonymous caller, the error is forwarded to `/error`, which anonymous callers can't reach. The client therefore gets **403 with an empty body** instead of the real status (500, 409, 400 …). This was observed on the pre-T3 login failure. The gateway passes this 403 through unchanged (AC-1.4).
   - **qa:** a 403 with an empty body from `/api/auth/**` comes from this auth-service defect, not from the gateway. Gateway-generated errors always have an `application/problem+json` body with `requestId` (D6).
4. **Fixed by auth-service phase 1.** **auth-service `./gradlew build` fails at baseline** because its test is in `org.authservice` while the app is in `com.example.authservice`.
5. **Fixed by auth-service phase 1.** **auth-service `OpenEndpoints`** permits `/actuator/**` and `/h2-console/**`. The gateway doesn't expose them (AC-1.5), but they stay reachable directly on 8081 until network isolation exists.
6. **Fixed by auth-service phase 1.** **(2026-10-12, from BUG-11.)** auth-service's `JwtAuthenticationFilter` accepts only `Bearer ` with that exact letter case (`bearerToken.startsWith("Bearer ")`). The gateway matches the scheme case-insensitively (D4). A client that sends `bearer …` is therefore authenticated at the edge but anonymous at auth-service, which fails closed. This isn't a gateway bug. A fix belongs in an auth-service spec.

## Non-functional requirements

- **Security and authorization**
  - Deny by default. Only the D2 public operations and `GET /actuator/health` need no token.
  - The gateway doesn't authorize. Services keep their role checks.
  - Services keep validating JWTs, and `X-User-Id` must not be used for authorization until a later decision on network isolation.
  - Never log tokens, `Authorization` values, the secret, request or response bodies, query strings, or invitation tokens (AC-7.5).
  - No production secret is committed. Dev-only values live only in `dev` profile files and are labelled.
  - Error bodies carry no internal details (AC-5.2).
  - CORS uses exact origins with no credentials. Only the health endpoint is exposed.
  - **Accepted risk:** HS512 means every holder of the secret can mint tokens, and a token stays valid at the edge until `exp` (24 h today, `app.jwtExpirationInMs`), even if the user is removed. Asymmetric keys and revocation are later work.
- **Performance (informational in phase 1, not an AC).** Measured locally against a stub that answers immediately, the gateway should add no more than 20 ms at p95 over 200 sequential 1 KB requests. backend-swe reports the measured value in the T12 handoff.
- **Reliability.**
  - Connect timeout 2 s and response timeout 10 s, both configurable. The response timeout covers the whole exchange, body included (D6).
  - No replays of timed-out requests, or of any method other than GET and HEAD (D6).
  - One service being down doesn't affect other routes (AC-4.4) or gateway health (AC-8.4).
- **Audit.** The gateway writes no audit records; `AuditLog` stays in auth-service. The access log isn't an audit trail.
- **Observability.** `X-Request-Id` on every request and response, and one access log entry per request (D7).
- **Compatibility.** No path rewriting, no API versioning. Existing auth-service paths work unchanged through the gateway.
- **Accessibility.** Not applicable (no UI).

## Blocking questions

None.

**Resolved (all answered by the user on 2026-10-11):**
- **Q1 (approve D1):** approved as listed, together with changes 1–4 to existing code listed under D1.
- **T6 test edit:** once T6 makes `JWT_SECRET` mandatory (AC-8.3), the existing `contextLoads` test supplies no properties and would fail. Decision: approved. T6 may add a test-only secret to `api-gateway/src/test/java/org/apigateway/ApiGatewayApplicationTests.java` through `@SpringBootTest(properties = …)`, leaving the test method body unchanged. This is change 5 under D1.

**Resolved (answered by the user on 2026-10-12):**
- **BUG-1 (`spring.profiles.active: dev` in `application.yaml`):** the user added the line so they could run the gateway from IntelliJ IDEA, and approved removing it. For local runs they will set "Active profiles: dev" in the IntelliJ IDEA run configuration, or `SPRING_PROFILES_ACTIVE=dev`. This is item 6 under D1, and T13.
- **Fix round:** approved. backend-swe fixes the phase-1 bugs (T13–T19), qa re-verifies, and pm does acceptance.

## Assumptions

- A1. Every client (the frontend, and later CLI or CI plugins) goes through the gateway. Calling services directly is for local development only.
- A2. Tokens travel only in `Authorization: Bearer`, never in cookies or query parameters.
- A3. No clock-skew allowance for `exp`. Tests use tokens that expired 1 hour ago, so the outcome doesn't depend on skew.
- A4. Traffic is development-scale. A servlet gateway on Java 17 platform threads (no virtual threads) is enough for phase 1.
- A5. Downstream services keep their current paths, and the gateway does no rewriting.
- A6. The local profile is named `dev` in both services.
- A7. The frontend dev-server origin isn't known yet. The `dev` profile allows ports 3000 and 5173, and this changes with config only.
- A8. Placeholder prefixes (`/api/scenarios/**`, `/api/reports/**`) may be renamed by later specs without counting as a breaking change.
- A9. An unauthenticated request to an unknown path gets 401, not 404. Authentication is checked before route matching, so route existence isn't revealed to anonymous callers.
- A10. Problem Details applies to gateway-generated errors only. Adopting it platform-wide is a separate decision.
- A11. Connect-timeout handling (an unreachable host mapped to 503) has no automated AC, because black-holed addresses are unreliable in tests. Refused connections (AC-4.1) cover the 503 path.
- A12. (Accepted risk, 2026-10-12.) T15's 502/504 classification runs inside a Spring Cloud Gateway Server MVC response-header filter. It reads request state through `RequestContextHolder`. That depends on SCG running the proxy exchange and its header filters on the servlet request thread, which SCG doesn't document as a contract. If a future Spring Cloud version breaks this, every proxied response fails. AC-4.6 and AC-4.7 are the tripwire: run the full api-gateway build on every Spring Cloud upgrade. If it ever breaks, move the logic to an SCG Server MVC filter function.

## Acceptance

### 2026-10-12, after the fix round: Accepted with follow-ups

- **Input:**
  - the section "Re-verification (fix round), 2026-10-12" in `docs/qa/api-gateway-report.md`, verdict **Signed off with known issues**;
  - `docs/qa/api-gateway-test-plan.md` (TF-11 to TF-14).
  - The tree tested was `aa2234c` plus the working tree as of 2026-10-12 12:56 (+05:00), with T13–T19 applied.
- **Decision: Accepted with follow-ups.** The rule:
  - every P0 AC passes;
  - every AC has a result;
  - there's no open S1 or S2 bug;
  - only S3 and S4 bugs remain.
- **Results:**
  - All 50 ACs pass: P0 12/12, P1 32/32, P2 6/6. That includes the manual AC-1.6, AC-2.8 and AC-8.6, run with the explicit `dev` profile, and AC-8.3 on the boot jar.
  - `cd api-gateway && ./gradlew clean build`: 411 tests, 0 failed, 4 skipped. The skipped tests are exactly D10's.
  - qa's mutation check: every new AC has a backend-swe test that fails when its fix is removed.
- **Bugs:**
  - Closed: BUG-1, BUG-2, BUG-4, BUG-5, BUG-7, BUG-10, BUG-11, BUG-12 and BUG-13.
  - Closed by decision: BUG-3 (D6 replay rule) and BUG-15 (D7 narrowed).
  - Open as follow-ups:

    | Bug | Severity | Follow-up |
    |---|---|---|
    | BUG-14 | S3 | FU-4 |
    | BUG-6 | S4 | FU-1 |
    | BUG-8 | S4 | FU-2 |
    | BUG-9 | S4 | FU-3 |
    | BUG-16 | S4 | FU-6 |
- **Follow-ups** (full detail under Scope, "Follow-ups"):

  | ID | Priority | Summary | Owner |
  |---|---|---|---|
  | FU-4 | **P1** | Abort committed responses (BUG-14), per-route timeouts. Must land before any routed service can return bodies over 8 KB or stream; at the latest before report-service downloads or uploads. **2026-10-29: abort part closed by `docs/specs/platform-hardening.md` (US-6); per-route timeouts stay open, with report-service.** | pm (spec), backend-swe, qa |
  | FU-1 | P2 | `/error` and health sub-paths answer the 404 Problem (BUG-6). **2026-10-29: `/error` part closed by `docs/specs/platform-hardening.md` (AC-5.4); health sub-paths moved to FU-5.** | pm, backend-swe, qa |
  | FU-2 | P2 | Byte-exact forwarding (BUG-8). Before signed URLs or a non-Spring service. | pm, backend-swe, qa |
  | FU-3 | P2 | Problem body for the firewall's 400 and for 405 (BUG-9). **Closed 2026-10-29 by `docs/specs/platform-hardening.md` (US-5).** | pm, backend-swe, qa |
  | FU-5 | P2 | Kubernetes probe groups, with the Helm wiring | pm, backend-swe |
  | FU-6 | P2 | AC-4.9 (gateway errors carry only the gateway's headers, each once), BUG-16, `RawSocketStub` sends `Connection: close` | pm, backend-swe, qa |
  | FU-7 | P2 | Docs only, can be done now: README startup markers (TF-14); contract notes for the D6 header rule and the BUG-14 deviation; contract 0.2.1 | backend-swe |
- **Accepted risk:** A12 (`RequestContextHolder` inside an SCG header filter).
- **Spec changes made at acceptance:**
  - D6: the header rule, and BUG-14 recorded as a known deviation;
  - D7: masking narrowed;
  - D10: no new disabled tests;
  - D11: decisions added;
  - FU-4 extended, FU-6 and FU-7 added;
  - A12 added.

  No AC was added or changed, so all 50 results still apply.
- **Superseded:** the "Changes requested" entry below. It's kept as history.

### 2026-10-12: Changes requested (superseded by the entry above)

- **Input:** `docs/qa/api-gateway-report.md` (verdict: Not signed off) and `docs/qa/api-gateway-test-plan.md`. Tested on `aa2234c` plus the working tree as of 2026-10-12 10:11 (+05:00).
- **Decision: Changes requested.** The rule doesn't allow acceptance while an S1 bug is open and P1 ACs fail.
- **Results:** all 41 ACs have a result. 39 pass and 2 fail.
  - All 12 P0 ACs pass, including the manual AC-1.6, AC-2.8 and AC-8.6.
  - The measured p95 overhead is 2.5–2.9 ms, against the 20 ms NFR.
- **Failing ACs:** AC-6.5 (P1) and AC-8.3 (P1). Both are caused by BUG-1. They passed on the tree before the `spring.profiles.active: dev` line was added.
- **Open bugs:**
  - S1: BUG-1;
  - S3: BUG-2, BUG-3, BUG-4, BUG-5;
  - S4: BUG-6 to BUG-13.
- **Who acts, in order:**
  1. **qa:** apply D10 now.
  2. **backend-swe:** T13 (BUG-1) first, then T14–T19.
  3. **qa:** re-verify using the scope below, then update the report and the test plan.
  4. **pm:** final acceptance.
- **Expected outcome:** if every AC passes after T13–T19, and only the deferred S4 bugs (BUG-6, BUG-8, BUG-9) remain open, the decision will be "Accept with follow-ups" (FU-1 to FU-5).

#### Triage of qa's bugs

| Bug | Sev | Decision | Task or follow-up | Reason |
|---|---|---|---|---|
| BUG-1 | S1 | Phase 1. Removal approved by the user on 2026-10-12. | T13 (first) | Authentication bypass whenever `JWT_SECRET` is missing. Fails AC-6.5 and AC-8.3. |
| BUG-2 | S3 | Phase 1 | T14 | Invitation tokens, which grant org membership, leak into logs. Breaks D7. |
| BUG-3 | S3 | Closed by decision (D6 replay rule amended). No code fix. | T15 adds AC-4.8 tests. qa rewrites its test (D10). | Replaying a GET or HEAD once is standard and avoids spurious 502s. Writes and timed-out requests are still never replayed. |
| BUG-4 | S3 | Phase 1 | T15 | Downstream failures give 500 and an ERROR stack trace. Breaks D6. |
| BUG-5 | S3 | Phase 1 | T16 | Form values, possibly credentials, move into downstream URLs and logs. Breaks D2. |
| BUG-6 | S4 | Follow-up | FU-1; tests disabled (D10) | Nothing leaks, and the health sub-path status is already right. The probes are revisited with the Helm wiring. |
| BUG-7 | S4 | Phase 1, with a stop condition | T15 | Same D6 "invalid response → 502" rule and the same code as BUG-4, so it's cheap to fix alongside. |
| BUG-8 | S4 | Follow-up | FU-2; tests disabled (D10) | Semantically equivalent for current services. qa's evidence (re-ordered, re-encoded parameters) suggests the query is rebuilt from parsed parameters, so a fix may go beyond configuration. |
| BUG-9 | S4 | Follow-up for the gateway-level 400 and 405 bodies. Container-level rejections are out of scope. | FU-3; no test to disable | The statuses are right. Only the body shape differs. |
| BUG-10 | S4 | Phase 1 | T17 | Breaks the contract's single request ID, and browsers would read a joined value. Cheap. |
| BUG-11 | S4 | Phase 1 | T18 | The gateway and a service could check different credentials. Security-relevant and cheap. |
| BUG-12 | S4 | Phase 1 | T18 | Header and log injection through `sub`. Security-relevant, cheap, and in the same code as BUG-11. |
| BUG-13 | S4 | Phase 1 (contract only) | T19 | Documentation fix with no code change. |

#### Re-verification scope for qa

- `cd api-gateway && ./gradlew clean build` on the final tree must be fully green, with only the D10 tests skipped.
- Every AC, 50 in total:
  - the original 41. AC-1.3 now states the JSON content type, and AC-4.3's title now says "timed-out"; their steps are otherwise unchanged;
  - the 9 new ones: AC-1.7, AC-2.9, AC-2.10, AC-4.6, AC-4.7, AC-4.8, AC-7.6, AC-7.7, AC-7.8.
- Bugs:
  - Close BUG-1, BUG-2, BUG-4, BUG-5, BUG-7, BUG-10, BUG-11, BUG-12 and BUG-13 if their ACs or contract items pass.
  - BUG-3 is closed by decision.
  - BUG-6, BUG-8 and BUG-9 stay open as deferred (FU-1 to FU-3).
- Manual checks:
  - T13 changes `application.yaml`, so re-run AC-8.6, AC-1.6 and AC-2.8 once on the final tree, with the `dev` profile set explicitly as in the README.
  - Also confirm on the boot jar that a start with no profile and no `JWT_SECRET` fails (AC-8.3).
- In the test plan, record the D11 decisions against TF-1 to TF-10.
