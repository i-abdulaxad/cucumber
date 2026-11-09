# auth-service phase 1: make accounts, organizations and members correct and usable

Status: Accepted (with follow-ups FU-1 to FU-9; fix round T16–T21 done; final, 2026-10-12)
Last updated: 2026-10-12

## Problem and goal

auth-service is the only service with real code. It owns users, organizations, memberships, invitations and (later)
projects. The gateway in front of it is accepted (`docs/specs/api-gateway.md`). At `aa2234c` plus the staged gateway
work, auth-service is not usable:

- `cd auth-service && ./gradlew build` fails. The only test is in package `org.authservice`, but the app is in
  `com.example.authservice`.
- Every error reaches anonymous callers as **403 with an empty body**, because `/error` isn't permitted and there's no
  error handler. Validation errors, a duplicate email and a wrong password all look the same.
- Creating an organization fails: `OrganizationController` has non-final fields, so its dependencies are null (NPE,
  verified by qa during gateway work).
- Organization authorization never works. `OrgGuard` expects a principal type the filter never sets, reads the wrong
  claim key, and compares roles in the wrong direction.
- Invitations can't be used end to end. The token is never returned or sent, anyone with a token can accept it with any
  account, and seat limits aren't checked.
- Listing organizations, changing a member's role and removing a member are empty stubs.

**Goal of phase 1:** make what exists correct, safe and complete for organizations and their members, with one error
format and a green, tested build. There are no new product areas (projects, email, MFA) in this phase.

**Success:** through `http://localhost:8080` (the gateway), a person can register, log in, create an organization,
invite a colleague, the colleague can accept, and an admin can change the colleague's role or remove them. Every error
is an RFC 9457 Problem with a stable `code`, and every P0 acceptance criterion passes.

## Users and roles affected

| Persona                                                 | Need                                                                                      |
|---------------------------------------------------------|-------------------------------------------------------------------------------------------|
| Organization owner (`OWNER`)                            | Create an organization, invite people, manage every member, hand over ownership           |
| Organization admin (`ADMIN`)                            | Invite people and manage members up to their own role                                     |
| Organization member / reader (`MEMBER`, `READER`)       | See their organizations and the member list, leave an organization                        |
| Invited person (no account yet, or an existing account) | Join an organization from an invitation token                                             |
| Frontend developer (not in the repo yet)                | One error shape with a machine-readable `code`, a written contract                        |
| Operator                                                | A service that starts with a valid secret only, and doesn't expose dev tooling by default |

**Roles.** No new roles. Organization roles `OWNER`, `ADMIN`, `MEMBER`, `READER` (
`auth-service/.../enums/OrganizationRole.java`) get an explicit rank: `OWNER` > `ADMIN` > `MEMBER` > `READER` (D3).
Project roles (`ADMIN`, `MEMBER`, `TESTER`, `READER`) aren't used in phase 1.

## Glossary

| Term              | Meaning in this spec                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
|-------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Member            | A user with an `OrgMembership` row in the organization. Exactly one role per organization.                                                                                                                                                                                                                                                                                                                                                                                            |
| Role rank         | `OWNER` 4, `ADMIN` 3, `MEMBER` 2, `READER` 1. "At least `ADMIN`" means rank ≥ 3. Never derived from the enum's declaration order.                                                                                                                                                                                                                                                                                                                                                     |
| Role ceiling      | An actor can only grant, change or remove roles whose rank is at most the actor's own rank (D11).                                                                                                                                                                                                                                                                                                                                                                                     |
| Seat              | One place in an organization. Seats used = members + pending, unexpired organization invitations (D11).                                                                                                                                                                                                                                                                                                                                                                               |
| Seat limit        | `Organization.seatLimit`. `null` means unlimited. Organizations created in phase 1 are on plan `FREE` with the configured free seat limit (default 5).                                                                                                                                                                                                                                                                                                                                |
| Invitation        | A pending offer to join one organization with one role, for one email address. It has an acceptance token and expires 7 days after creation. Statuses: `PENDING`, `ACCEPTED`, `REVOKED`, `EXPIRED` (expiry is computed from `expiresAt`; the stored status may still say `PENDING`).                                                                                                                                                                                                  |
| Acceptance token  | The secret string in `POST /api/auth/invitations/{token}/accept`. Returned once, to the inviter, in the create-invitation response (D11).                                                                                                                                                                                                                                                                                                                                             |
| Problem           | An RFC 9457 Problem Details body (`application/problem+json`) with `type` `about:blank`, `title` (the HTTP reason phrase), `status`, a non-empty `detail`, `instance` (the request path without the query string), and the extension member `code` (D2). Validation Problems also have `errors`.                                                                                                                                                                                      |
| "a Problem S / C" | In scenarios: status S, `Content-Type: application/problem+json`, a Problem body with `status` S and `code` C, and no stack trace, exception class name, SQL or internal host or port anywhere in the body.                                                                                                                                                                                                                                                                           |
| Acme fixture      | Unless a scenario says otherwise: users `ana@example.com` (Ana Lima), `ben@example.com` (Ben Ode), `cai@example.com` (Cai Wu), `dan@example.com` (Dan Roe) and `eve@example.com` (Eve Park), all with password `Secret123!` and status `PENDING_VERIFICATION`. Organization "Acme QA" (`{acme}`), plan `FREE`, seat limit 5, members Ana `OWNER`, Ben `ADMIN`, Cai `MEMBER`, Dan `READER`. Eve belongs to no organization. `fay@example.com` has no account. Acme has no invitations. |
| `{token of X}`    | The `accessToken` returned by `POST /api/auth/login` for user X after the scenario's Given steps.                                                                                                                                                                                                                                                                                                                                                                                     |
| `{X.id}`          | User X's id. `{unknown id}` is `3b9d5c1e-0000-4000-8000-0000000000ff`, which matches no row.                                                                                                                                                                                                                                                                                                                                                                                          |
| Seeded            | Inserted directly into the database by the test (through repositories), for states the API can't produce in phase 1: a suspended user, an expired invitation, a second owner.                                                                                                                                                                                                                                                                                                         |
| Direct call       | A request sent to auth-service on port 8081, not through the gateway. Used for 401 scenarios, because through the gateway the gateway's own 401 answers first (see Known issues).                                                                                                                                                                                                                                                                                                     |

## Scope

### In scope

- A green build and an honest test suite (T1).
- One error format: RFC 9457 Problem Details with a `code`, for every error auth-service produces (D2, US-1).
- Correct authentication: HS512-only JWT validation, 401 instead of 403, user status respected, exactly three public
  operations (D4, D7, US-2, US-3).
- Organization authorization from the database with explicit role ranks (D3).
- Organizations: create, list mine, view one (US-4).
- Invitations: create (with seat limit and role ceiling), list pending, revoke (US-5); accept as a new or existing
  user (US-6).
- Members: list, change role, remove, leave (US-7, US-8).
- Email normalisation (lower-case) for register, login, invitations (D12).
- Removing unused dependencies, and optionally upgrading jjwt to 0.12.5 (D5, approval needed).
- Fixing the misplaced JPA configuration keys (D6).
- Contract `docs/api/auth-service.openapi.yaml` (D9, the gateway spec's C4 follow-up).
- `auth-service/README.md` with run instructions and the manual end-to-end checks.

### Out of scope (later phases, each with its own spec)

- **Phase 2 `auth-service-projects`:** the project API, project memberships and invitations, `ProjectGuard` and
  `ProjectRoleService` fixes, the project role ceiling under the org role, routing `/api/projects/**` at the gateway.
- **Phase 3 `auth-service-accounts`:** email verification (and what `PENDING_VERIFICATION` blocks), password reset,
  email delivery of invitations, refresh tokens, logout and revocation, MFA, suspending users through the API. Several
  of these add public operations, which need a gateway config and spec change.
- **Phase 4 `auth-service-persistence`:** PostgreSQL, Flyway migrations, `ddl-auto: validate`, `Instant`/UTC timestamps.
- Audit log writing and reading (D8). Decide in phase 2.
- Renaming or deleting an organization, changing its plan or seat limit, billing.
- Pagination of lists (organizations, members, invitations are small in phase 1).
- A `GET /api/auth/me` endpoint.
- Caching of organization roles.
- Rate limiting and brute-force protection on login.
- Asymmetric token signing (accepted risk carried over from the gateway spec).
- Platform-wide adoption of the `code` member by other services (they don't exist yet).
- **Entity defects found by the ERD (`docs/erd/cucumber.dbml`), routing approved by the user on 2026-10-12.** None of
  them changes phase 1.
    - **Phase 2 `auth-service-projects`:**
        - `invitations.role` is free text holding either an org or a project role. `org_id` and `project_id` are both
          nullable, and nothing checks that exactly one is set.
        - `projects.secret_token` is plain text, nullable and not unique, and its purpose is unspecified.
    - **Phase 4 `auth-service-persistence`:**
        - Nullable business columns: `organizations.name` and `plan`, membership `role`s, `users.status` and
          `full_name`, `projects.name`, `audit_log.action`; `invitations.email`, `status`, `expires_at` and `token`.
        - Every `created_at` is set by hand; `users.updated_at` is never updated; `organizations` has no `updated_at`.
        - `audit_log.metadata` is a String in a `json` column (breaks on PostgreSQL, double-encoded on H2).
        - `LocalDateTime` timestamps without a zone.
        - `users.email` uniqueness is case-sensitive in the database.
        - "One pending invitation per org and email" exists only in the application.
        - Enum DDL differs between H2 and PostgreSQL.
        - `audit_log` table name is singular.
        - No explicit indexes on foreign-key columns, and no ON DELETE rules.

### Follow-ups (decided at acceptance, 2026-10-12)

Each follow-up needs its own spec, or a section in the named phase's spec, before work starts. None of them blocks phase
1.

| ID   | Priority and gate                                                                                                    | Follow-up                                    | Source                                 | Target behaviour                                                                                                                                                                                                                                                                                                                                                                                               | Owner                              |
|------|----------------------------------------------------------------------------------------------------------------------|----------------------------------------------|----------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------|
| FU-1 | P2. Do it together with the gateway's FU-3, so that both services treat rejections before MVC the same way.          | Problem for errors raised outside Spring MVC | BUG-1, TF-1, Q7                        | New AC-1.10, written in the follow-up. Firewall rejections (`;`, `/./`, `%2e`, `//`) are 400 `MALFORMED_REQUEST` Problems; `TRACE` is a 405 `METHOD_NOT_ALLOWED` Problem; a direct `GET /error` is a 404 `NOT_FOUND` Problem, never a 500. Tomcat's encoded-slash (`%2F`) HTML page is out of scope, because it's rejected at container level, like the gateway's D6 rule.                                     | pm (AC), backend-swe, qa           |
| FU-2 | P2, in phase 3 (`auth-service-accounts`)                                                                             | Unicode normalisation of emails              | Q4, TF-4                               | NFC normalisation before lower-casing, so that NFC and NFD spellings are one account. Also decide how dotted `İ` and the Kelvin sign are treated.                                                                                                                                                                                                                                                              | pm (phase 3 spec)                  |
| FU-3 | **P1, gate: before or with the PostgreSQL move (phase 4)**                                                           | Blank and control characters in names        | Q5, TF-5                               | Organization names and `fullName` that are blank after removing Unicode whitespace (including no-break and zero-width spaces) are `VALIDATION_FAILED`. So are names containing control characters (U+0000–U+001F, U+007F). PostgreSQL rejects U+0000, which would otherwise surface as a 500.                                                                                                                  | pm (phase 4 spec), backend-swe, qa |
| FU-4 | P2, with phase 4's move to `Instant`                                                                                 | Injectable `Clock`                           | qa deviation 5, guardrail P9           | New and rewritten time-dependent code gets the time from an injected `java.time.Clock`, and expiry tests control the time instead of seeding past timestamps. **Not approved by the user** for phase 1.                                                                                                                                                                                                        | pm (phase 4 spec), backend-swe     |
| FU-6 | P2. **Gate: before any production use.** Re-check on PostgreSQL in phase 4. (Added at final acceptance, 2026-10-12.) | No personal data in logs                     | BUG-5, Q8                              | No email appears in any log line at any level when a registration or anonymous acceptance race is lost. Proposed E29: set `logging.level.org.hibernate.orm.jdbc.error: ERROR` in `application.yaml`. Its lock-timeout WARN lines contain placeholders only, and `ApiExceptionHandler` already logs the exception type at WARN. New AC in the follow-up: "a lost registration race writes no email to the log". | pm (AC), backend-swe, qa           |
| FU-7 | P2, docs only. (Added at final acceptance, 2026-10-12.)                                                              | Contract wording for role strings            | BUG-6, Q9                              | `docs/api/auth-service.openapi.yaml`, `OrganizationRole`: "leading and trailing ASCII whitespace is ignored; otherwise only these exact strings…". Bump to `1.1.1`. No code change.                                                                                                                                                                                                                            | backend-swe                        |
| FU-8 | **P1, gate: phase 4 (PostgreSQL)**                                                                                   | Re-verify the H2-specific behaviour          | qa fix-round gaps                      | On PostgreSQL, re-verify: the AC-1.9 lock exceptions and SQLStates; the unique-violation classification behind AC-2.11; the email in constraint messages (FU-6); U+0000 (FU-3); enum DDL.                                                                                                                                                                                                                      | pm (phase 4 spec), qa              |
| FU-9 | P2, docs only                                                                                                        | Gateway spec catch-up                        | phase 1 outcome                        | In `docs/specs/api-gateway.md`, "Known issues in other services": mark items 2, 3, 4, 5 and 6 as fixed by auth-service phase 1.                                                                                                                                                                                                                                                                                | pm                                 |
| FU-5 | P2. Needs user approval before anyone starts.                                                                        | Tidy-ups                                     | qa deviations 4 and 6, test weakness 6 | Move `OrganizationProperties` (backend-swe's tidy-up); fix the 3 stale test texts (qa deviation 6); de-duplicate the three `normalizeEmail` copies, which FU-2 will make more important; move tests off the deprecated jjwt API before jjwt 1.0. **Not approved by the user** for phase 1.                                                                                                                     | backend-swe                        |

## Code facts verified (aa2234c plus the staged gateway work, read on 2026-10-12)

pm read all 52 Java files, both YAML files, `build.gradle`, `settings.gradle` and the wrapper properties. The main
session's list is confirmed except where the "Correction or addition" column says otherwise.

| Item                                           | Result                                                                                                                                                                                                                                                                                                   | Correction or addition                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Stack                                          | Confirmed: Boot 4.1.0, Java 17 toolchain, Gradle 9.5.1, independent build (`settings.gradle` has only `rootProject.name`). Packages as listed, including `aop` and `util`.                                                                                                                               | `build.gradle` has `group = 'org'`, unlike the `com.example.authservice` packages. Not changed.                                                                                                                                                                                                                                                                                                                                                                                  |
| Dependencies                                   | Confirmed: jjwt 0.11.5 (`jjwt-impl`/`jjwt-jackson` `runtimeOnly`), Caffeine, springdoc 3.0.3, Okta 3.1.0, oauth2-client, webauthn, elasticsearch, H2, PostgreSQL driver, no Flyway/Liquibase. Test starters: data-jpa-test, elasticsearch-test, security-oauth2-client-test, security-test, webmvc-test. | **There is no actuator dependency**, so the `/actuator/**` permit is dead. `spring-boot-starter-validation` is present, so Bean Validation works. **`OrgGuard` imports `org.springframework.security.oauth2.jwt.Jwt`** (supplied by the OAuth2 dependencies) and Jackson 2 `ObjectMapper`, so removing the OAuth2 dependencies before `OrgGuard` is rewritten breaks compilation. `aop/Logging.java` imports `org.aspectj`, which arrives transitively; see T7's stop condition. |
| Config                                         | Confirmed: port 8081, `app.jwtSecret: ${JWT_SECRET}`, dev secret in `application-dev.yaml`, `hibernate`, `show-sql`, `properties` under `spring.datasource`.                                                                                                                                             | `defer-datasource-initialization` is misplaced too. **Because they're misplaced, all four are ignored today**: the effective schema mode is Boot's embedded default (`create-drop`) and SQL isn't logged. Moving them changes behaviour slightly (D6). H2 console is enabled.                                                                                                                                                                                                    |
| Endpoints                                      | Confirmed.                                                                                                                                                                                                                                                                                               | `SecurityConfig` permits all of **`/api/auth/**`**, which is wider than the gateway's three public operations.                                                                                                                                                                                                                                                                                                                                                                   |
| Build failure                                  | Confirmed (test package mismatch).                                                                                                                                                                                                                                                                       | —                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `OrganizationController` NPE                   | Confirmed (`private OrganizationService`, `private UserRepository`, not final, with `@RequiredArgsConstructor`).                                                                                                                                                                                         | —                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `OrgGuard`                                     | Confirmed: expects a `Jwt` principal (the filter sets `UserPrincipal`), reads `org_id` (`OrgClaim` serialises `orgId`), `ordinal() >=`.                                                                                                                                                                  | With `OWNER` declared first, `>=` means a `READER` passes an `ADMIN` check, if the guard ever got that far.                                                                                                                                                                                                                                                                                                                                                                      |
| `ProjectGuard`                                 | Confirmed: casts the principal to `model.User`.                                                                                                                                                                                                                                                          | Its `@Cacheable` method is self-invoked and has no effect. Not reachable from any endpoint. Deferred to phase 2.                                                                                                                                                                                                                                                                                                                                                                 |
| Stubs                                          | Confirmed: `createOrganization` returns an empty builder; `findByUser` returns `null`; `changeMemberRole`, `removeMember` are empty; seat and ceiling checks are TODOs.                                                                                                                                  | `createOrganization` never sets `seatLimit`.                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| Bare `RuntimeException`                        | Confirmed in `InvitationServiceImpl` (2) and `OrganizationServiceImpl` (1).                                                                                                                                                                                                                              | —                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `/error` not permitted, no `@ControllerAdvice` | Confirmed.                                                                                                                                                                                                                                                                                               | **There's no `AuthenticationEntryPoint` either**, so an anonymous call to a protected endpoint gets 403, not 401. A wrong password raises `BadCredentialsException` inside the controller, which also ends as 403 with an empty body (code reading).                                                                                                                                                                                                                             |
| Filter catch-all                               | Confirmed (`catch (Exception ex)`, logged at ERROR with the stack trace). `JwtTokenProvider.validateToken` also swallows every exception.                                                                                                                                                                | `JwtAuthenticationFilter` is a `@Component`, so Boot also registers it as a plain servlet filter. `OncePerRequestFilter` stops it from running twice. Not changed.                                                                                                                                                                                                                                                                                                               |
| `UserPrincipal.isEnabled()`                    | Confirmed: always `true`. Registration sets `PENDING_VERIFICATION`.                                                                                                                                                                                                                                      | —                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `Bearer` case                                  | Confirmed: `startsWith("Bearer ")`.                                                                                                                                                                                                                                                                      | —                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `OpenEndpoints`                                | Confirmed: `/api/v1/auth/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/**`, `/h2-console/**`.                                                                                                                                                                                                     | —                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `AuditLog`                                     | Nothing writes it: `AuditLogRepository` has no callers.                                                                                                                                                                                                                                                  | `AuditLog` and `Project` have **no getters, setters or constructors** besides the implicit one, so they can't be written or read without changing the entity.                                                                                                                                                                                                                                                                                                                    |
| Project API                                    | Confirmed: none.                                                                                                                                                                                                                                                                                         | —                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| **New: invitation acceptance**                 | —                                                                                                                                                                                                                                                                                                        | `InvitationServiceImpl.acceptInvitation` never checks that the invitation's email matches the logged-in user, so **any account holding a token joins with the invited role**. For a new user, `AuthController` registers the user and then accepts in two transactions, so a failed acceptance leaves an orphan account. Accepting twice as the same user hits the unique constraint (500).                                                                                      |
| **New: invitation delivery**                   | —                                                                                                                                                                                                                                                                                                        | `inviteMember` returns nothing and sends nothing, so nobody ever learns the token.                                                                                                                                                                                                                                                                                                                                                                                               |
| **New: email case**                            | —                                                                                                                                                                                                                                                                                                        | Emails are stored and compared as typed: `Ana@example.com` and `ana@example.com` are two accounts.                                                                                                                                                                                                                                                                                                                                                                               |
| **New: long passwords**                        | —                                                                                                                                                                                                                                                                                                        | Spring Security's `BCryptPasswordEncoder` rejects passwords longer than 72 bytes, and there's no `@Size(max)`, so such a registration fails with a 500 (code reading; verify in T10).                                                                                                                                                                                                                                                                                            |
| **New: JWT algorithm**                         | —                                                                                                                                                                                                                                                                                                        | jjwt 0.11.5's deprecated `parser().setSigningKey(String)` derives the key from the token header's `alg`, so an HS256 or HS384 token signed with the shared secret is probably accepted. The gateway accepts HS512 only (AC-3.1 pins the rule; verify in T3).                                                                                                                                                                                                                     |
| **New: OpenAPI config**                        | —                                                                                                                                                                                                                                                                                                        | `OpenApiConfiguration` sets the scheme type to HTTP and then overwrites it with OAUTH2. This only affects the runtime `/v3/api-docs`. Not changed; `docs/api/auth-service.openapi.yaml` becomes the contract (D9).                                                                                                                                                                                                                                                               |
| Gateway contract                               | Confirmed from `docs/api/api-gateway.openapi.yaml` 0.2.0 and the gateway spec D2, D4, D6, C2, C3.                                                                                                                                                                                                        | The gateway contract says auth-service accepts only `Bearer` and has no standard error body. Both statements become stale in this phase (X1).                                                                                                                                                                                                                                                                                                                                    |

## Decisions (recommended defaults)

Each decision has a recommended default and a one-line reason. The items that change existing code are listed
separately, under "Changes to existing code", for the user's approval.

### D1. Phase scope and slug

**Default:** phase 1 = "make what exists correct and usable" (the In scope list). Slug `auth-service` for this phase.
Later phases use their own slugs (`auth-service-projects`, `auth-service-accounts`, `auth-service-persistence`).
**Why:** every listed defect blocks the organization flow the frontend needs first, and the phase fits 8 stories and one
review cycle per batch.

### D2. Error format: RFC 9457 Problem Details with a `code`

**Default:** every error auth-service produces is a Problem (`application/problem+json`) with `type` `about:blank`,
`title`, `status`, `detail`, `instance`, and the extension member `code` (UPPER_SNAKE_CASE, stable, part of the
contract). Validation errors add `errors: [{"field": "...", "message": "..."}]`. When the request carries an
`X-Request-Id` header matching `^[A-Za-z0-9._-]{1,128}$` (the gateway always sends one), the body also has `requestId`
with that value.
**Why:** the gateway already uses this shape, so the frontend handles one format. `code` lets the UI tell apart two
errors with the same status (for example `SEAT_LIMIT_REACHED` and `ALREADY_MEMBER`, both 409) without parsing text.

Mechanics (binding, see P6):

- One `@RestControllerAdvice`, built on Spring's `ProblemDetail` and `ResponseEntityExceptionHandler`, so Spring MVC's
  own exceptions (malformed JSON, 405, 415, 404 for an unknown path, type mismatch) get the same shape.
- A Problem-writing `AuthenticationEntryPoint` (401, with `WWW-Authenticate: Bearer`) and `AccessDeniedHandler` (403) in
  the security chain.
- `/error` is permitted, so anything that still reaches Boot's error page is never turned into a 403.
- Typed exceptions replace bare `RuntimeException`. The existing `EmailAlreadyExistsException` and
  `OrgRoleCeilingException` are kept unchanged and mapped by the advice.
- Unexpected exceptions: 500, `code` `INTERNAL_ERROR`, a fixed detail, logged once at ERROR with the stack trace.
- auth-service never sets an `X-Request-Id` response header (the gateway drops it anyway) and never emits CORS headers.

Status and code table (the contract, T6, copies it):

| Situation                                                                                                                                  | Status                     | `code`                                                                      |
|--------------------------------------------------------------------------------------------------------------------------------------------|----------------------------|-----------------------------------------------------------------------------|
| Bean Validation failure on a body or parameter                                                                                             | 400                        | `VALIDATION_FAILED` (with `errors`)                                         |
| Unreadable JSON, a wrong enum value, a malformed UUID in the path                                                                          | 400                        | `MALFORMED_REQUEST`                                                         |
| No or invalid credentials on a protected operation                                                                                         | 401                        | `UNAUTHENTICATED`                                                           |
| Wrong email or password, or a suspended account, at login                                                                                  | 401                        | `INVALID_CREDENTIALS`                                                       |
| Authenticated, but not allowed (not a member, role too low)                                                                                | 403                        | `FORBIDDEN`                                                                 |
| Accepting an invitation addressed to another email                                                                                         | 403                        | `INVITATION_EMAIL_MISMATCH`                                                 |
| Unknown path                                                                                                                               | 404                        | `NOT_FOUND`                                                                 |
| Invitation token or id unknown (or in another organization)                                                                                | 404                        | `INVITATION_NOT_FOUND`                                                      |
| Target user isn't a member of the organization                                                                                             | 404                        | `MEMBER_NOT_FOUND`                                                          |
| Method not supported                                                                                                                       | 405                        | `METHOD_NOT_ALLOWED`                                                        |
| Email already registered                                                                                                                   | 409                        | `EMAIL_ALREADY_REGISTERED`                                                  |
| Invitee is already a member                                                                                                                | 409                        | `ALREADY_MEMBER`                                                            |
| A pending, unexpired invitation already exists for that email                                                                              | 409                        | `INVITATION_PENDING`                                                        |
| No free seat                                                                                                                               | 409                        | `SEAT_LIMIT_REACHED`                                                        |
| The last owner tries to leave                                                                                                              | 409                        | `LAST_OWNER`                                                                |
| Revoking an invitation that isn't pending                                                                                                  | 409                        | `INVITATION_NOT_PENDING`                                                    |
| Any other unique-constraint conflict                                                                                                       | 409                        | `CONFLICT`                                                                  |
| Invitation expired / revoked / already accepted (on accept)                                                                                | 410                        | `INVITATION_EXPIRED` / `INVITATION_REVOKED` / `INVITATION_ALREADY_ACCEPTED` |
| Unsupported `Content-Type`                                                                                                                 | 415                        | `UNSUPPORTED_MEDIA_TYPE`                                                    |
| (2026-10-12) No acceptable response media type for the `Accept` header                                                                     | 406                        | `NOT_ACCEPTABLE`                                                            |
| (2026-10-12) Any other 4xx that Spring MVC raises itself and that no row above covers (for example a missing required parameter or header) | Spring's status, unchanged | `MALFORMED_REQUEST`                                                         |
| Role above the actor's own rank (grant, change or remove)                                                                                  | 422                        | `ROLE_CEILING`                                                              |
| Changing your own role                                                                                                                     | 422                        | `SELF_ROLE_CHANGE`                                                          |
| Anything unexpected                                                                                                                        | 500                        | `INTERNAL_ERROR`                                                            |

**Decided after review point A (2026-10-12):**

- **406 and the 4xx fallback.** Both go into this table and the contract (T6), as implemented in T2. **Why:** 406 is a
  distinct client mistake worth its own code. The fallback keeps Spring's real status and never invents a 400, and
  `MALFORMED_REQUEST` is the honest meaning ("the request itself is wrong"). Clients branch on `status` first and on
  `code` second. A new dedicated code needs a spec change. A 406 Problem is written as `application/problem+json` only
  when the client's `Accept` allows it; otherwise the body may be empty, which the contract states.
- **`WWW-Authenticate` on every 401.** Both `UNAUTHENTICATED` and `INVALID_CREDENTIALS` carry
  `WWW-Authenticate: Bearer`, as implemented in T2. **Why:** RFC 9110 §15.5.2 requires a challenge on every 401,
  `Bearer` is the only scheme auth-service supports, and browsers don't show a login dialog for it. The contract
  documents the header on both.

`ROLE_CEILING` stays 422 because the user's existing `OrgRoleCeilingException` already declares that status; it tells
the UI "you may do this action, but not with that role", which differs from a plain 403.

### D3. Authorization model

**Default:**

- Keep the existing pattern: `@PreAuthorize("@orgGuard.…")` on controller methods, with `OrgGuard` as the single place
  that answers "is the caller at least role R in organization O?". Every org endpoint carries an explicit
  `@PreAuthorize`.
- The guard reads the caller's id from `UserPrincipal` and the caller's role **from the database on every request** (one
  indexed lookup). It never reads the JWT `orgs` claim or `X-User-Id`.
- Role comparison uses an explicit rank on `OrganizationRole` (`isAtLeast`). The declaration order stays as it is and is
  never used.
- A non-member and a non-existent organization get the same 403 `FORBIDDEN`, so organization ids can't be probed.
- Business rules (role ceiling, seat limit, last owner, self-role change) live in the service layer and throw typed
  exceptions. Guards don't contain them.
- No caching of organization roles in phase 1. The existing `project-roles` Caffeine cache stays untouched.
- The `orgs` claim keeps being issued (contract C2), as information for clients only. It's stale until the next login;
  the API isn't.

**Why:** a role change or removal must take effect at once (AC-7.6, AC-8.2), the per-request user lookup already hits
the database, and keeping the user's SpEL pattern means no new mechanism.

### D4. Registration status and login

**Default:** in phase 1, `PENDING_VERIFICATION` users can log in and use the API. `SUSPENDED` users can't log in (401
`INVALID_CREDENTIALS`, the same body as a wrong password), and their existing tokens stop working (401).
`UserPrincipal.isEnabled()` becomes `status != SUSPENDED`. No API sets `SUSPENDED` in phase 1; tests seed it.
**Why:** there's no verification flow, so blocking pending users would block every user; suspension is cheap to honour
now and needed later.

### D5. Dependencies (user approval needed: E11, E12)

| Change                                                                                                                                                                                                                                                                                    | Recommendation                                                                                                                                  | Why                                                                                                                                                        |
|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Remove `spring-boot-starter-elasticsearch`, `spring-boot-starter-security-oauth2-client`, `com.okta.spring:okta-spring-boot-starter:3.1.0`, `spring-security-webauthn`, and the test starters `spring-boot-starter-elasticsearch-test`, `spring-boot-starter-security-oauth2-client-test` | **Yes (E11), after `OrgGuard` is rewritten (T5)**                                                                                               | Nothing uses them except `OrgGuard`'s `Jwt` import, which T5 removes. They add auto-configuration and attack surface, and mirror the gateway's D1 cleanup. |
| jjwt 0.11.5 → 0.12.5                                                                                                                                                                                                                                                                      | **Yes, but as its own task after T3's tests pin the JWT behaviour (E12, T9)**. If the user prefers, defer it; nothing in phase 1 depends on it. | Same library version as the gateway, and the 0.11 API used today is deprecated. Doing it after the behaviour is pinned keeps the risk visible.             |
| Flyway                                                                                                                                                                                                                                                                                    | **No, phase 4**                                                                                                                                 | The database is in-memory and recreated at every start, so migrations add nothing yet.                                                                     |
| Test libraries                                                                                                                                                                                                                                                                            | **None**                                                                                                                                        | JUnit Jupiter, Mockito, AssertJ, MockMvc, `@MockitoBean`, `spring-security-test` and the data-jpa test slice are already on the classpath.                 |
| Actuator                                                                                                                                                                                                                                                                                  | **No**                                                                                                                                          | Not needed in phase 1; health checks come with the Helm wiring.                                                                                            |
| Keep                                                                                                                                                                                                                                                                                      | Caffeine, cache starter, springdoc 3.0.3, PostgreSQL driver, H2, devtools, Lombok, validation, data-jpa, security, webmvc                       | In use or planned (PostgreSQL in phase 4).                                                                                                                 |

### D6. Persistence

**Default:** stay on H2 in-memory. Move the misplaced keys under `spring.jpa` with the user's values unchanged (
`hibernate.ddl-auto: create`, `show-sql: true`, `properties.hibernate.format_sql: true`,
`defer-datasource-initialization: true`) (E13). Set `spring.jpa.open-in-view: false` (E14).
**Why:** the user's intent was clearly `spring.jpa`; in-memory `create` behaves like today's effective `create-drop`.
Open-in-view hides lazy-loading mistakes and keeps connections open through the view. Note: once moved, SQL is logged to
the console; the user can ask to move `show-sql` to the `dev` profile instead.

### D7. Public operations and open endpoints

**Default:**

- Public at auth-service: exactly `POST /api/auth/register`, `POST /api/auth/login`,
  `POST /api/auth/invitations/{token}/accept` (the same three as the gateway's D2), plus `/error`, `/swagger-ui/**` and
  `/v3/api-docs/**`.
- Remove `/api/auth/**` as a blanket permit, and remove the `OpenEndpoints` entries `/api/v1/auth/**` (unused),
  `/actuator/**` (no actuator) and `/h2-console/**` (E3).
- Turn the H2 console off (E15).
- Swagger stays public on port 8081. The gateway doesn't route it, so it's reachable only directly, for local
  development.
- On public operations an invalid or expired token is ignored (the caller is anonymous), so a user with an expired token
  can still log in (AC-2.8), matching the gateway's AC-2.5.

**Why:** deny by default means a future `/api/auth/...` endpoint can't become public by accident, and the service's
public list matches the edge's. **Phase 1 adds no public operation, so the gateway configuration doesn't change.**

### D8. Audit log

**Default:** defer writing audit entries to phase 2.
**Why:** `AuditLog` has no accessors, so writing it means changing the entity, and without a read API nothing would be
observable or testable. Phase 2 should spec both together (who changed which role, invitations, removals).

### D9. Contract

**Default:** backend-swe writes `docs/api/auth-service.openapi.yaml` (OpenAPI 3.1) in T6, right after the security core,
documenting every phase-1 operation with external paths (the gateway doesn't rewrite), the `bearerAuth` scheme (C2), the
Problem schema with `code`, `errors` and `requestId`, and the D2 table. T15 syncs it with the final behaviour.
**Why:** it is the gateway spec's C4 follow-up, and it unblocks frontend-swe while Batch C is being built.

### D10. Testing approach

**Default:**

- Acceptance tests: `@SpringBootTest` with MockMvc (`@AutoConfigureMockMvc`) against the real security chain, advice and
  H2, one test per AC row where practical, tagged `@Tag("AC-x.y")`.
- Unit tests (plain JUnit + Mockito) for pure rules: role rank, ceiling, seat count.
- `@DataJpaTest` only for new repository queries that aren't trivial derived queries.
- The test-only JWT secret is a constant in test code (standard Base64 of 64 bytes, different from the dev value),
  supplied with `@SpringBootTest(properties = …)` or a shared test meta-annotation. **`src/test/resources` is not used
  ** (same rule as the gateway's G1).
- Test data is created through the API or seeded through repositories, and cleaned in `@BeforeEach`. API tests are **not
  ** `@Transactional` (that would hide transaction-boundary bugs such as the non-atomic invitation acceptance).
- Tokens are obtained by logging in; tokens the API can't produce (expired, HS256, wrong secret, `alg none`) are minted
  in a test helper with jjwt.
- The cross-service checks through the gateway are manual (T15), as in the gateway spec.

**Why:** no new libraries are needed, and full-context MockMvc tests prove the behaviour qa will check, including the
security chain.

### D11. Organization and membership rules

**Default:**

- **Create:** the creator becomes `OWNER`. Plan `FREE`, `seatLimit` = `app.orgs.free-seat-limit` (default **5**). Names
  are 1–100 characters and not blank; duplicates are allowed.
- **Seats used** = members + `PENDING` invitations whose `expiresAt` is in the future. Invite needs seats used < seat
  limit. Accept re-checks members < seat limit. `null` seat limit = unlimited. The check and the insert must not be able
  to overshoot the limit under concurrent requests (NFR).
- **Role ceiling:** an actor may invite with, change to, or change or remove a member holding, only roles with rank ≤
  the actor's rank. So an `ADMIN` can't invite or create an `OWNER`, and can't change or remove an `OWNER`. An `OWNER`
  can do everything, including making another `OWNER`.
- **Self:** you can't change your own role (`SELF_ROLE_CHANGE`). Any member can leave (remove themselves), whatever
  their role. The last `OWNER` can't leave (`LAST_OWNER`). Ownership is handed over by making someone else `OWNER`
  first.
- **Who can do what:** view an organization and its member list: any member. Invite, list pending invitations, revoke,
  change roles, remove others: at least `ADMIN`, within the ceiling.
- **Invitation delivery:** no email in phase 1. The create-invitation response returns the acceptance token once, to the
  inviter, who shares it out of band. Lists never show tokens. Tokens are never logged.
- **Invitation rules:** one pending, unexpired invitation per organization and email; no invitation for an existing
  member; 7-day expiry (existing behaviour); revoking frees the seat.
- **Accept:** the invitation's email must equal the caller's email (case-insensitive) when the caller is logged in. An
  anonymous caller provides a password and full name, and the account plus membership are created in one transaction. If
  the email already has an account, the anonymous caller gets 409 and must log in first.
- **Decided on 2026-10-12 (backend-swe's questions after review point B):**
    - **Q2, the accept response body.** The 200 (existing account) and the 201 (new account) use the same schema, and
      both `user` and `organization` are required. **Why:** one schema for the frontend, which always gets both the
      account and the organization it joined. (AC-6.2)
    - **Q3, a body sent by a logged-in caller.** A well-formed JSON body is ignored, without validation. An unreadable
      body is still 400 `MALFORMED_REQUEST`. **Why:** a logged-in caller's identity comes from the token, so a stale
      password or name field must not fail the request. Parsing errors are rejected before the handler runs anyway.
    - **Q4, revoking a `PENDING` invitation that has expired.** 204: it becomes `REVOKED`. **Why:** the admin's
      intent ("this invitation must not work") is met, and an invitation that expires between listing and clicking
      shouldn't produce an error. `INVITATION_NOT_PENDING` stays for `ACCEPTED` and `REVOKED` invitations.
    - **Q5, the loser of two concurrent acceptances of one token.** It gets exactly what a second, sequential acceptance
      would get: 410 `INVITATION_ALREADY_ACCEPTED`. It creates no user and no membership, and never gets a 500. The
      invitation's state must therefore be checked and claimed before any user or membership is written. **Why:** a
      deterministic answer that the T13 concurrency test can assert.
    - **Q6, which error wins when several apply.** Checks run in a fixed order, and the first failure is returned. *
      *Why:** the caller first sees the problem they can fix themselves, and target-specific answers come before the
      organization-wide seat limit.
        - **Invite:** request validation 400 (runs before authorization, A3); then not a member or below `ADMIN`, 403
          `FORBIDDEN`; then `ROLE_CEILING` 422; then `ALREADY_MEMBER` 409; then `INVITATION_PENDING` 409; then
          `SEAT_LIMIT_REACHED` 409.
        - **Accept:** body validation, anonymous callers only (400); then `INVITATION_NOT_FOUND` 404; then
          `INVITATION_ALREADY_ACCEPTED`, `INVITATION_REVOKED` or `INVITATION_EXPIRED` 410, in that order; then, for a
          logged-in caller, `INVITATION_EMAIL_MISMATCH` 403 and `ALREADY_MEMBER` 409, or, for an anonymous caller,
          `EMAIL_ALREADY_REGISTERED` 409; then `SEAT_LIMIT_REACHED` 409.
        - **Change role and remove:** 403 `FORBIDDEN` (not a member; or, when the target isn't the caller, below
          `ADMIN`); then `MEMBER_NOT_FOUND` 404; then `SELF_ROLE_CHANGE` 422 (change only); then `ROLE_CEILING` 422;
          then `LAST_OWNER` 409 (leave only).

**Why:** these are the rules the existing TODOs and `OrgRoleCeilingException` point at, made explicit and testable. The
free seat limit of 5 is a product guess, and it's configuration, so it's cheap to change.

### D12. Email normalisation

**Default:** emails are trimmed and lower-cased before they're stored or compared, at registration, login, invitation
and acceptance. Validation still runs on the raw input.
**Why:** `Ana@Example.com` and `ana@example.com` must be one account, and invitation matching depends on it.

### D13. Decisions on qa's findings (decided at acceptance, 2026-10-12)

| Finding                                                                                       | Decision                                                                                                                                                                                                                                                                                                                                                                     | Why                                                                                                                                                                                                                              | Recorded in                          |
|-----------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------|
| Q1 / TF-7(1): revoke and the role ceiling                                                     | Revoking applies the ceiling: an actor may revoke only invitations whose role rank is at most their own; otherwise 422 `ROLE_CEILING`. Revoke error order: 403 `FORBIDDEN`, then 404 `INVITATION_NOT_FOUND`, then 409 `INVITATION_NOT_PENDING`, then 422 `ROLE_CEILING`. The Q4 rule (an expired `PENDING` invitation can be revoked, 204) still applies within the ceiling. | D11 lists revoke among the actions "within the ceiling", and an `ADMIN` must not be able to cancel an owner's ownership hand-over.                                                                                               | AC-5.12, T19, E27                    |
| Q2 / TF-7(2): the error shape of an accept with no body                                       | A missing or empty body gives the same per-field `errors` as `{}`: one entry each for `fullName` and `password`.                                                                                                                                                                                                                                                             | The UI maps `errors` to inputs; `acceptInvitationRequest` is a Java parameter name, not a field.                                                                                                                                 | AC-6.10, T20, E28                    |
| Q3 / TF-2: `instance` echoes the acceptance token                                             | Keep it. `instance` stays the request path, as D2 says.                                                                                                                                                                                                                                                                                                                      | The token is the caller's own input, it's echoed only to that caller, and it's never logged (verified by qa). Redaction would make `instance` differ from the path for one operation only.                                       | D2 (unchanged); contract note in T21 |
| Q4 / TF-4: Unicode in emails                                                                  | Phase 1: trim and lower-case with `Locale.ROOT`, with no Unicode normalisation. NFC goes to phase 3.                                                                                                                                                                                                                                                                         | Matching is consistent today, and normalisation belongs with email verification.                                                                                                                                                 | D12 (as is), FU-2                    |
| Q5 / TF-5: Unicode blanks and U+0000 in names                                                 | Deferred, with a gate: it must land before or with the PostgreSQL move.                                                                                                                                                                                                                                                                                                      | Harmless on H2, but U+0000 fails on PostgreSQL, so the fix belongs with phase 4.                                                                                                                                                 | FU-3                                 |
| Q6 / TF-6 / BUG-3: the code for losers of a registration race                                 | `EMAIL_ALREADY_REGISTERED`, also under a race. AC-2.2's code applies to concurrent requests.                                                                                                                                                                                                                                                                                 | The UI reacts differently ("log in instead") to `EMAIL_ALREADY_REGISTERED` than to a generic `CONFLICT`.                                                                                                                         | AC-2.11, T18, E26                    |
| Q7 / TF-1 / BUG-1: errors outside Spring MVC                                                  | Target: Problems for firewall rejections, `TRACE` and direct `/error`, under a new AC in the follow-up. Tomcat's encoded-slash HTML page is out of scope. Deferred, and documented as a phase-1 deviation in the contract.                                                                                                                                                   | Nothing is exposed, the statuses are right, and the gateway deferred the same class of rejections (its FU-3).                                                                                                                    | FU-1; contract note in T21           |
| TF-3 / BUG-2: numeric roles                                                                   | Numbers (and numeric strings) are wrong values: 400 `MALFORMED_REQUEST`.                                                                                                                                                                                                                                                                                                     | D3 says the declaration order is never used, and the contract already says "any other value is 400".                                                                                                                             | AC-5.11, AC-7.8, T17, E24            |
| BUG-4: a 72-byte password matches with any suffix                                             | A login password longer than 72 UTF-8 bytes is answered 401 `INVALID_CREDENTIALS`, with no validation error, so AC-2.4's answer stays uniform.                                                                                                                                                                                                                               | No user can have such a password (E17), so it can never be right.                                                                                                                                                                | AC-2.10, T18, E25                    |
| Lock timeouts (backend-swe deviation 1; follow-up approved by the user on 2026-10-12)         | `jakarta.persistence.LockTimeoutException`, `jakarta.persistence.PessimisticLockException`, Spring's `PessimisticLockingFailureException` and its subclasses (including `CannotAcquireLockException`) all become 409 `CONFLICT`, logged at WARN without a stack trace.                                                                                                       | The organization lock comes from `EntityManager.find`, which Spring doesn't translate, so mapping only the Spring type would miss it (qa evidence).                                                                              | AC-1.9, T16, E23                     |
| Q8 / BUG-5: an email in Hibernate's WARN line under a lost race (decided at final acceptance) | Emails must not appear in logs at any level. Fix in a later round, with E29 (proposed).                                                                                                                                                                                                                                                                                      | An email is personal data, and logs are kept longer and read more widely than the database. The NFR's "no emails" intent applies to every log line, not only INFO. S4: races are rare, and there's no production deployment yet. | FU-6, E29                            |
| Q9 / BUG-6: role names with surrounding whitespace (decided at final acceptance)              | Accept the leniency: Jackson trims ASCII whitespace before matching. Change the contract wording, not the code.                                                                                                                                                                                                                                                              | It's harmless: the ceiling applies to the resolved role, so there's no escalation. Exact matching would need custom deserialization of `OrganizationRole`, which D3 and T17 deliberately avoided.                                | FU-7                                 |

## User stories

### US-1: Consistent, safe error responses (P0)

As a frontend developer, I want every auth-service error to be a Problem with a stable code and the real status, so that
the UI can show the right message instead of a generic 403.

```gherkin
Feature: Consistent, safe error responses

  @US-1 @AC-1.1 @P0
  Scenario: A validation error is a 400 Problem that lists each invalid field
    When an anonymous caller sends "POST /api/auth/register" with body {"email":"not-an-email","password":"short","fullName":""}
    Then the response is a Problem 400 / VALIDATION_FAILED with instance "/api/auth/register"
    And its "errors" member has exactly one entry each for the fields "email", "password" and "fullName"
    And no user exists with email "not-an-email"

  @US-1 @AC-1.2 @P0
  Scenario Outline: An unreadable body on a public operation is a 400 Problem, not an empty 403
    When an anonymous caller sends "POST <path>" with header "Content-Type: application/json" and body {"email":
    Then the response is a Problem 400 / MALFORMED_REQUEST

    Examples:
      | path                                  |
      | /api/auth/register                    |
      | /api/auth/login                       |
      | /api/auth/invitations/inv-7Kq2/accept |

  @US-1 @AC-1.3 @P0
  Scenario: An unexpected failure is a 500 Problem without internal details
    Given the Acme fixture
    And the test makes the organization lookup fail with an unexpected exception whose message is "SELECT secret FROM x at db-host:5432"
    When Ana sends "GET /api/orgs" with "{token of Ana}"
    Then the response is a Problem 500 / INTERNAL_ERROR
    And the body contains neither "SELECT", "db-host", "5432", "Exception" nor "at com.example"
    And the service log contains exactly one ERROR entry for the request

  @US-1 @AC-1.4 @P1
  Scenario: An unknown path is a 404 Problem for an authenticated caller
    Given the Acme fixture
    When Ana sends "GET /api/orgs/{acme}/no-such-thing" with "{token of Ana}"
    Then the response is a Problem 404 / NOT_FOUND

  @US-1 @AC-1.5 @P1
  Scenario Outline: An unsupported method is a 405 Problem
    Given the Acme fixture
    When Ana sends "<method> <path>" with "{token of Ana}"
    Then the response is a Problem 405 / METHOD_NOT_ALLOWED

    Examples:
      | method | path            |
      | DELETE | /api/orgs       |
      | PUT    | /api/orgs/{acme} |

  @US-1 @AC-1.6 @P1
  Scenario: An unsupported content type is a 415 Problem
    When an anonymous caller sends "POST /api/auth/login" with header "Content-Type: text/plain" and body "ana@example.com"
    Then the response is a Problem 415 / UNSUPPORTED_MEDIA_TYPE

  @US-1 @AC-1.7 @P2
  Scenario Outline: A Problem carries the caller's request ID only when it is valid
    Given the Acme fixture
    When an anonymous caller sends "POST /api/auth/register" with header "X-Request-Id: <sent>" and body {"email":"ana@example.com","password":"Secret123!","fullName":"Ana Lima"}
    Then the response is a Problem 409 / EMAIL_ALREADY_REGISTERED
    And the body <outcome>
    And the response has no "X-Request-Id" header

    Examples:
      | sent        | outcome                                   |
      | rid-123     | has "requestId" equal to "rid-123"        |
      | bad id!     | has no "requestId" member                 |
      | (no header) | has no "requestId" member                 |

  @US-1 @AC-1.8 @P2
  Scenario: auth-service never emits CORS headers
    Given the Acme fixture
    When a caller sends "POST /api/auth/login" with header "Origin: http://localhost:5173" and Ana's valid credentials
    Then the response status is 200
    And the response has no header whose name starts with "Access-Control-"

  # Added at acceptance, 2026-10-12 (fix round, T16)
  @US-1 @AC-1.9 @P2
  Scenario Outline: A lock that can't be acquired in time is a 409 Problem, not a 500
    Given the Acme fixture
    And the test makes taking the organization lock fail with <exception>
    When Ben sends "POST /api/orgs/{acme}/invitations" with "{token of Ben}" and body {"email":"fay@example.com","role":"MEMBER"}
    Then the response is a Problem 409 / CONFLICT
    And Acme has no invitations
    And the service log contains no ERROR entry and no stack trace for the request

    Examples:
      | exception                                                |
      | jakarta.persistence.LockTimeoutException                 |
      | jakarta.persistence.PessimisticLockException             |
      | org.springframework.dao.PessimisticLockingFailureException |
      | org.springframework.dao.CannotAcquireLockException       |
```

### US-2: Register and log in (P0)

As a new user, I want to register and log in with my email in any letter case and get clear errors, so that I can start
using the platform.

```gherkin
Feature: Register and log in

  @US-2 @AC-2.1 @P0
  Scenario: Registration creates a pending account and never returns the password
    When an anonymous caller sends "POST /api/auth/register" with body {"email":"Fay@Example.com","password":"Secret123!","fullName":"Fay Lin"}
    Then the response status is 201
    And the body has a UUID "id", "email" "fay@example.com", "fullName" "Fay Lin", "status" "PENDING_VERIFICATION", "mfaEnabled" false and a "createdAt"
    And the body has no member whose name contains "password"

  @US-2 @AC-2.2 @P0
  Scenario Outline: An email that is already registered, in any letter case, is a conflict
    Given the Acme fixture
    When an anonymous caller sends "POST /api/auth/register" with body {"email":"<email>","password":"Secret123!","fullName":"Someone"}
    Then the response is a Problem 409 / EMAIL_ALREADY_REGISTERED
    And exactly one user exists with email "ana@example.com"

    Examples:
      | email           |
      | ana@example.com |
      | Ana@Example.COM |

  @US-2 @AC-2.3 @P0
  Scenario: Login returns an HS512 token with the agreed claims
    Given the Acme fixture
    When an anonymous caller sends "POST /api/auth/login" with body {"email":"ana@example.com","password":"Secret123!"}
    Then the response status is 200 and the body has a non-empty "accessToken"
    And the token's header "alg" is "HS512" and it verifies with the shared secret
    And its claims are "sub" = "{Ana.id}", "email" = "ana@example.com", "mfa" = false, and "orgs" = [{"orgId":"{acme}","role":"OWNER"}]
    And "exp" minus "iat" is 86400 seconds

  @US-2 @AC-2.4 @P0
  Scenario Outline: Wrong credentials give the same 401 Problem whatever was wrong
    Given the Acme fixture
    When an anonymous caller sends "POST /api/auth/login" with body {"email":"<email>","password":"<password>"}
    Then the response is a Problem 401 / INVALID_CREDENTIALS
    And its "detail" is identical in every row

    Examples:
      | email               | password     |
      | ana@example.com     | Wrong123!    |
      | nobody@example.com  | Secret123!   |

  @US-2 @AC-2.5 @P1
  Scenario: Login ignores the email's letter case
    Given the Acme fixture
    When an anonymous caller sends "POST /api/auth/login" with body {"email":"ANA@example.com","password":"Secret123!"}
    Then the response status is 200 and the token's "sub" is "{Ana.id}"

  @US-2 @AC-2.6 @P1
  Scenario: A suspended user can't log in and gets the same answer as a wrong password
    Given the Acme fixture
    And Cai's status is seeded as "SUSPENDED"
    When an anonymous caller sends "POST /api/auth/login" with body {"email":"cai@example.com","password":"Secret123!"}
    Then the response is a Problem 401 / INVALID_CREDENTIALS
    And its "detail" equals the "detail" of a wrong-password login

  @US-2 @AC-2.7 @P1
  Scenario: A pending user can log in in phase 1
    Given the Acme fixture
    And Eve's status is "PENDING_VERIFICATION"
    When an anonymous caller sends "POST /api/auth/login" with Eve's credentials
    Then the response status is 200 and the token's "orgs" claim is []

  @US-2 @AC-2.8 @P1
  Scenario Outline: A bad token on a public operation is ignored
    Given the Acme fixture
    When a caller sends "POST /api/auth/login" with header "Authorization: <authorization>" and Ana's valid credentials
    Then the response status is 200

    Examples:
      | authorization                                          |
      | Bearer not-a-jwt                                       |
      | Bearer {Ana's token whose exp is 1 hour in the past}   |

  @US-2 @AC-2.9 @P1
  Scenario Outline: Field length limits are validation errors, not server errors
    When an anonymous caller sends "POST /api/auth/register" with a body where <field> is <value> and the other fields are valid
    Then the response is a Problem 400 / VALIDATION_FAILED with an "errors" entry for "<field>"

    Examples:
      | field    | value                                               |
      | password | 73 ASCII characters                                 |
      | fullName | 101 characters                                      |
      | email    | a syntactically valid address of 255 characters     |

  # Added at acceptance, 2026-10-12 (fix round, T18)
  @US-2 @AC-2.10 @P2
  Scenario Outline: Only the exact 72-byte password logs in
    Given user "a72@example.com" registered with a password of 72 × "A"
    When an anonymous caller sends "POST /api/auth/login" with email "a72@example.com" and password <password>
    Then <outcome>

    Examples:
      | password              | outcome                                                                                   |
      | 72 × "A"              | the response status is 200                                                                |
      | 72 × "A" + "Z"        | the response is a Problem 401 / INVALID_CREDENTIALS with the same "detail" as AC-2.4      |
      | 24 × "€" + "X"        | the response is a Problem 401 / INVALID_CREDENTIALS with the same "detail" as AC-2.4      |

  @US-2 @AC-2.11 @P2
  Scenario: Losers of a simultaneous registration get the same conflict as a late one
    When 5 anonymous callers send "POST /api/auth/register" with body {"email":"zed@example.com","password":"Secret123!","fullName":"Zed"} at the same moment
    Then exactly one response has status 201
    And each of the other 4 is a Problem 409 / EMAIL_ALREADY_REGISTERED
    And exactly one user exists with email "zed@example.com"
```

### US-3: Authenticate requests at auth-service (P0)

As a service developer, I want auth-service to reject bad tokens with 401 and accept only tokens the gateway would
accept, so that the service is safe even when called directly.

All scenarios in US-3 are direct calls (port 8081).

```gherkin
Feature: Authenticate requests at auth-service

  @US-3 @AC-3.1 @P0
  Scenario Outline: A protected request without valid credentials is a 401 Problem
    Given the Acme fixture
    When a caller sends "GET /api/orgs" with Authorization header <authorization>
    Then the response is a Problem 401 / UNAUTHENTICATED
    And the response has a "WWW-Authenticate" header whose value starts with "Bearer"

    Examples:
      | case                          | authorization                                                                  |
      | header missing                | (none)                                                                         |
      | wrong scheme                  | "Basic YW5hOlNlY3JldDEyMyE="                                                   |
      | empty bearer value            | "Bearer "                                                                      |
      | not a JWT                     | "Bearer not-a-jwt"                                                             |
      | signed with another secret    | "Bearer {Ana's claims signed HS512 with a different 64-byte secret}"           |
      | expired                       | "Bearer {Ana's token whose exp is 1 hour in the past}"                         |
      | unsigned                      | "Bearer {Ana's claims with header alg none and no signature}"                  |
      | HS256 with the shared secret  | "Bearer {Ana's claims signed HS256 with the shared secret}"                    |
      | payload altered after signing | "Bearer {Ana's token with sub changed to {Ben.id}, original signature kept}"   |
      | subject is not a UUID         | "Bearer {token signed HS512 with the shared secret, sub "ana", exp in 1 hour}" |
      | no subject                    | "Bearer {token signed HS512 with the shared secret, no sub, exp in 1 hour}"    |

  @US-3 @AC-3.2 @P0
  Scenario: A valid token authenticates the request
    Given the Acme fixture
    When Ana sends "GET /api/orgs" with header "Authorization: Bearer {token of Ana}"
    Then the response status is 200

  @US-3 @AC-3.3 @P1
  Scenario: The bearer scheme is matched case-insensitively
    Given the Acme fixture
    When Ana sends "GET /api/orgs" with header "Authorization: bearer {token of Ana}"
    Then the response status is 200

  @US-3 @AC-3.4 @P1
  Scenario: A suspended user's existing token stops working
    Given the Acme fixture
    And Cai has logged in and holds "{token of Cai}"
    And Cai's status is then seeded as "SUSPENDED"
    When Cai sends "GET /api/orgs" with "{token of Cai}"
    Then the response is a Problem 401 / UNAUTHENTICATED

  @US-3 @AC-3.5 @P1
  Scenario: A valid token for a user that no longer exists is rejected
    When a caller sends "GET /api/orgs" with a token signed HS512 with the shared secret, sub "{unknown id}", exp in 1 hour
    Then the response is a Problem 401 / UNAUTHENTICATED

  @US-3 @AC-3.6 @P0
  Scenario Outline: Only the three auth operations are public
    When an anonymous caller sends "<method> <path>"
    Then the response is a Problem 401 / UNAUTHENTICATED

    Examples:
      | method | path                                  |
      | GET    | /api/orgs                             |
      | POST   | /api/orgs                             |
      | GET    | /api/auth/login                       |
      | POST   | /api/auth/logout                      |
      | GET    | /api/auth/invitations/inv-7Kq2/accept |
      | POST   | /api/v1/auth/login                    |
      | GET    | /h2-console                           |
      | GET    | /actuator/health                      |

  @US-3 @AC-3.7 @P2
  Scenario: The API docs stay public on the service port
    When an anonymous caller sends "GET /v3/api-docs"
    Then the response status is 200

  @US-3 @AC-3.8 @P2
  Scenario: Rejected tokens are never logged
    When a caller sends "GET /api/orgs" with header "Authorization: Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJ4In0.tampered-sig-QA"
    Then the response is a Problem 401 / UNAUTHENTICATED
    And the service log does not contain "tampered-sig-QA"
    And the service log contains no ERROR entry and no stack trace for the request

  @US-3 @AC-3.9 @P2
  Scenario Outline: The service refuses to start with an unusable secret
    When auth-service is started without the "dev" profile and with "app.jwtSecret" <secret>
    Then startup fails with a message that names "app.jwtSecret"
    And the message does not contain the secret value

    Examples:
      | secret                                   |
      | not set                                  |
      | "not base64 !!"                          |
      | the Base64 encoding of 32 random bytes   |
```

### US-4: Create, list and view organizations (P0)

As a user, I want to create organizations and see the ones I belong to with my role, so that I can organise my team's
testing work.

```gherkin
Feature: Create, list and view organizations

  @US-4 @AC-4.1 @P0
  Scenario: Creating an organization makes the creator its owner
    Given the Acme fixture
    When Eve sends "POST /api/orgs" with "{token of Eve}" and body {"name":"Globex QA"}
    Then the response status is 201
    And the body has a UUID "id", "name" "Globex QA", "plan" "FREE", "seatLimit" 5, a "createdAt" and "myRole" "OWNER"
    And the "Location" header is exactly "/api/orgs/" followed by that id
    And "GET /api/orgs/{that id}/members" by Eve lists exactly Eve, with role "OWNER"

  @US-4 @AC-4.2 @P0
  Scenario Outline: An invalid organization name is a validation error
    Given the Acme fixture
    When Eve sends "POST /api/orgs" with "{token of Eve}" and body <body>
    Then the response is a Problem 400 / VALIDATION_FAILED with an "errors" entry for "name"
    And Eve still belongs to no organization

    Examples:
      | body                          |
      | {}                            |
      | {"name":""}                   |
      | {"name":"   "}                |
      | {"name":"<101 characters>"}   |

  @US-4 @AC-4.3 @P0
  Scenario: Listing my organizations shows each one with my role
    Given the Acme fixture
    And organization "Initech QA" is seeded with Eve as "OWNER" and Ana as "READER"
    When Ana sends "GET /api/orgs" with "{token of Ana}"
    Then the response status is 200
    And the body is a list of exactly 2 organizations ordered by name: "Acme QA" with "myRole" "OWNER", then "Initech QA" with "myRole" "READER"

  @US-4 @AC-4.4 @P0
  Scenario: A user without organizations gets an empty list
    Given the Acme fixture
    When Eve sends "GET /api/orgs" with "{token of Eve}"
    Then the response status is 200 and the body is []

  @US-4 @AC-4.5 @P1
  Scenario Outline: Viewing one organization is allowed for every member and nobody else
    Given the Acme fixture
    When <user> sends "GET /api/orgs/{acme}" with "{token of <user>}"
    Then <outcome>

    Examples:
      | user | outcome                                                                    |
      | Ana  | the response status is 200 and "myRole" is "OWNER"                         |
      | Ben  | the response status is 200 and "myRole" is "ADMIN"                         |
      | Cai  | the response status is 200 and "myRole" is "MEMBER"                        |
      | Dan  | the response status is 200 and "myRole" is "READER"                        |
      | Eve  | the response is a Problem 403 / FORBIDDEN                                  |

  @US-4 @AC-4.6 @P1
  Scenario Outline: An unknown or malformed organization id doesn't reveal anything
    Given the Acme fixture
    When Eve sends "GET /api/orgs/<id>" with "{token of Eve}"
    Then the response is a Problem <status> / <code>

    Examples:
      | id           | status | code              |
      | {unknown id} | 403    | FORBIDDEN         |
      | not-a-uuid   | 400    | MALFORMED_REQUEST |

  @US-4 @AC-4.7 @P1
  Scenario: A new organization is usable without logging in again
    Given the Acme fixture
    And Eve holds "{token of Eve}", issued while she belonged to no organization
    When Eve creates organization "Globex QA" and then sends "GET /api/orgs" with the same token
    Then the list contains "Globex QA" with "myRole" "OWNER"
```

### US-5: Invite people to an organization (P0)

As an organization admin, I want to invite a colleague by email with a role, within my seat limit and my own role, so
that they can join.

```gherkin
Feature: Invite people to an organization

  @US-5 @AC-5.1 @P0
  Scenario: An admin invites a colleague and receives the acceptance token once
    Given the Acme fixture
    When Ben sends "POST /api/orgs/{acme}/invitations" with "{token of Ben}" and body {"email":"Fay@Example.com","role":"MEMBER"}
    Then the response status is 201
    And the body has a UUID "id", "email" "fay@example.com", "role" "MEMBER", "status" "PENDING", a "createdAt", an "expiresAt" 7 days after "createdAt", and a "token" of at least 32 URL-safe characters
    And the service log does not contain the token

  @US-5 @AC-5.2 @P0
  Scenario Outline: Who may invite with which role
    Given the Acme fixture
    When <actor> sends "POST /api/orgs/{acme}/invitations" with "{token of <actor>}" and body {"email":"fay@example.com","role":"<role>"}
    Then <outcome>

    Examples:
      | actor | role   | outcome                                          |
      | Ana   | OWNER  | the response status is 201                       |
      | Ana   | ADMIN  | the response status is 201                       |
      | Ben   | ADMIN  | the response status is 201                       |
      | Ben   | MEMBER | the response status is 201                       |
      | Ben   | READER | the response status is 201                       |
      | Ben   | OWNER  | the response is a Problem 422 / ROLE_CEILING     |
      | Cai   | READER | the response is a Problem 403 / FORBIDDEN        |
      | Dan   | READER | the response is a Problem 403 / FORBIDDEN        |
      | Eve   | READER | the response is a Problem 403 / FORBIDDEN        |

  @US-5 @AC-5.3 @P0
  Scenario: No invitation is created when every seat is taken
    Given the Acme fixture
    And Ben has invited "fay@example.com" as "MEMBER", so 4 members and 1 pending invitation use all 5 seats
    When Ben sends "POST /api/orgs/{acme}/invitations" with "{token of Ben}" and body {"email":"gus@example.com","role":"READER"}
    Then the response is a Problem 409 / SEAT_LIMIT_REACHED
    And "GET /api/orgs/{acme}/invitations" by Ben lists only "fay@example.com"

  @US-5 @AC-5.4 @P1
  Scenario: An expired invitation frees its seat
    Given the Acme fixture
    And a "PENDING" invitation for "old@example.com" whose "expiresAt" was 1 hour ago is seeded in Acme
    When Ben invites "fay@example.com" as "MEMBER"
    Then the response status is 201

  @US-5 @AC-5.5 @P1
  Scenario: Inviting an existing member is a conflict
    Given the Acme fixture
    When Ben sends "POST /api/orgs/{acme}/invitations" with "{token of Ben}" and body {"email":"Cai@Example.com","role":"ADMIN"}
    Then the response is a Problem 409 / ALREADY_MEMBER

  @US-5 @AC-5.6 @P1
  Scenario: Inviting an email that already has a pending invitation is a conflict
    Given the Acme fixture
    And Ben has invited "fay@example.com" as "MEMBER"
    When Ana sends "POST /api/orgs/{acme}/invitations" with "{token of Ana}" and body {"email":"fay@example.com","role":"ADMIN"}
    Then the response is a Problem 409 / INVITATION_PENDING

  @US-5 @AC-5.7 @P0
  Scenario Outline: An invalid invitation request is rejected
    Given the Acme fixture
    When Ben sends "POST /api/orgs/{acme}/invitations" with "{token of Ben}" and body <body>
    Then the response is a Problem 400 / <code>
    And Acme has no invitations

    Examples:
      | body                                          | code              |
      | {"email":"not-an-email","role":"MEMBER"}      | VALIDATION_FAILED |
      | {"email":"fay@example.com"}                   | VALIDATION_FAILED |
      | {"email":"fay@example.com","role":"SUPERUSER"} | MALFORMED_REQUEST |

  @US-5 @AC-5.8 @P1
  Scenario Outline: Listing pending invitations
    Given the Acme fixture
    And Ben has invited "fay@example.com" as "MEMBER"
    And a "PENDING" invitation for "old@example.com" whose "expiresAt" was 1 hour ago is seeded in Acme
    When <actor> sends "GET /api/orgs/{acme}/invitations" with "{token of <actor>}"
    Then <outcome>

    Examples:
      | actor | outcome                                                                                              |
      | Ana   | the response status is 200 and the body lists exactly "fay@example.com", and no item has a "token" member |
      | Ben   | the response status is 200 and the body lists exactly "fay@example.com", and no item has a "token" member |
      | Cai   | the response is a Problem 403 / FORBIDDEN                                                            |
      | Dan   | the response is a Problem 403 / FORBIDDEN                                                            |
      | Eve   | the response is a Problem 403 / FORBIDDEN                                                            |

  @US-5 @AC-5.9 @P1
  Scenario: Revoking a pending invitation frees the seat and kills the token
    Given the Acme fixture
    And Ben has invited "fay@example.com" as "MEMBER" and received invitation id I and token T
    When Ben sends "DELETE /api/orgs/{acme}/invitations/I" with "{token of Ben}"
    Then the response status is 204
    And "GET /api/orgs/{acme}/invitations" by Ben returns []
    And an anonymous "POST /api/auth/invitations/T/accept" with body {"password":"Secret123!","fullName":"Fay Lin"} is a Problem 410 / INVITATION_REVOKED

  @US-5 @AC-5.10 @P1
  Scenario Outline: Revoking is limited to admins and to pending invitations of the same organization
    Given the Acme fixture
    And Ben has invited "fay@example.com" as "MEMBER" and received invitation id I
    And organization "Initech QA" is seeded with Eve as "OWNER" and a pending invitation J
    And <setup>
    When <actor> sends "DELETE /api/orgs/{acme}/invitations/<target>" with "{token of <actor>}"
    Then the response is a Problem <status> / <code>

    Examples:
      | setup                                   | actor | target       | status | code                   |
      | nothing else                            | Cai   | I            | 403    | FORBIDDEN              |
      | nothing else                            | Ben   | J            | 404    | INVITATION_NOT_FOUND   |
      | nothing else                            | Ben   | {unknown id} | 404    | INVITATION_NOT_FOUND   |
      | Fay has accepted invitation I           | Ben   | I            | 409    | INVITATION_NOT_PENDING |

  # Added at acceptance, 2026-10-12 (fix round, T17 and T19)
  @US-5 @AC-5.11 @P1
  Scenario Outline: A role sent as a number is rejected, never mapped by declaration order
    Given the Acme fixture
    When Ana sends "POST /api/orgs/{acme}/invitations" with "{token of Ana}" and body {"email":"fay@example.com","role":<role>}
    Then the response is a Problem 400 / MALFORMED_REQUEST
    And Acme has no invitations

    Examples:
      | role |
      | 0    |
      | 1    |
      | 3    |
      | "3"  |

  @US-5 @AC-5.12 @P1
  Scenario Outline: Revoking respects the role ceiling
    Given the Acme fixture
    And Ana has invited "fay@example.com" as "OWNER" and received invitation id I
    When <actor> sends "DELETE /api/orgs/{acme}/invitations/I" with "{token of <actor>}"
    Then <outcome>

    Examples:
      | actor | outcome                                                                                                    |
      | Ben   | the response is a Problem 422 / ROLE_CEILING, and "GET /api/orgs/{acme}/invitations" by Ana still lists "fay@example.com" |
      | Ana   | the response status is 204                                                                                 |
```

### US-6: Accept an invitation (P0)

As an invited person, I want to join the organization with my invitation token, either by creating my account or with
the account I already have, so that I can start working with my team.

```gherkin
Feature: Accept an invitation

  Background:
    Given the Acme fixture
    And Ben has invited "fay@example.com" as "MEMBER" and received token T

  @US-6 @AC-6.1 @P0
  Scenario: A new person creates an account and joins in one step
    When an anonymous caller sends "POST /api/auth/invitations/T/accept" with body {"password":"Secret123!","fullName":"Fay Lin"}
    Then the response status is 201
    And the body's "user" has "email" "fay@example.com", "fullName" "Fay Lin" and "status" "PENDING_VERIFICATION"
    And the body's "organization" has "id" "{acme}" and "myRole" "MEMBER"
    And Fay can log in, and "GET /api/orgs" by Fay lists "Acme QA" with "myRole" "MEMBER"
    And "GET /api/orgs/{acme}/invitations" by Ben returns []

  @US-6 @AC-6.2 @P0
  Scenario: A person who already has an account accepts while logged in
    Given Fay has registered with "fay@example.com" and holds "{token of Fay}"
    When Fay sends "POST /api/auth/invitations/T/accept" with "{token of Fay}" and no body
    Then the response status is 200
    And the body's "organization" has "id" "{acme}" and "myRole" "MEMBER"
    And "GET /api/orgs/{acme}/members" by Ben includes "fay@example.com" with role "MEMBER"

  @US-6 @AC-6.3 @P0
  Scenario: An invitation can't be accepted by an account with another email
    When Eve sends "POST /api/auth/invitations/T/accept" with "{token of Eve}" and no body
    Then the response is a Problem 403 / INVITATION_EMAIL_MISMATCH
    And Eve is not a member of Acme
    And "GET /api/orgs/{acme}/invitations" by Ben still lists "fay@example.com"

  @US-6 @AC-6.4 @P0
  Scenario Outline: Only a pending, unexpired invitation can be accepted
    Given <state>
    When an anonymous caller sends "POST /api/auth/invitations/<token>/accept" with body {"password":"Secret123!","fullName":"Fay Lin"}
    Then the response is a Problem <status> / <code>
    And no user exists with email "fay@example.com"

    Examples:
      | state                                                  | token          | status | code                        |
      | nothing else                                           | inv-unknown-QA | 404    | INVITATION_NOT_FOUND        |
      | invitation T's "expiresAt" is seeded to 1 hour ago     | T              | 410    | INVITATION_EXPIRED          |
      | Ben has revoked invitation T                           | T              | 410    | INVITATION_REVOKED          |
      | invitation T's status is seeded to "ACCEPTED"          | T              | 410    | INVITATION_ALREADY_ACCEPTED |

  @US-6 @AC-6.5 @P1
  Scenario: An anonymous caller whose email already has an account must log in first
    Given Fay has registered with "fay@example.com"
    When an anonymous caller sends "POST /api/auth/invitations/T/accept" with body {"password":"Other123!","fullName":"Fay Lin"}
    Then the response is a Problem 409 / EMAIL_ALREADY_REGISTERED
    And Fay is not a member of Acme
    And Fay's password is still "Secret123!"

  @US-6 @AC-6.6 @P1
  Scenario Outline: An anonymous acceptance needs a valid body
    When an anonymous caller sends "POST /api/auth/invitations/T/accept" with <body>
    Then the response is a Problem 400 / VALIDATION_FAILED
    And no user exists with email "fay@example.com"

    Examples:
      | body                                          |
      | no body                                       |
      | body {"password":"short","fullName":"Fay Lin"} |
      | body {"password":"Secret123!"}                |

  @US-6 @AC-6.7 @P1
  Scenario: Accepting when already a member is a conflict, not a server error
    Given a "PENDING" invitation for "cai@example.com" with role "ADMIN" and token "inv-cai-QA" is seeded in Acme
    When Cai sends "POST /api/auth/invitations/inv-cai-QA/accept" with "{token of Cai}" and no body
    Then the response is a Problem 409 / ALREADY_MEMBER
    And Cai's role in Acme is still "MEMBER"

  @US-6 @AC-6.8 @P1
  Scenario: A failed acceptance leaves no account behind
    Given invitation T's "expiresAt" is seeded to 1 hour ago
    And an anonymous acceptance of T has just been answered with a Problem 410 / INVITATION_EXPIRED
    When an anonymous caller sends "POST /api/auth/register" with body {"email":"fay@example.com","password":"Secret123!","fullName":"Fay Lin"}
    Then the response status is 201

  @US-6 @AC-6.9 @P1
  Scenario: No one joins when the organization is already full
    Given user "gus@example.com" is seeded as a 5th member of Acme with role "READER"
    When an anonymous caller sends "POST /api/auth/invitations/T/accept" with body {"password":"Secret123!","fullName":"Fay Lin"}
    Then the response is a Problem 409 / SEAT_LIMIT_REACHED
    And no user exists with email "fay@example.com"

  # Added at acceptance, 2026-10-12 (fix round, T20)
  @US-6 @AC-6.10 @P2
  Scenario Outline: A missing accept body reports each missing field, like an empty object
    When an anonymous caller sends "POST /api/auth/invitations/T/accept" with <body>
    Then the response is a Problem 400 / VALIDATION_FAILED
    And its "errors" member has exactly one entry each for the fields "fullName" and "password", and no other entry
    And no user exists with email "fay@example.com"

    Examples:
      | body                                              |
      | no body                                           |
      | an empty body with "Content-Type: application/json" |
      | body {}                                           |
```

### US-7: View members and change their roles (P1)

As an organization admin, I want to see who is in the organization and change their roles within my own role, so that
permissions match responsibilities.

```gherkin
Feature: View members and change their roles

  @US-7 @AC-7.1 @P1
  Scenario Outline: Every member can see the member list, nobody else can
    Given the Acme fixture
    When <actor> sends "GET /api/orgs/{acme}/members" with "{token of <actor>}"
    Then <outcome>

    Examples:
      | actor | outcome |
      | Ana   | the response status is 200 and the body lists exactly Ana OWNER, Ben ADMIN, Cai MEMBER, Dan READER, ordered by email, each with "userId", "email", "fullName", "role" and "joinedAt" and no member whose name contains "password" |
      | Dan   | the response status is 200 and the body lists the same 4 members |
      | Eve   | the response is a Problem 403 / FORBIDDEN |

  @US-7 @AC-7.2 @P1
  Scenario Outline: Who may change which role
    Given the Acme fixture
    When <actor> sends "PATCH /api/orgs/{acme}/members/{<target>.id}" with "{token of <actor>}" and body {"role":"<new role>"}
    Then <outcome>

    Examples:
      | actor | target | new role | outcome |
      | Ana   | Cai    | ADMIN    | the response status is 200, the body has "userId" "{Cai.id}" and "role" "ADMIN", and Cai's role is "ADMIN" |
      | Ana   | Ben    | OWNER    | the response status is 200 and Ben's role is "OWNER" |
      | Ben   | Cai    | READER   | the response status is 200 and Cai's role is "READER" |
      | Ben   | Dan    | ADMIN    | the response status is 200 and Dan's role is "ADMIN" |
      | Ben   | Cai    | OWNER    | the response is a Problem 422 / ROLE_CEILING and Cai's role is still "MEMBER" |
      | Ben   | Ana    | ADMIN    | the response is a Problem 422 / ROLE_CEILING and Ana's role is still "OWNER" |
      | Cai   | Dan    | MEMBER   | the response is a Problem 403 / FORBIDDEN and Dan's role is still "READER" |
      | Dan   | Cai    | READER   | the response is a Problem 403 / FORBIDDEN and Cai's role is still "MEMBER" |
      | Eve   | Cai    | READER   | the response is a Problem 403 / FORBIDDEN and Cai's role is still "MEMBER" |

  @US-7 @AC-7.3 @P1
  Scenario: Nobody can change their own role
    Given the Acme fixture
    When Ana sends "PATCH /api/orgs/{acme}/members/{Ana.id}" with "{token of Ana}" and body {"role":"ADMIN"}
    Then the response is a Problem 422 / SELF_ROLE_CHANGE
    And Ana's role is still "OWNER"

  @US-7 @AC-7.4 @P1
  Scenario Outline: The target must be a member of this organization
    Given the Acme fixture
    When Ana sends "PATCH /api/orgs/{acme}/members/<target>" with "{token of Ana}" and body {"role":"READER"}
    Then the response is a Problem 404 / MEMBER_NOT_FOUND

    Examples:
      | target       |
      | {Eve.id}     |
      | {unknown id} |

  @US-7 @AC-7.5 @P1
  Scenario Outline: An invalid role is rejected
    Given the Acme fixture
    When Ana sends "PATCH /api/orgs/{acme}/members/{Cai.id}" with "{token of Ana}" and body <body>
    Then the response is a Problem 400 / <code>
    And Cai's role is still "MEMBER"

    Examples:
      | body               | code              |
      | {}                 | VALIDATION_FAILED |
      | {"role":"GOD"}     | MALFORMED_REQUEST |

  @US-7 @AC-7.6 @P1
  Scenario: A demotion takes effect at once, even for a token issued earlier
    Given the Acme fixture
    And Ben holds "{token of Ben}", whose "orgs" claim says "ADMIN" for Acme
    And Ana has changed Ben's role to "MEMBER"
    When Ben sends "POST /api/orgs/{acme}/invitations" with that token and body {"email":"fay@example.com","role":"READER"}
    Then the response is a Problem 403 / FORBIDDEN

  @US-7 @AC-7.7 @P2
  Scenario: Setting the role a member already has is a no-op success
    Given the Acme fixture
    When Ana sends "PATCH /api/orgs/{acme}/members/{Cai.id}" with "{token of Ana}" and body {"role":"MEMBER"}
    Then the response status is 200 and Cai's role is "MEMBER"

  # Added at acceptance, 2026-10-12 (fix round, T17)
  @US-7 @AC-7.8 @P1
  Scenario Outline: A role sent as a number is rejected when changing a role
    Given the Acme fixture
    When Ana sends "PATCH /api/orgs/{acme}/members/{Dan.id}" with "{token of Ana}" and body {"role":<role>}
    Then the response is a Problem 400 / MALFORMED_REQUEST
    And Dan's role is still "READER"

    Examples:
      | role |
      | 0    |
      | 1    |
      | "2"  |
```

### US-8: Remove a member or leave (P1)

As an organization admin, I want to remove people who no longer need access, and as a member I want to leave, so that
access stays limited to the right people.

```gherkin
Feature: Remove a member or leave

  @US-8 @AC-8.1 @P1
  Scenario Outline: Who may remove whom
    Given the Acme fixture
    When <actor> sends "DELETE /api/orgs/{acme}/members/{<target>.id}" with "{token of <actor>}"
    Then <outcome>

    Examples:
      | actor | target | outcome |
      | Ana   | Ben    | the response status is 204 and Ben is no longer a member of Acme |
      | Ben   | Cai    | the response status is 204 and Cai is no longer a member of Acme |
      | Ben   | Dan    | the response status is 204 and Dan is no longer a member of Acme |
      | Ben   | Ana    | the response is a Problem 422 / ROLE_CEILING and Ana is still a member |
      | Cai   | Dan    | the response is a Problem 403 / FORBIDDEN and Dan is still a member |
      | Dan   | Cai    | the response is a Problem 403 / FORBIDDEN and Cai is still a member |
      | Eve   | Cai    | the response is a Problem 403 / FORBIDDEN and Cai is still a member |

  @US-8 @AC-8.2 @P1
  Scenario: A removed member loses access at once
    Given the Acme fixture
    And Cai holds "{token of Cai}"
    And Ben has removed Cai from Acme
    When Cai sends "GET /api/orgs/{acme}" with that token
    Then the response is a Problem 403 / FORBIDDEN
    And "GET /api/orgs" by Cai with that token returns []

  @US-8 @AC-8.3 @P1
  Scenario Outline: Any member can leave
    Given the Acme fixture
    When <actor> sends "DELETE /api/orgs/{acme}/members/{<actor>.id}" with "{token of <actor>}"
    Then the response status is 204 and <actor> is no longer a member of Acme

    Examples:
      | actor |
      | Ben   |
      | Cai   |
      | Dan   |

  @US-8 @AC-8.4 @P1
  Scenario Outline: The last owner can't leave
    Given the Acme fixture
    And <setup>
    When Ana sends "DELETE /api/orgs/{acme}/members/{Ana.id}" with "{token of Ana}"
    Then <outcome>

    Examples:
      | setup                              | outcome                                                              |
      | nothing else                       | the response is a Problem 409 / LAST_OWNER and Ana is still "OWNER"  |
      | Ana has changed Ben's role to OWNER | the response status is 204 and Ana is no longer a member of Acme    |

  @US-8 @AC-8.5 @P1
  Scenario: Removing someone who isn't a member is not found
    Given the Acme fixture
    When Ana sends "DELETE /api/orgs/{acme}/members/{Eve.id}" with "{token of Ana}"
    Then the response is a Problem 404 / MEMBER_NOT_FOUND

  @US-8 @AC-8.6 @P2
  Scenario: Removing a member frees a seat
    Given the Acme fixture
    And user "gus@example.com" is seeded as a 5th member of Acme with role "READER"
    And Ben has removed Gus from Acme
    When Ben invites "fay@example.com" as "MEMBER"
    Then the response status is 201
```

**AC count:** 65 acceptance criteria across 8 stories: US-1 8, US-2 9, US-3 9, US-4 7, US-5 10, US-6 9, US-7 7, US-8 6.
P0: 22, P1: 36, P2: 7.
**Added at acceptance (2026-10-12), for the fix round:** 7 ACs, all for new behaviour: AC-1.9, AC-2.10, AC-2.11,
AC-5.11, AC-5.12, AC-6.10 and AC-7.8 (P1: 3, P2: 4). That makes 72 in total. The original 65 are unchanged.

## Changes to existing code (approved by the user on 2026-10-12)

Every file that existed at `aa2234c` is the user's code. Only the items below may change it, and only once the user
approves the item. Each item is small and can be approved or rejected on its own; rejecting an item removes the tasks
and ACs that depend on it (named in each row). The user's approval will be recorded here with a date.

| ID  | Change                                                                                                                                                                                                                                                                                                                                                                                                           | Files                                                                                                                                                                 | Why                                                                                                 | Risk                                                                                                                                    | Depends on | Task        |
|-----|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------|------------|-------------|
| E1  | Move the test to package `com.example.authservice` and give it a test-only `app.jwtSecret` through `@SpringBootTest(properties = …)`. The test method stays unchanged.                                                                                                                                                                                                                                           | `auth-service/src/test/java/org/authservice/AuthServiceApplicationTests.java` → `auth-service/src/test/java/com/example/authservice/AuthServiceApplicationTests.java` | Fixes the red build. Without a secret the context can't start since gateway T3.                     | Low                                                                                                                                     | —          | T1          |
| E2  | `SecurityConfig`: permit `/error`; register a Problem 401 entry point (with `WWW-Authenticate: Bearer`) and a Problem 403 access-denied handler.                                                                                                                                                                                                                                                                 | `configuration/SecurityConfig.java`                                                                                                                                   | Ends the "everything is an empty 403" defect; 401 for unauthenticated.                              | Medium (security config)                                                                                                                | E1         | T2          |
| E3  | `SecurityConfig` and `OpenEndpoints`: replace the `/api/auth/**` permit with the three exact public operations (method + path); remove the `OpenEndpoints` constants `AUTHENTICATION` (`/api/v1/auth/**`), `ACTUATOR` and `H2_CONSOLE`. Swagger entries stay.                                                                                                                                                    | `configuration/SecurityConfig.java`, `enums/OpenEndpoints.java`                                                                                                       | Deny by default; matches the gateway's public list (D7).                                            | Medium: any anonymous call to another `/api/auth` path now gets 401 (there are none today).                                             | E2         | T4          |
| E4  | `JwtAuthenticationFilter`: match `Bearer` case-insensitively; replace `catch (Exception)` with handling of the specific token and user-lookup failures (the caller stays anonymous); reject disabled users; never log tokens; no stack traces for bad tokens.                                                                                                                                                    | `configuration/JwtAuthenticationFilter.java`                                                                                                                          | RFC 9110 scheme rule (gateway Known issue 6); no swallowed errors; suspended users locked out (D4). | Medium (authentication)                                                                                                                 | E2, E5, E6 | T3          |
| E5  | `JwtTokenProvider`: verify HS512 only, require `sub` (a UUID) and `exp`; read `app.jwtSecret` / `app.jwtExpirationInMs` from a typed, validated properties record instead of `@Value` (property names and env var unchanged); fail at startup if the secret isn't standard Base64 of at least 64 bytes; stop swallowing exceptions in `validateToken`. Token **issuing** (claims, HS512, expiry) doesn't change. | `service/impl/JwtTokenProvider.java` (+ new `configuration/JwtProperties.java`)                                                                                       | Same acceptance rule as the gateway; fail fast instead of a 500 at first login.                     | Medium (authentication)                                                                                                                 | E1         | T3          |
| E6  | `UserPrincipal`: carry the user's status; `isEnabled()` returns `false` only for `SUSPENDED`.                                                                                                                                                                                                                                                                                                                    | `security/UserPrincipal.java`                                                                                                                                         | D4.                                                                                                 | Low                                                                                                                                     | —          | T3          |
| E7  | `OrganizationRole`: add an explicit rank and an `isAtLeast(OrganizationRole)` method. Constants and their order stay unchanged.                                                                                                                                                                                                                                                                                  | `enums/OrganizationRole.java`                                                                                                                                         | Replaces the inverted `ordinal()` comparison (D3).                                                  | Low                                                                                                                                     | —          | T5          |
| E8  | `OrgGuard`: rewrite to read the caller from `UserPrincipal`, look up the membership in the database, and compare with E7. Removes the `Jwt` and `ObjectMapper` usage. The bean name `orgGuard` and the method `hasRole(UUID, Authentication, String)` stay; an `isMember(UUID, Authentication)` method is added.                                                                                                 | `security/OrgGuard.java`                                                                                                                                              | Organization authorization never works today (D3).                                                  | High (authorization); covered by the AC-5.2, AC-4.5 and AC-7.2 matrices                                                                 | E7, E10    | T5          |
| E9  | `OrganizationController`: make the two fields `private final`.                                                                                                                                                                                                                                                                                                                                                   | `controller/OrganizationController.java`                                                                                                                              | Fixes the NPE behind `POST /api/orgs`.                                                              | Low                                                                                                                                     | —          | T5          |
| E10 | Repositories: add derived query methods only (no change to existing methods). `OrgMembershipRepository`: lookups by user id + org id, by org id, counts, existence. `InvitationRepository`: pending/unexpired lookups and counts by org and email, lookup by id + org. `UserRepository`: none expected.                                                                                                          | `repository/OrgMembershipRepository.java`, `repository/InvitationRepository.java`                                                                                     | Needed by the guard and the rules. Additive.                                                        | Low                                                                                                                                     | —          | T5, T11–T14 |
| E11 | `build.gradle`: remove the 6 unused dependency lines listed in D5. Nothing else in the file changes.                                                                                                                                                                                                                                                                                                             | `auth-service/build.gradle`                                                                                                                                           | D5.                                                                                                 | Low–medium: a removed starter might have supplied a class used transitively (see T7 stop condition)                                     | E8         | T7          |
| E12 | (Optional) `build.gradle`: jjwt `0.11.5` → `0.12.5` (3 lines), and migrate `JwtTokenProvider` to the 0.12 API with the same behaviour.                                                                                                                                                                                                                                                                           | `auth-service/build.gradle`, `service/impl/JwtTokenProvider.java`                                                                                                     | Same version as the gateway; drops deprecated API (D5).                                             | Medium; T3's tests must pass unchanged                                                                                                  | E5, T3     | T9          |
| E13 | `application.yaml`: move `hibernate.ddl-auto`, `show-sql`, `properties.hibernate.format_sql` and `defer-datasource-initialization` from `spring.datasource` to `spring.jpa`, values unchanged. The commented `dialect` line moves with them.                                                                                                                                                                     | `src/main/resources/application.yaml`                                                                                                                                 | They're ignored today (D6).                                                                         | Low: SQL starts being logged                                                                                                            | —          | T8          |
| E14 | `application.yaml`: add `spring.jpa.open-in-view: false`.                                                                                                                                                                                                                                                                                                                                                        | `src/main/resources/application.yaml`                                                                                                                                 | D6.                                                                                                 | Low: services must map entities to DTOs inside their transactions (P3 already requires it)                                              | —          | T8          |
| E15 | `application.yaml`: turn the H2 console off (`spring.h2.console.enabled: false`, path line removed).                                                                                                                                                                                                                                                                                                             | `src/main/resources/application.yaml`                                                                                                                                 | D7; it's unreachable once E3 removes its permit.                                                    | Low; the user loses the console unless they reject E3's `H2_CONSOLE` removal and this item                                              | E3         | T8          |
| E16 | `AuthServiceImpl`: normalise the email (trim, lower-case) in `register` and `login`; make `register` `@Transactional`.                                                                                                                                                                                                                                                                                           | `service/impl/AuthServiceImpl.java`                                                                                                                                   | D12.                                                                                                | Low                                                                                                                                     | —          | T10         |
| E17 | Request DTO limits: `RegisterRequest` and `AcceptInvitationRequest` (password ≤ 72 bytes in UTF-8, `fullName` ≤ 100), `RegisterRequest.email` and `InviteMemberRequest.email` ≤ 254, `CreateOrganizationRequest.name` ≤ 100. Existing constraints and messages stay.                                                                                                                                             | `dto/request/RegisterRequest.java`, `AcceptInvitationRequest.java`, `InviteMemberRequest.java`, `CreateOrganizationRequest.java`                                      | Turns 500s into 400s (AC-2.9, AC-4.2).                                                              | Low                                                                                                                                     | —          | T10, T11    |
| E18 | Organizations: implement `createOrganization` (set `seatLimit`, return the body), `findByUser`, and add "get one"; add `myRole` to `OrganizationResponse`; add the list and get endpoints to `OrganizationController`, and remove its `UserRepository` usage (P1).                                                                                                                                               | `service/OrganizationService.java`, `service/impl/OrganizationServiceImpl.java`, `controller/OrganizationController.java`, `dto/response/OrganizationResponse.java`   | US-4.                                                                                               | Medium (contract of `POST /api/orgs` changes from empty to a full body)                                                                 | E2, E8, E9 | T11         |
| E19 | Invitations: `inviteMember` enforces seat limit, ceiling, duplicates and returns the invitation with its token; add list and revoke. Replace the bare `RuntimeException`.                                                                                                                                                                                                                                        | same four files as E18                                                                                                                                                | US-5.                                                                                               | Medium                                                                                                                                  | E18        | T12         |
| E20 | Acceptance: move the whole flow from `AuthController.acceptInvitation` into one `@Transactional` service method with typed exceptions, the email match, the already-member and seat checks, and the new response. The project branch in `InvitationServiceImpl.acceptInvitation` is left as it is (not reachable in phase 1). `AuthController` no longer uses `UserRepository`.                                  | `controller/AuthController.java`, `service/InvitationService.java`, `service/impl/InvitationServiceImpl.java`                                                         | US-6; closes the "any account can accept" hole.                                                     | Medium–high (public operation)                                                                                                          | E16, E19   | T13         |
| E21 | Members: implement `changeMemberRole` and `removeMember` (signatures gain the acting user), add list members; controller endpoints.                                                                                                                                                                                                                                                                              | `service/OrganizationService.java`, `service/impl/OrganizationServiceImpl.java`, `controller/OrganizationController.java`                                             | US-7, US-8.                                                                                         | Medium                                                                                                                                  | E18        | T14         |
| X1  | Cross-service docs (not the user's code, recorded for visibility): in `docs/api/api-gateway.openapi.yaml`, update the note that auth-service accepts only `Bearer`, the note that auth-service has no standard error body, and the auth/orgs `contractNote`; bump the patch version.                                                                                                                             | `docs/api/api-gateway.openapi.yaml`                                                                                                                                   | The gateway contract would otherwise be wrong.                                                      | Low (docs)                                                                                                                              | T3, T15    | T15         |
| E22 | (Approved by the user on 2026-10-12.) `spring.jpa.show-sql: false` in `application.yaml`; `spring.jpa.show-sql: true` in `application-dev.yaml`. Nothing else in either file.                                                                                                                                                                                                                                    | `src/main/resources/application.yaml`, `src/main/resources/application-dev.yaml`                                                                                      | Keeps SQL out of non-dev logs after E13 made the key effective.                                     | Low                                                                                                                                     | E13        | T9b         |
| E23 | **Approved by the user on 2026-10-12.** Map lock failures to 409 `CONFLICT` (D13): `jakarta.persistence.LockTimeoutException`, `jakarta.persistence.PessimisticLockException`, and Spring's `PessimisticLockingFailureException` (with subclasses). Logged at WARN, without a stack trace.                                                                                                                       | `exception/ApiExceptionHandler.java` (created in T2)                                                                                                                  | Today a lock timeout is a 500.                                                                      | Low                                                                                                                                     | —          | T16         |
| E29 | **Proposed for a later round; needs user approval.** One key in `application.yaml`: `logging.level.org.hibernate.orm.jdbc.error: ERROR` (FU-6).                                                                                                                                                                                                                                                                  | `src/main/resources/application.yaml`                                                                                                                                 | BUG-5: H2 and PostgreSQL constraint messages contain the email.                                     | Low: Hibernate's SQL-error WARN lines disappear. The handler still logs the exception type, and unexpected failures still log at ERROR. | —          | FU-6        |
| E24 | **Approved (2026-10-12).** Reject numeric values for enums in request bodies (Jackson's "fail on numbers for enums"). Preferably one key in `application.yaml`; if configuration can't do it, an annotation on the two `role` record components.                                                                                                                                                                 | `src/main/resources/application.yaml` (one key), or `dto/request/InviteMemberRequest.java` and `dto/request/ChangeMemberRoleRequest.java`                             | BUG-2.                                                                                              | Low: it applies to every enum in a request body, and only roles are such enums today                                                    | —          | T17         |
| E25 | **Approved (2026-10-12).** `AuthServiceImpl.login`: a password over 72 UTF-8 bytes is answered as bad credentials before BCrypt is called.                                                                                                                                                                                                                                                                       | `service/impl/AuthServiceImpl.java`                                                                                                                                   | BUG-4.                                                                                              | Low                                                                                                                                     | —          | T18         |
| E26 | **Approved (2026-10-12).** `AuthServiceImpl.register`: flush the insert, and turn a unique-constraint violation on the user's email into `EmailAlreadyExistsException`. The accept flow calls `register`, so it's covered too.                                                                                                                                                                                   | `service/impl/AuthServiceImpl.java`                                                                                                                                   | BUG-3, Q6.                                                                                          | Low                                                                                                                                     | —          | T18         |
| E27 | **Approved (2026-10-12).** Revoke applies the role ceiling (D13, Q1).                                                                                                                                                                                                                                                                                                                                            | `service/impl/OrganizationServiceImpl.java`                                                                                                                           | Q1.                                                                                                 | Low; changes 204 to 422 for an `ADMIN` revoking an `OWNER` invitation                                                                   | —          | T19         |
| E28 | **Approved (2026-10-12).** An anonymous accept with a missing or empty body reports `fullName` and `password` as separate `errors` entries (D13, Q2). **Addition approved by the user on 2026-10-12 (T20 deviation):** `AuthController` also gains one constructor-injected `private final jakarta.validation.Validator validator` field, with its imports, outside the accept method.                           | `service/impl/InvitationServiceImpl.java` and/or `controller/AuthController.java` (the accept method only)                                                            | Q2.                                                                                                 | Low                                                                                                                                     | —          | T20         |

**Will not be touched in phase 1** (any change needs a new approval): `AuthServiceApplication.java`, `aop/Logging.java`,
`configuration/CacheConfig.java`, `configuration/OpenApiConfiguration.java`, `security/ProjectGuard.java`,
`service/impl/ProjectRoleService.java`, `service/impl/CustomUserDetailsService.java`, `service/AuthService.java`,
`util/TokenGenerator.java`, every entity in `model/` (`AuditLog`, `Invitation`, `OrgMembership`, `Organization`,
`Project`, `ProjectMembership`, `User`), `repository/AuditLogRepository.java`, `OrganizationRepository.java`,
`ProjectRepository.java`, `ProjectMembershipRepository.java`, `UserRepository.java`, `dto/request/LoginRequest.java`,
`dto/response/LoginResponse.java`, `OrgClaim.java`, `UserResponse.java`, `enums/` other than `OrganizationRole` and
`OpenEndpoints`, `exception/EmailAlreadyExistsException.java`, `exception/OrgRoleCeilingException.java`,
`application-dev.yaml`, `settings.gradle`, the Gradle wrapper, `gradlew*`, `.gitattributes`, every `.gitignore`, and
everything outside `auth-service/` except `docs/api/auth-service.openapi.yaml` (new) and X1. In `build.gradle`, only E11
and E12 lines change: plugins, versions of anything else, `group`, the toolchain and the `test` block stay.

## Engineering guardrails (binding for every task)

Every task must meet all of these. A task isn't done while any rule is broken. If a rule blocks you, stop and ask in
your handoff; don't work around it.

### Scope control

- **G1. Allowed files only.** A task creates, modifies, moves or deletes only the files on its **Allowed files** line.
  New files may only go under:
    - `auth-service/src/main/java/com/example/authservice/<existing package>/` (no new main packages);
    - `auth-service/src/test/java/com/example/authservice/**` (test packages mirror main; plus `support/` for shared
      test helpers);
    - `docs/api/auth-service.openapi.yaml`;
    - `auth-service/README.md`.

  `auth-service/src/test/resources/` is not allowed; supply test properties in test code.
- **G2. Existing code changes only through approved E-items.** A task may change a file that existed at `aa2234c` only
  as described by the E-items it names, and only after the user approved them. If an approved E-item turns out to need
  more than it describes, stop and ask.
- **G3. Files from earlier tasks** may change only if the task's Allowed files line names them. When a later task
  updates an earlier test, it may only strengthen its assertions, never weaken them (G6).
    - **Exception approved by the user on 2026-10-12 (T4):** `configuration/JwtSecretStartupTest` was given its own H2
      URL, so that the startup contexts it closes can't drop the shared `authdb`.
    - **Rule from then on:** any test that starts and closes its own application context must use its own database URL,
      never the shared `authdb`.
- **G4. No drive-by changes.** No refactors, reformatting, import reshuffles, renames, Lombok style changes, comment
  edits or "while I'm here" fixes. Defects outside the task go into your handoff.
- **G5. No version or dependency changes** beyond approved E11 and E12. Spring Boot, the plugins, the Gradle wrapper and
  the Java toolchain stay.
- **G6. Tests stay honest.** No `@Disabled`, no assumptions that skip, no weakened assertions, no deleted tests.
  `cd auth-service && ./gradlew build` is green at the end of every task. Report total, failed and skipped test counts.
- **G7. No git operations.** No `git add`, `commit`, `stash`, `restore`, `checkout`, `reset`, `mv` or `rm` (use plain
  file operations). Don't touch any `.gitignore`. The user commits. Each handoff includes `git status --short` and
  `git diff --stat`, and lists new (untracked) files with a one-line summary each, because `git diff` doesn't show them.
- **G8. Self-review the diff** before handing off: only allowed files, no unrelated whitespace, no debug output, no
  commented-out code, no secrets other than the test-only secret in test code.
- **G9. Service boundaries.** auth-service never reads another service's database, code or configuration, and never
  calls the gateway. No shared library is introduced.

### Package layout (decided)

| Package (main and test)       | New contents in phase 1                                                                               |
|-------------------------------|-------------------------------------------------------------------------------------------------------|
| `controller`                  | Endpoints only: mapping, `@Valid`, `@PreAuthorize`, principal extraction, status codes                |
| `service`, `service/impl`     | Business rules and transactions                                                                       |
| `repository`                  | Additive derived queries (E10)                                                                        |
| `dto/request`, `dto/response` | Records for every request and response body                                                           |
| `exception`                   | The typed exception base class, specific exceptions, the error-code enum, the `@RestControllerAdvice` |
| `security`                    | `OrgGuard`, the Problem 401 entry point and 403 handler                                               |
| `configuration`               | `SecurityConfig`, typed `@ConfigurationProperties` records, a `Clock` bean if needed                  |
| `support` (test only)         | Test secret, token factory, Acme fixture builder, base test annotation                                |

### Patterns and practices

- **P1. Layering.** Controller → service → repository. Controllers never use repositories or entities; services own
  business rules. Touched controller methods lose their direct `UserRepository` use (E18, E20).
- **P2. Transactions.** `@Transactional` on every service method that writes; `@Transactional(readOnly = true)` on read
  methods that touch more than one query; never on controllers or guards. A use case that writes several rows (accept
  invitation, create organization) is one transaction.
- **P3. DTOs, not entities.** No controller accepts or returns an entity. Mapping happens inside the service
  transaction (E14 turns open-in-view off). Response records never contain password hashes or acceptance tokens, except
  the one create-invitation response.
- **P4. Validation.** Every request body is a record with Bean Validation constraints and is bound with `@Valid`. Path
  ids are typed `UUID`. Emails are normalised after validation (D12).
- **P5. Authorization.** Every org endpoint has an explicit `@PreAuthorize` using `@orgGuard`. Roles come from the
  database (D3). Compare roles only with `OrganizationRole.isAtLeast`: never `ordinal()`, `compareTo` or string
  comparison. Never authorize on `X-User-Id` or the `orgs` claim. Ceiling, seat, self and last-owner rules live in
  services.
- **P6. Errors.** Throw typed exceptions that extend one base class carrying the HTTP status and the D2 `code`. No bare
  `RuntimeException`, no new `@ResponseStatus`. One `@RestControllerAdvice` builds every Problem with `ProblemDetail`.
  It must map `AccessDeniedException`/`AuthorizationDeniedException` to 403 and `AuthenticationException` to 401
  explicitly, so a catch-all handler can never turn them into 500. No stack traces, class names, SQL or hosts in bodies.
- **P7. Configuration.** New settings bind to `@ConfigurationProperties` records with `@Validated` constraints; startup
  fails on invalid values. No new `@Value`. The only existing `@Value` uses (in `JwtTokenProvider`) go away through E5.
- **P8. Injection.** Constructor injection with `private final` fields (`@RequiredArgsConstructor`). No field or setter
  injection, no static mutable state.
- **P9. Java hygiene.** Records for DTOs; `Optional` instead of `null` returns; named constants; no `catch (Exception)`
  that swallows; SLF4J logging; never log passwords, tokens, `Authorization` values or acceptance tokens. New and
  rewritten code that needs the current time gets it from an injected `java.time.Clock`. No TODO unless it names a later
  phase.
- **P10. HTTP.** `Location` headers are relative (`/api/orgs/{id}`), never built from the request host (the gateway
  replaces `Host`). No CORS headers (`cors` stays disabled). No `X-Request-Id` response header.
- **P11. Tests.** Per D10. Behaviour-focused, one AC per test where practical, `@Tag("AC-x.y")`, Given/When/Then
  structure, AssertJ, deterministic (no `Thread.sleep`, no external network, ephemeral data). A test that covers only
  some rows of an outline says so in its display name, and the handoff names the missing rows and the task that adds
  them.
- **P12. Don't guess framework APIs.** Spring Boot 4.1 moved test annotations and auto-configuration classes to new
  modules and packages (for example `@AutoConfigureMockMvc`, `@DataJpaTest`), and Spring Security 7 changed
  method-security exceptions. Check the resolved jars before using an API, and say in your handoff what you verified.

### Fix rounds after QA

- **F1. qa's tests are read-only.** Don't edit, move, disable or import from qa's test package (
  `auth-service/src/test/java/com/example/authservice/qa/`). If a qa test contradicts this spec, stop and report the
  test, the AC and your evidence.
- **F2. Build state.** At the end of each fix task, the build may fail only on qa reproductions of bugs that a later fix
  task in the same round fixes, as listed in that task. The last fix task ends with a fully green
  `./gradlew clean build`.
- **F3. New tests** are tagged with AC IDs and live in backend-swe's packages. Never tag a test `BUG-auth-service-n`;
  those tags are qa's.
- **F4. Order and handoffs.** Do fix tasks in order; report each task separately (status, files, test counts,
  `git status --short`, `git diff --stat`, new files).

## Tasks for backend-swe

Do the tasks in order, batch by batch. **Stop at each review point** and hand off; the user reviews the diff before the
next batch starts. Every task follows the guardrails. A task may only start once the E-items it names are approved; if
one isn't, skip the task (and its ACs) and say so. Run `cd auth-service && ./gradlew build` at the end of every task.

### Batch A: green build and security core (small; review point A)

**T1. Green-build baseline.** E-items: E1.

- Change: move the test file to `com.example.authservice`, change its package line, add the test-only secret through
  `@SpringBootTest(properties = …)`.
- ACs: none (enabler).
- Done when: `./gradlew build` is green with 1 test passing.
- **Stop condition:** if `contextLoads` then fails for any other reason (for example Okta, OAuth2 client or
  Elasticsearch auto-configuration), stop and report the exact error. Don't remove dependencies (that's E11, T7) or add
  exclusions.
- **Allowed files:** move `auth-service/src/test/java/org/authservice/AuthServiceApplicationTests.java` to
  `auth-service/src/test/java/com/example/authservice/AuthServiceApplicationTests.java` (the empty `org/authservice`
  directories go with it).
- **Must not:** change the test method; touch `build.gradle`, main code or configuration; add tests.

**T2. Problem Details foundation.** E-items: E2.

- Change: the typed exception base class and the D2 code enum; one `@RestControllerAdvice` on `ProblemDetail`/
  `ResponseEntityExceptionHandler` (including the `requestId` rule and `errors` for validation); mappings for
  `EmailAlreadyExistsException` (409 `EMAIL_ALREADY_REGISTERED`), `OrgRoleCeilingException` (422 `ROLE_CEILING`),
  `BadCredentialsException`/`DisabledException` at login (401 `INVALID_CREDENTIALS`),
  `DataIntegrityViolationException` (409 `CONFLICT`), access-denied (403), and unexpected exceptions (500); the Problem
  401 entry point and 403 handler; `SecurityConfig` per E2. Test support: the test secret constant, the base test
  annotation, the Acme fixture builder, the token factory.
- ACs: AC-1.1, AC-1.2, AC-1.3, AC-1.4, AC-1.5, AC-1.6, AC-1.7, AC-1.8, AC-2.4. Also AC-2.2 row 1 only (row 2 needs T10).
- Done when: those tests pass and the build is green.
- **Allowed files:**
    - modify `configuration/SecurityConfig.java` (E2 only: the `/error` permit and the `exceptionHandling` wiring);
    - create classes in `exception/` and, for the entry point and handler, in `security/`;
    - create `auth-service/src/test/java/com/example/authservice/support/**` and tests in `.../exception/` and
      `.../security/`.
- **Must not:** modify `EmailAlreadyExistsException`, `OrgRoleCeilingException`, `JwtAuthenticationFilter`,
  `JwtTokenProvider` or any controller; change any success response; enable CORS; set `X-Request-Id` on responses.

**T3. Token validation and user status.** E-items: E4, E5, E6.

- Change: per E4–E6. `JwtProperties` record under the existing `app` prefix (`app.jwtSecret`, `app.jwtExpirationInMs`),
  validated at startup.
- ACs: AC-2.3, AC-2.6, AC-2.7, AC-2.8, AC-3.1, AC-3.2, AC-3.3, AC-3.4, AC-3.5, AC-3.8, AC-3.9.
- Done when: those tests pass. In your handoff, state whether the "HS256 with the shared secret" row of AC-3.1 failed
  before your change (pm predicts it did).
- **Allowed files:**
    - modify `configuration/JwtAuthenticationFilter.java`, `service/impl/JwtTokenProvider.java`,
      `security/UserPrincipal.java`;
    - create `configuration/JwtProperties.java` (and one `@EnableConfigurationProperties` or
      `@ConfigurationPropertiesScan` holder in `configuration/` if needed);
    - modify `support/` test helpers; create tests in `.../security/`, `.../configuration/` and `.../service/impl/`.
- **Must not:** change the issued claims, the HS512 signing, the expiry or the property names and env var; change
  `jjwt` (that's T9); touch `CustomUserDetailsService`; log tokens; give `app.jwtSecret` a default.

**T4. Public operations and open endpoints.** E-items: E3.

- ACs: AC-3.6, AC-3.7; AC-2.8 and AC-1.2 must still pass.
- Done when: those tests pass.
- **Allowed files:** modify `configuration/SecurityConfig.java` (the `authorizeHttpRequests` block only) and
  `enums/OpenEndpoints.java` (delete three constants); create tests in `.../configuration/`.
- **Must not:** add a public operation; remove the Swagger entries; change filter order or session policy.

**T5. Organization authorization core.** E-items: E7, E8, E9, E10 (the `OrgMembershipRepository` lookup only).

- Change: rank and `isAtLeast` on `OrganizationRole`; `OrgGuard` per E8; `OrganizationController` fields
  `private final`. The existing invite endpoint keeps its
  `@PreAuthorize("@orgGuard.hasRole(#orgId, authentication, 'ADMIN')")`.
- ACs: unit tests for the rank (every pair of roles) and the guard (member, non-member, unknown org, each role against
  each required role, unknown role string fails closed). End to end: the AC-5.2 rows that don't involve the ceiling (Ana
  OWNER/ADMIN, Ben ADMIN/MEMBER/READER → 201; Cai, Dan, Eve → 403), tagged `AC-5.2` with a display name saying "
  authorization rows only".
- Done when: those tests pass, `POST /api/orgs` no longer fails with an NPE (it may still return the empty body until
  T11), and the build is green.
- **Allowed files:** modify `enums/OrganizationRole.java`, `security/OrgGuard.java`,
  `controller/OrganizationController.java` (the two field declarations only),
  `repository/OrgMembershipRepository.java` (add methods only); create tests in `.../security/`, `.../enums/` and
  `.../controller/`.
- **Must not:** reorder or rename enum constants; read the `orgs` claim or `X-User-Id`; cache roles; touch
  `ProjectGuard` or `ProjectRoleService`; change any other controller line.

**Review point A.** Hand off to the main session. The user reviews T1–T5 (security config, filter, token provider,
guard) before Batch B starts.
**Passed on 2026-10-12.** The main session verified the build at 138 tests, all green. The user approved T1–T5 and the
start of Batch B.

### Batch B: contract and cleanup (review point B)

**T6. auth-service contract.**

- File: `docs/api/auth-service.openapi.yaml`, OpenAPI 3.1. Servers: `http://localhost:8080` (through the gateway) and
  `http://localhost:8081` (direct, development). Every phase-1 operation from US-1 to US-8 with request and response
  schemas, status codes and `code` values; the three public operations marked with `security: []`; the `bearerAuth`
  scheme with the C2 claims; the `Problem` schema compatible with the gateway's (same members plus `code` and `errors`;
  `requestId` optional here); the D2 table; the D11 rules in operation descriptions.
- ACs: documents all.
- Done when: the file parses as OpenAPI 3.1 (name the validator, or say none was available), and every D2 row and every
  story's operations appear.
- **Allowed files:** create `docs/api/auth-service.openapi.yaml`.
- **Must not:** touch code; document behaviour that isn't in this spec (raise gaps with pm).

**T7. Remove unused dependencies.** E-items: E11.

- Done when: `./gradlew build` is green with the same test count as after T5;
  `./gradlew dependencies --configuration runtimeClasspath` no longer lists Okta, `spring-security-oauth2-client`,
  `spring-security-webauthn` or Elasticsearch clients. Report the before/after count of resolved runtime artifacts.
- **Stop condition:** if compilation or a test fails because a removed dependency supplied a class used by existing
  code (for example `org.aspectj` in `aop/Logging.java`), stop and report the class and file. Don't change, delete or
  add anything to work around it.
- **Allowed files:** modify `auth-service/build.gradle` (delete exactly the six lines).
- **Must not:** add any dependency; touch other lines.

**T8. Persistence configuration.** E-items: E13, E14, E15.

- Done when: the build is green; the app starts with `--spring.profiles.active=dev` and logs Hibernate SQL;
  `GET /h2-console` directly on 8081 returns a 401 Problem (AC-3.6 row). Report whether any test broke with open-in-view
  off (it should not; if it does, the code under test violates P3: report, don't patch).
- **Allowed files:** modify `auth-service/src/main/resources/application.yaml`.
- **Must not:** change the datasource URL, credentials, port or `app.*` keys; touch `application-dev.yaml`.

**T9. (Only if E12 is approved) Upgrade jjwt to 0.12.5.**

- Done when: every test from T3 passes **unchanged**, apart from the approved exception below, and the build is green. (
  2026-10-12) The manual gateway check moved to T11's Done when, because `GET /api/orgs` only answers 200 with a real
  body after T11.
- **Stop condition:** if any T3 test would need changing, stop and report.
- **Allowed files:** modify `auth-service/build.gradle` (the three jjwt lines), `service/impl/JwtTokenProvider.java`,
  and `support/` test helpers that mint tokens.
- **G3 exception, approved by the user on 2026-10-12.** jjwt 0.12 removed `Jwts.parserBuilder()`. In exactly three
  lines, `security/LoginTokenTest.java:66` and `:108` and `service/impl/JwtTokenProviderTest.java:66`, change
  `Jwts.parserBuilder()` to `Jwts.parser()`, plus any call that 0.12 requires on that same chain, with the same meaning.
  No assertion changes, and no other test edits.
- **Must not:** change claims, algorithm, key derivation or property names.

**T9b. SQL logging only in the `dev` profile.** E-items: E22.

- Change: `spring.jpa.show-sql: false` in `application.yaml`, and `spring.jpa.show-sql: true` in `application-dev.yaml`.
- Done when: the build is green with the same test count. Started with `--spring.profiles.active=dev`, the service logs
  Hibernate SQL. Started without the profile (with `JWT_SECRET` set), it doesn't.
- **Allowed files:** modify `auth-service/src/main/resources/application.yaml` (the `spring.jpa.show-sql` value only)
  and `auth-service/src/main/resources/application-dev.yaml` (add the `spring.jpa.show-sql: true` key only).
- **Must not:** touch any other key, including `format_sql` and the dev `app.jwtSecret`.

**Review point B.** **Passed with T9 resumed (2026-10-12).** T6–T8 are green at 138 tests (verified by the main
session). T9 stopped correctly at its stop condition. The user approved the G3 exception above and E22. backend-swe
finishes T9 and T9b, then continues with Batch C without another review point.

### Batch C: organizations and members (review point C)

**T10. Email normalisation and field limits.** E-items: E16, E17 (`RegisterRequest`, `AcceptInvitationRequest`).

- ACs: AC-2.1, AC-2.2 (both rows), AC-2.5, AC-2.9.
- Done when: those tests pass. Report what a 73-byte password did before the change.
- **Allowed files:** modify `service/impl/AuthServiceImpl.java`, `dto/request/RegisterRequest.java`,
  `dto/request/AcceptInvitationRequest.java`; create at most one custom constraint (UTF-8 byte length) in
  `dto/request/`; create tests.
- **Must not:** change `LoginRequest`, `CustomUserDetailsService` or the login token; remove `SecurityContextHolder` use
  in `login` (G4).

**T11. Create, list and view organizations.** E-items: E17 (`CreateOrganizationRequest`), E18.

- Change: plus an `app.orgs.free-seat-limit` setting (default 5) in a validated properties record, and its default in
  `application.yaml`.
- ACs: AC-4.1 to AC-4.7.
- **Allowed files:** modify `service/OrganizationService.java`, `service/impl/OrganizationServiceImpl.java`,
  `controller/OrganizationController.java`, `dto/response/OrganizationResponse.java`,
  `dto/request/CreateOrganizationRequest.java`, `repository/OrgMembershipRepository.java` (add methods only),
  `src/main/resources/application.yaml` (add the `app.orgs` key only); create the properties record in `configuration/`;
  create tests. (2026-10-12) Modify `exception/UnexpectedErrorTest.java`, `exception/ProblemDetailsTest.java` (AC-1.5)
  and `security/BearerAuthenticationTest.java` (AC-3.2, AC-3.3), only to bring them to their final form: `GET /api/orgs`
  as the request, with the 200 checks. Assertions may only be strengthened, never weakened (G3).
- Done when (2026-10-12, moved from T9): AC-4.1 to AC-4.7 pass, the build is green, and a manual check succeeds. With
  both services on the `dev` profile, register and log in through 8080; `GET /api/orgs` through 8080 with that token
  then returns 200.
- **Must not:** change `Organization` or `OrgMembership`; add rename or delete; paginate.

**T12. Invitations: create, list, revoke.** E-items: E17 (`InviteMemberRequest`), E19, E10 (`InvitationRepository`).

- ACs: AC-5.1 to AC-5.10 (AC-5.9's last step and AC-5.10 row 4 need T13; mark them "completed in T13").
- **Allowed files:** the T11 service, controller and repository files; `repository/InvitationRepository.java` (add
  methods only); `dto/request/InviteMemberRequest.java`; create response records in `dto/response/` and exceptions in
  `exception/`; create tests.
- **Must not:** change `Invitation`, `TokenGenerator` or the 7-day expiry; log tokens; return tokens from the list.

**T13. Accept an invitation.** E-items: E20.

- ACs: AC-6.1 to AC-6.9; finish AC-5.9 and AC-5.10 row 4.
- Done when: those tests pass, including a test that two concurrent acceptances of the same token produce exactly one
  membership and no 500.
- **Allowed files:** modify `controller/AuthController.java` (the `acceptInvitation` method, its imports and the removal
  of the `UserRepository` field), `service/InvitationService.java`, `service/impl/InvitationServiceImpl.java`; create
  response records in `dto/response/`; create tests. (2026-10-12) Modify `exception/UnexpectedErrorTest.java` only if
  the accept-flow signature it depends on changes. Assertions may only be strengthened, never weakened (G3).
- **Must not:** change `register` or `login`; touch the project branch of `acceptInvitation`; return a login token from
  the accept call.

**T14. Members: list, change role, remove, leave.** E-items: E21.

- ACs: AC-7.1 to AC-7.7, AC-8.1 to AC-8.6.
- **Allowed files:** the T11 service, controller and repository files; create request and response records in
  `dto/request/` and `dto/response/`, exceptions in `exception/`; create tests.
- **Must not:** let a guard contain business rules; add role caching.

**T15. Contract sync, README, gateway contract notes, end-to-end check.** E-items: X1.

- Change:
    - Update `docs/api/auth-service.openapi.yaml` to the final behaviour; set `info.version` to `1.0.0`.
    - Create `auth-service/README.md`: running with the `dev` profile, `JWT_SECRET`, port 8081, the free seat limit
      setting, the public operations, and the manual steps below.
    - X1 in `docs/api/api-gateway.openapi.yaml`: the two stale notes and the `contractNote`, patch version bump. If FU-7
      has already bumped it to 0.2.1, use the next patch.
    - Manual end-to-end through the gateway (both services on `dev`): register Ana; log in; create "Acme QA"; invite
      `fay@example.com` as `MEMBER`; accept anonymously; log in as Fay; Ana changes Fay to `READER`; Ana removes Fay.
      Record each status code, and one error response body to show the Problem passes through the gateway unchanged.
- Done when: `./gradlew clean build` is green; both contract files parse; the manual results are in your handoff.
- **Allowed files:** modify `docs/api/auth-service.openapi.yaml`; create `auth-service/README.md`; modify
  `docs/api/api-gateway.openapi.yaml` (X1 lines only).
- **Must not:** change code or configuration to make a manual check pass (report instead); put a real secret in the
  README.

**Review point C.** Then qa verifies.

### Fix round after acceptance (T16–T21, 2026-10-12)

These tasks implement the D13 decisions. Guardrails G1–G9 and P1–P12 apply, and so do F1–F4. Additional rules:

- **Test first.** For every task, write the new tests first and run them against the unfixed code. They must fail there.
  Report which tests failed and how, then apply the fix. A test that passes before the fix doesn't prove it; strengthen
  it or explain why in your handoff.
- **Green at the end of every task.** `cd auth-service && ./gradlew build` passes with no failing, disabled or skipped
  test. A red test is never left for a later task.
- **qa's tests are read-only (F1).** If a test under `qa/` asserts the old behaviour of a D13 decision, stop and report
  the test name and its assertion. Don't change it; qa will update it.
- **Only approved E-items.** E23 to E28 are all approved (E23 and, on 2026-10-12, E24 to E28), so T16–T21 can run in
  order.

**T16. Lock failures are 409, not 500.** E-items: E23 (approved).

- ACs: AC-1.9 (new). AC-1.3 must still pass.
- Done when: the 4 AC-1.9 rows pass, and they failed with a 500 before the fix; the build is green.
- **Allowed files:** modify `exception/ApiExceptionHandler.java`; create one test class in
  `auth-service/src/test/java/com/example/authservice/exception/`.
- **Must not:** map any other exception; change lock timeouts or the locking code; log a stack trace for these failures.

**T17. Numeric roles are rejected.** E-items: E24 (approved 2026-10-12).

- ACs: AC-5.11 and AC-7.8 (new). AC-5.7 and AC-7.5 must still pass.
- Done when: the new rows pass, and they failed before the fix (201 or 200); the build is green. Name the exact
  configuration key or annotation you used, and how you verified it against Jackson 3 (P12).
- **Stop condition:** if neither one configuration key nor an annotation on the two record components can do it, stop
  and report. Don't write a custom deserializer, and don't change `OrganizationRole`.
- **Allowed files:** per E24, either `src/main/resources/application.yaml` (one key) or
  `dto/request/InviteMemberRequest.java` and `dto/request/ChangeMemberRoleRequest.java`; create tests in
  `.../controller/`.
- **Must not:** change role names, string matching (`"MEMBER"` stays valid, `"Member"` stays a 400), or any other
  Jackson behaviour.

**T18. Registration race and long login passwords.** E-items: E25, E26 (approved 2026-10-12).

- ACs: AC-2.10 and AC-2.11 (new). AC-2.2, AC-2.4 and AC-2.9 must still pass.
  `QaConcurrencyTest#simultaneousRegistrationsOfOneEmail` and `QaInputEdgeCasesTest` must still pass unmodified.
- Done when: the new tests pass, and they failed before the fix. The AC-2.11 test is repeated (at least 5 repetitions)
  and is green 3 runs in a row. The build is green.
- **Allowed files:** modify `service/impl/AuthServiceImpl.java`; create tests in `.../service/impl/` or
  `.../controller/`.
- **Must not:** add a validation error to `LoginRequest`; change `register`'s response or status codes for non-racing
  calls; parse database error messages if the constraint can be identified otherwise (name what you rely on in your
  handoff).

**T19. Revoking respects the role ceiling.** E-items: E27 (approved 2026-10-12).

- ACs: AC-5.12 (new). AC-5.9 and AC-5.10 must still pass.
- Done when: the new rows pass, and Ben's row failed with 204 before the fix. The revoke error order is as in D13. The
  build is green.
- **Allowed files:** modify `service/impl/OrganizationServiceImpl.java` (the revoke method only); create tests in
  `.../controller/`.
- **Must not:** change who may revoke (still at least `ADMIN`), or the Q4 rule for expired invitations.

**T20. Per-field errors for a missing accept body.** E-items: E28 (approved 2026-10-12).

- ACs: AC-6.10 (new). AC-6.6 must still pass.
- Done when: the 3 rows pass, and the first two failed before the fix (one `acceptInvitationRequest` entry). The build
  is green.
- **Allowed files:** per E28, modify `service/impl/InvitationServiceImpl.java` and/or `controller/AuthController.java` (
  the accept method only); modify `InvitationAcceptanceTest.java`, only where its AC-6.6 "no body" row asserts the old
  `acceptInvitationRequest` entry, to assert the new entries (a stronger assertion); create tests.
- **Must not:** validate the body of a logged-in caller (Q3 rule in D11); change any other error shape.

**T21. Contract and final build.**

- Changes to `docs/api/auth-service.openapi.yaml`:
    1. Lock failures are 409 `CONFLICT` (AC-1.9).
    2. Numeric role values are 400 `MALFORMED_REQUEST`.
    3. Login passwords over 72 bytes are 401 `INVALID_CREDENTIALS`.
    4. Losers of a registration race get `EMAIL_ALREADY_REGISTERED`.
    5. Revoke applies the role ceiling, with the D13 error order.
    6. The no-body accept error has per-field entries.
    7. A note that `instance` holds the acceptance token on accept errors (Q3).
    8. A phase-1 deviation note for FU-1: firewall rejections, `TRACE` and direct `/error` carry Boot's default JSON,
       and `%2F` gets Tomcat's HTML page.
    9. Bump `info.version` to `1.1.0`.

  Leave out the items for skipped tasks.
- Done when: the file parses as OpenAPI 3.1 (name the validator), and `cd auth-service && ./gradlew clean build` is
  fully green. Report the counts of backend-swe and qa tests.
- **Allowed files:** modify `docs/api/auth-service.openapi.yaml`.
- **Must not:** touch code, configuration or tests; document behaviour that isn't in this spec.

**Re-verification scope for qa (after T21).** The 7 new ACs; regression runs of AC-1.3, AC-2.2, AC-2.4, AC-2.9, AC-5.7,
AC-5.9, AC-5.10, AC-6.6 and AC-7.5; a full `clean build`. Update qa tests that assert old behaviour of a D13 decision,
and record why. Close BUG-2, BUG-3 and BUG-4 if their ACs pass. BUG-1 stays open as FU-1.

## Breakdown

| Area                   | Work                                                                                                                                                                                                          | Depends on                                    |
|------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------|
| auth-service           | T1–T15 above                                                                                                                                                                                                  | User approval of the E-list; batches in order |
| api-gateway            | No code or config change: no new public operation, routes `/api/auth/**` and `/api/orgs/**` already cover every new path. Docs only: X1 in T15.                                                               | T15                                           |
| scenario-service       | None                                                                                                                                                                                                          | —                                             |
| report-service         | None                                                                                                                                                                                                          | —                                             |
| docs/api               | `docs/api/auth-service.openapi.yaml` (T6, T15)                                                                                                                                                                | Review point A                                |
| qa                     | Test plan `docs/qa/auth-service-test-plan.md` now (from this spec, once `Ready`); verification after review point C                                                                                           | Spec `Ready`; T15                             |
| Frontend (not in repo) | Later: login and registration, organization list and switcher, create organization, member list with role editing, invite dialog showing the token to copy, accept-invitation page. Switch on Problem `code`. | T6 contract                                   |

## Contracts needed before parallel work

1. **C1. auth-service contract** `docs/api/auth-service.openapi.yaml` (T6, backend-swe). Must exist before frontend-swe
   starts. The paths, status codes and `code` values in this spec's ACs are binding; if backend-swe needs a different
   path or shape, raise it with pm before T6 is final.
2. **C2. JWT contract (unchanged)** from the gateway spec: HS512, `JWT_SECRET` as standard Base64 of at least 64 bytes,
   `sub` (user UUID) and `exp` required, `email`, `orgs` (`[{orgId, role}]`) and `mfa` informational. Phase 1 tightens
   validation at auth-service to the gateway's rule; it doesn't change issuing.
3. **C3. Error contract.** Problem Details as in D2. Compatible with the gateway's `Problem` schema (same base members),
   plus `code` and `errors`; `requestId` present when the gateway forwarded a request ID.
4. **C4. Header contract (unchanged)** from the gateway's C3: `Authorization` forwarded unchanged; `X-User-Id`
   informational only; no CORS headers; no `X-Request-Id` on responses; relative `Location`.

## Cross-service dependencies

| Provider                    | Provides                                                                                                                                         | Consumer                 | Blocks parallel work?                                                             |
|-----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------|-----------------------------------------------------------------------------------|
| auth-service                | Tokens per C2 (unchanged)                                                                                                                        | gateway                  | No                                                                                |
| auth-service                | The public-operation list: unchanged in phase 1 (three operations)                                                                               | gateway                  | No. Any future public operation (phase 3) needs a gateway config and spec change. |
| auth-service                | Problem bodies with `code` (C3), passed through the gateway unchanged                                                                            | frontend                 | No for backend; frontend waits for C1                                             |
| gateway                     | Routes `/api/auth/**` and `/api/orgs/**` to 8081; validates tokens first; drops `X-Request-Id` from service responses; strips client `X-User-Id` | auth-service, qa         | No                                                                                |
| auth-service → gateway docs | X1 note updates                                                                                                                                  | gateway contract readers | No                                                                                |

### Known issues in other services that affect verification

These aren't auth-service bugs; qa: don't file them against auth-service.

1. **Through the gateway, the gateway answers 401 first.** An anonymous or bad-token call to a protected path gets the
   gateway's 401 Problem (with `requestId`, without `code`) and never reaches auth-service. Verify US-3 and the 401 rows
   with direct calls on 8081.
2. **The gateway's own errors have no `code`.** Gateway-generated 401, 403 (CORS), 404, 502, 503, 504 Problems follow
   the gateway's D6 and lack `code` until a platform decision (gateway spec A10). The frontend must handle a Problem
   without `code`.
3. **Unknown paths through the gateway.** `/api/orgs/{id}/no-such-thing` is routed (prefix match), so auth-service
   answers 404 (AC-1.4). A path outside the routes gets the gateway's 404 instead.
4. **The gateway drops `X-Request-Id` from service responses** (gateway D7). Expected; auth-service doesn't set it
   anyway.
5. **The gateway re-encodes query strings** (gateway FU-2). No phase-1 endpoint uses a query string.
6. **Gateway follow-up FU-4** (bodies over 8 KB). Phase-1 responses are small lists; a member list of hundreds of users
   could exceed 8 KB, which is not expected in phase 1.

## Non-functional requirements

- **Security and authorization**
    - Deny by default; exactly three public operations (D7).
    - Authorization from the database on every request (D3); a role change or removal applies to the next request.
    - Login doesn't reveal whether an email exists (AC-2.4). Registration does (409), which is an accepted, common
      trade-off for a sign-up form.
    - Acceptance tokens: at least 192 bits of randomness (existing `TokenGenerator`, 24 bytes), single use, 7-day
      expiry, returned once, never logged, never listed.
    - Never log passwords, tokens, `Authorization` values or acceptance tokens. Problems never contain internals.
    - Seat limits must hold under concurrent invitations and acceptances: two simultaneous requests for the last seat
      can't both succeed. Two acceptances of one token produce one membership.
    - No CORS headers; no H2 console; Swagger only on the service port.
    - **Accepted risks** (carried over): HS512 shared secret; a token stays valid until `exp` (24 h) for routes that
      don't load the user, which in auth-service is none: every authenticated request loads the user and its status.
      Invitation tokens are shown to the inviting admin.
- **Performance (informational, not an AC).** Locally on H2, protected organization endpoints should answer within 100
  ms at p95 for the Acme fixture. backend-swe reports a measurement in T15.
- **Audit.** None in phase 1 (D8). Application logs at INFO record organization creation, invitation creation,
  revocation, acceptance, role changes and removals with actor id, organization id and target id only (no emails, no
  tokens).
- **Observability.** If an `X-Request-Id` request header is present and valid, put it in the logging MDC for the request
  and remove it afterwards (recommended; AC-1.7 covers the body only).
- **Compatibility.** No path changes for existing endpoints. `POST /api/orgs` gains a body (was empty),
  `POST /api/orgs/{orgId}/invitations` gains a body (was empty), the accept endpoint's success body changes. There is no
  consumer yet, so this isn't breaking in practice.
- **Accessibility.** Not applicable (no UI).

## Blocking questions

None.

**Resolved (all answered by the user on 2026-10-12; every recommendation approved):**

1. **Approve the E-list?** Recommended: approve E1–E21 and X1 as listed. Minimum for a useful phase: E1–E10 and E16–E21.
   Batch A needs E1–E10 only.
2. **Remove the six unused dependencies (E11)?** Recommended: yes, in T7 after the guard rewrite.
3. **Upgrade jjwt to 0.12.5 (E12)?** Recommended: yes, as its own task (T9) after T3's tests pin the behaviour. Answer "
   defer" to keep 0.11.5 for now; nothing else depends on it.
4. **Invitation delivery without email:** return the acceptance token once to the inviter in the create response (D11)?
   Recommended: yes; email delivery comes in phase 3.
5. **Free-plan seat limit of 5, counting pending invitations (D11)?** Recommended: yes; it's a configuration value.
6. **Pending (unverified) users can log in during phase 1 (D4)?** Recommended: yes.
7. **H2 console off and removed from the open endpoints (E3, E15)?** Recommended: yes. If the user wants it for local
   debugging, pm will specify a `dev`-profile-only console instead.

## Assumptions

- A1. All data is in-memory and recreated at each start (H2); there's no existing data to migrate.
- A2. Phase 1 creates only `FREE` organizations; plan changes come with billing.
- A3. For request bodies, validation runs before the `@PreAuthorize` check, so a caller who isn't allowed but sends an
  invalid body may get 400 instead of 403. Permission scenarios always use valid bodies. Nothing about the organization
  is revealed by the 400.
- A4. Timestamps remain `LocalDateTime` in the server's time zone (existing entities). UTC `Instant`s come with phase 4.
- A5. Lists are small; no pagination. Organizations are ordered by name, members by email, invitations by creation time.
- A6. The 72-byte password limit counts UTF-8 bytes (BCrypt's limit); AC-2.9 uses ASCII.
- A7. `PATCH` with `{"role": ...}` is the only member update in phase 1.
- A8. Leaving the organization uses the same `DELETE` endpoint with the caller's own id.
- A9. An invitation's stored status may stay `PENDING` after it expires; "expired" is computed from `expiresAt`. No
  background job updates it.
- A10. Spring Boot 4.1's Bean Validation and method-security behaviour is as assumed here; P12 requires backend-swe to
  verify, and to stop and report if it differs.

## Acceptance

### 2026-10-12, after the fix round: Accepted with follow-ups (final)

- **Input:**
    - the section "Re-verification (fix round), 2026-10-12" in `docs/qa/auth-service-report.md`, verdict **Signed off
      with known issues**;
    - tested tree: commit `32eee07` plus the working tree with T1–T21.
- **Decision: Accepted with follow-ups.** The rule is met:
    - every P0 AC passes;
    - every AC has a result;
    - there's no open S1 or S2 bug;
    - only S3 and S4 bugs remain.
- **Results:**
    - All 72 ACs pass: P0 22/22, P1 39/39, P2 11/11.
    - `cd auth-service && ./gradlew clean build`: 719 tests (308 backend-swe, 411 qa), 0 failed, 0 skipped.
    - Concurrency reruns: 3 × 225 tests, no flakes.
    - qa's mutation check killed all 8 fix-round mutants, with each suite on its own.
    - The gateway suite is unchanged: 411 tests, 4 accepted D10 skips.
    - The manual run through the gateway passes.
- **Bugs:**
    - Closed: BUG-2, BUG-3, BUG-4.
    - Open as follow-ups:

      | Bug | Severity | Follow-up |
          |---|---|---|
      | BUG-1 | S3 | FU-1 |
      | BUG-5 | S4 | FU-6 |
      | BUG-6 | S4 | FU-7 |
- **Fix round:** T16–T21 are done. The user accepted the T20 addition under E28.
- **Decisions at final acceptance:** Q8 and Q9 (D13); E29 proposed for FU-6; FU-6 to FU-9 added. No AC was added or
  changed, so all 72 results stand.
- **Follow-up backlog:**

  | ID | Priority | Summary | Owner |
    |---|---|---|---|
  | FU-3 | **P1**, gate: phase 4 | Unicode-blank names and control characters | pm, backend-swe, qa |
  | FU-8 | **P1**, gate: phase 4 | Re-verify the H2-specific behaviour on PostgreSQL | pm, qa |
  | FU-1 | P2 | Problems for errors outside Spring MVC (with gateway FU-3) | pm, backend-swe, qa |
  | FU-6 | P2, gate: before production | No email in logs (E29, needs approval) | pm, backend-swe, qa |
  | FU-7 | P2 | Contract wording for padded role strings (docs) | backend-swe |
  | FU-2 | P2, phase 3 | NFC normalisation of emails | pm |
  | FU-4 | P2, phase 4 | Injectable `Clock` (not approved) | pm, backend-swe |
  | FU-5 | P2 | Tidy-ups (not approved) | backend-swe |
  | FU-9 | P2 | Gateway spec "Known issues" catch-up (docs) | pm |
- **Superseded:** the entry below, kept as history.

### 2026-10-12: Accepted with follow-ups (superseded by the entry above)

- **Input:**
    - `docs/qa/auth-service-report.md`, verdict **Signed off with known issues**;
    - `docs/qa/auth-service-test-plan.md` (TF-1 to TF-7).
    - Tested tree: commit `32eee07` plus the phase-1 working tree.
- **Decision: Accepted with follow-ups.** The rule is met:
    - every P0 AC passes;
    - every AC has a result;
    - there's no open S1 or S2 bug;
    - only S3 and S4 bugs remain.
- **Results:**
    - All 65 ACs pass: P0 22/22, P1 36/36, P2 7/7.
    - `cd auth-service && ./gradlew clean build`: 625 tests (277 backend-swe, 348 qa), 0 failed, 0 skipped.
    - The gateway regression run is green: 411 tests, with only its 4 accepted D10 skips.
    - The manual end to end through the gateway passes, every error passes through as a Problem, and p95 is 10 ms
      against the 100 ms target.
    - qa's mutation check killed 14 of 14 mutants.
- **Bug triage:**

  | Bug or item | Sev | Decision | Where |
    |---|---|---|---|
  | Lock timeouts give 500 | (deviation) | Fix round. Approved by the user on 2026-10-12 (E23). | T16, AC-1.9 |
  | BUG-2 numeric roles | S3 | Fix round (E24 approved 2026-10-12) | T17, AC-5.11, AC-7.8 |
  | BUG-3 registration race code | S4 | Fix round (E26 approved 2026-10-12) | T18, AC-2.11 |
  | BUG-4 72-byte password suffix | S4 | Fix round (E25 approved 2026-10-12) | T18, AC-2.10 |
  | Q1 revoke ceiling | (question) | Fix round (E27 approved 2026-10-12) | T19, AC-5.12 |
  | Q2 no-body accept errors | (question) | Fix round (E28 approved 2026-10-12) | T20, AC-6.10 |
  | BUG-1 errors outside Spring MVC | S3 | Follow-up, P2, together with the gateway's FU-3. Tomcat's `%2F` page is out of scope. | FU-1 |
- **Follow-ups:**
    - the fix round T16–T21, then qa re-verification of its scope;
    - FU-1 to FU-5 (see Scope, "Follow-ups"). FU-3 is P1 and gated on the PostgreSQL move. FU-4 and FU-5 (the `Clock`,
      and the tidy-ups including the `OrganizationProperties` move and the 3 stale test texts) are **not approved** by
      the user and are recorded only.
- **Decisions on qa's questions:** D13.
- **Spec changes at acceptance:**
    - added D13, FU-1 to FU-5, E23 to E28, T16 to T21, and AC-1.9, AC-2.10, AC-2.11, AC-5.11, AC-5.12, AC-6.10, AC-7.8.
    - No existing AC was changed, so all 65 results still apply.
