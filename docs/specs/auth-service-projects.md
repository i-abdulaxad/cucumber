# auth-service phase 2: projects, project roles and the project access check

Status: Accepted (with follow-ups FU-10 to FU-16; final acceptance 2026-10-14, see Acceptance)
Last updated: 2026-10-14 (final acceptance)

## Problem and goal

Phase 1 (`docs/specs/auth-service.md`, accepted 2026-10-12, committed at `8345746`) made accounts, organizations,
organization members and invitations correct. Projects exist only as unused code:

- There is no project API. `Project` has no getters, setters or constructors, so nothing can read or write it.
- `ProjectGuard` casts the principal to `model.User`, but the filter sets `UserPrincipal`, so any use would throw
  `ClassCastException`. It compares roles with `ordinal() >=`, and with `ADMIN` declared first, a `READER` would pass an
  `ADMIN` check.
- `ProjectRoleService` caches project roles for 30 s (Caffeine `project-roles`), so a removal or demotion would keep
  working for up to 30 s. `ProjectGuard`'s own `@Cacheable` method is self-invoked and has no effect.
- Invitations can hold an organization role or a project role in one free-text `role` column, with `org_id` and
  `project_id` both nullable and nothing checking that exactly one is set. The project branch of the accept flow is
  reachable by no API, skips the seat check and returns no organization.
- `projects.secret_token` is plain text, nullable, not unique, and has no stated purpose.
- scenario-service and report-service will store `project_id` (ERD-D1) and need to ask auth-service "may this user act
  on this project, and with which role?". No such contract exists, and it blocks both services (ERD handoff).

The user also wants the existing code cleaned up before new code is added (2026-10-13).
`AuthController.acceptInvitation` validates by hand, uses reflection and branches on business state, and the service
layer contains persistence mechanics, duplicated helpers and unused code (D16).

**Goal of phase 2:**

- Organizations can hold projects, and project access is granted to organization members with a project role.
- Every permission is computed from current data with explicit ranks.
- Other services get a documented, tested access check.
- The dead and ambiguous parts of the data model are cleaned up.
- Controllers, services and repositories follow one layering rule, which new project code uses from the start.

**Success:** through `http://localhost:8080`, an organization admin creates a project, adds colleagues with project
roles, and changes or removes them. A removed or demoted person loses access on the next request. A direct call to
auth-service's access check returns the caller's effective project role. Every error is a Problem with a stable `code`,
every P0 acceptance criterion passes, and every phase-1 test still passes after the clean-up.

## Users and roles affected

| Persona                                                                                | Need                                                                                          |
|----------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| Organization owner or admin (`OWNER`, `ADMIN`)                                         | Create projects; manage every project in the organization without being added to each one     |
| Project admin (project `ADMIN`, for example an organization `MEMBER` who leads a team) | Rename, archive and restore the project; add organization members and set their project roles |
| Project member, tester, reader (`MEMBER`, `TESTER`, `READER`)                          | See the projects they belong to and their role; leave a project                               |
| Organization member without project access                                             | See nothing of projects they don't belong to                                                  |
| scenario-service and report-service developers (not built yet)                         | One documented call that answers "which role does this user have in project P?"               |
| Frontend developer (not in the repo)                                                   | Project screens with one error format and a written contract                                  |
| The user, as code owner                                                                | Thin controllers, no hand-written plumbing, no dead code                                      |

**Roles.** No new roles. Organization roles keep their phase-1 ranks (`OWNER` 4 > `ADMIN` 3 > `MEMBER` 2 > `READER` 1).
Project roles `ADMIN`, `MEMBER`, `TESTER`, `READER` (`auth-service/.../enums/ProjectRole.java`) get an explicit rank for
the first time (D3).

## Glossary

Phase 1's glossary applies (Problem, "a Problem S / C", `{token of X}`, `{X.id}`, `{unknown id}`, Seeded, Direct call,
Role rank, Seat). New or changed terms:

| Term                        | Meaning in this spec                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
|-----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Project                     | A container for Gherkin features, test runs and results, owned by exactly one organization. In auth-service it has a name, its organization, a creation time and an archive time. Its content lives in scenario-service and report-service, which refer to it by id.                                                                                                                                                                                                                                                                                  |
| Project role rank           | `ADMIN` 4, `MEMBER` 3, `TESTER` 2, `READER` 1. "At least `TESTER`" means rank ≥ 2. Never derived from the enum's declaration order (D3).                                                                                                                                                                                                                                                                                                                                                                                                              |
| Explicit project member     | A user with a `ProjectMembership` row in the project. Exactly one project role per project.                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| Implicit project admin      | An organization `OWNER` or `ADMIN`. They have project `ADMIN` on every project of their organization without a `ProjectMembership` row (D3).                                                                                                                                                                                                                                                                                                                                                                                                          |
| Effective project role      | The role auth-service uses for every decision about a user in a project, computed on each request (D3): none if the user isn't a member of the project's organization; `ADMIN` if their organization role is `OWNER` or `ADMIN`; otherwise their explicit project role, or none.                                                                                                                                                                                                                                                                      |
| Project role cap            | The highest project role an organization role allows for an explicit membership: organization `READER` → project `READER`; organization `MEMBER`, `ADMIN`, `OWNER` → project `ADMIN` (D3).                                                                                                                                                                                                                                                                                                                                                            |
| Has access                  | Has an effective project role.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| Archived project            | A project with `archivedAt` set. Visible and readable to everyone with access, but read-only: only "restore" changes it (D5).                                                                                                                                                                                                                                                                                                                                                                                                                         |
| Access check                | The service-facing call `GET /internal/projects/{projectId}/access`, made with the end user's bearer token, that returns the caller's effective project role (D9, contract C5).                                                                                                                                                                                                                                                                                                                                                                       |
| Projects fixture            | The phase-1 Acme fixture, plus: Acme's seat limit is seeded to 10; users `gus@example.com` (Gus Hale), `hal@example.com` (Hal Ito) and `ivy@example.com` (Ivy Moss), password `Secret123!`, all Acme `MEMBER`; project "Checkout" (`{checkout}`) in Acme with explicit members Cai `ADMIN`, Gus `MEMBER`, Hal `TESTER`, Dan `READER`; project "Billing" (`{billing}`) in Acme with no explicit members; organization "Initech QA" (`{initech}`) with Eve `OWNER` and project "Portal" (`{portal}`) with no explicit members. All projects are active. |
| `{acme-projects}`           | `/api/orgs/{acme}/projects`. So `{acme-projects}/{checkout}/members` is `/api/orgs/{acme}/projects/{checkout}/members`.                                                                                                                                                                                                                                                                                                                                                                                                                               |
| Refactor item, removal item | An E-item that changes the structure of existing code, or deletes unused code, **without changing behaviour or the contract** (D16). Existing tests must pass unmodified, except test lines the item names explicitly.                                                                                                                                                                                                                                                                                                                                |

Effective project roles in the Projects fixture (the reference for every matrix below):

| User | Acme role              | Checkout           | Billing            | Portal             |
|------|------------------------|--------------------|--------------------|--------------------|
| Ana  | `OWNER`                | `ADMIN` (implicit) | `ADMIN` (implicit) | none               |
| Ben  | `ADMIN`                | `ADMIN` (implicit) | `ADMIN` (implicit) | none               |
| Cai  | `MEMBER`               | `ADMIN`            | none               | none               |
| Gus  | `MEMBER`               | `MEMBER`           | none               | none               |
| Hal  | `MEMBER`               | `TESTER`           | none               | none               |
| Dan  | `READER`               | `READER`           | none               | none               |
| Ivy  | `MEMBER`               | none               | none               | none               |
| Eve  | none (Initech `OWNER`) | none               | none               | `ADMIN` (implicit) |

## Scope

### In scope

- **Clean-up first (D16):**
    - the layering guardrail PL, binding for every task;
    - refactor items that bring the existing controllers and services in line with it, including a clean
      accept-invitation design;
    - removal of unused code.

  Behaviour and contract are unchanged.
- Explicit ranks for project roles, and the effective-role rule that ties project access to organization roles (D3).
- A rewritten `ProjectGuard` and `ProjectRoleService`: database lookup on every request, explicit ranks, no cache (D10).
  The now-unused cache configuration and dependencies are removed.
- Projects: create, list the ones I can see, view one, rename, archive, restore (US-1, US-2, US-3).
- Project members: list, add an organization member with a project role, change role, remove, leave (US-4, US-5).
- Organization changes carry through to projects: leaving or being removed from the organization removes project
  memberships; becoming organization `READER` lowers project roles to `READER` (US-6).
- The access check for other services, and how they authenticate to it (US-7, D9, contract C5).
- Invitation cleanup: invitations are for organizations only, with a typed role (D7).
- `projects.secret_token`: purpose decided, unused column removed (D8).
- **Logging (E55, Batch A3, D17):** a logging aspect on the service layer, request correlation in the MDC (request ID,
  user ID), and tests that prove no personal data or secrets are logged (US-9). Added on 2026-10-13 in place of E43.
- Contract `docs/api/auth-service.openapi.yaml` 1.2.0, including docs-only follow-up FU-7 (D13).
- pm docs follow-up FU-9 in the gateway spec, plus a note on `/api/projects/**` and `/internal/**` (X2, D13).
- ERD: `docs/erd/cucumber.dbml` marks the planned entity changes `proposed (auth-service-projects)`.

### Out of scope (later specs)

- **Project invitations for people outside the organization**, and a combined "invite to organization and project" flow.
  In phase 2 you invite to the organization first (phase 1), then add the member to projects (D6).
- **Hard deletion of projects.** Other services hold data by project id, and there are no events to tell them; archive
  is the only removal (D5).
- **CI upload tokens** (the purpose of the old `secret_token`): issuing, rotation, revocation and verification come with
  the result-ingestion spec for report-service (D8).
- **Service-to-service credentials** (a service calling auth-service with no end user, for example a CI upload). Decided
  with the ingestion spec (D9).
- **Audit log** writing and reading: its own spec `auth-service-audit` (D11).
- A cross-organization "all my projects" list (`GET /api/projects`), project descriptions, keys or settings, moving a
  project to another organization, per-plan project limits, custom roles.
- Listing implicit project admins in the project member list (A4).
- A `projects` claim in the JWT. Clients and services read project roles from the API (D9).
- Routing `/api/projects/**` at the gateway (D2 recommends not to; option A is listed for the user's choice).
- Pagination (lists stay small).
- Refactors that change behaviour, and the clean-up candidates listed under D16 "Considered and not proposed".
- Phase-4 items: PostgreSQL, Flyway, `Instant`, NOT NULL and unique constraints in the database (including a unique
  index for project names), FU-3, FU-8.
- Phase-1 follow-ups other than FU-7, FU-9 and the parts of FU-5 re-proposed here (E49, E50): FU-1, FU-2, FU-3, FU-4,
  the rest of FU-5, FU-8. FU-6 (E29) was folded into the logging work (L2) and closed at review point A3 by AC-9.10.
- Logging in auth-service beyond E55's scope: log shipping, JSON log format, distributed tracing, metrics, and an access
  log in auth-service (the gateway already writes one). Refactoring candidates RC-1 to RC-10 aren't scheduled.

## Code facts verified (HEAD `8345746`, read on 2026-10-13)

### Projects, invitations, gateway

| Item                                                          | Result                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
|---------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `model/Project`                                               | Fields `id`, `organization` (`@ManyToOne`, `org_id` not null), `name`, `secretToken`, `createdAt`. No Lombok, no accessors, no constructors besides the implicit one.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `secretToken`                                                 | Never read or written in `src/main` or `src/test` (grep).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `model/ProjectMembership`                                     | Lombok `@Getter @Setter @Builder` etc.; unique `(user_id, project_id)`; `role` is `@Enumerated(STRING)`. Unused except by the unreachable accept branch.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `enums/ProjectRole`                                           | `ADMIN, MEMBER, TESTER, READER`, no rank.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `security/ProjectGuard`                                       | Casts `authentication.getPrincipal()` to `model.User` (the filter sets `UserPrincipal`, so `ClassCastException`); `ProjectRole.valueOf(role.toUpperCase())` with the default locale; `userRole.ordinal() >= requiredRole.ordinal()` (inverted: `READER` passes `ADMIN`); a self-invoked `@Cacheable` method (line 33). **No `@PreAuthorize` references `projectGuard`**, so it's unreachable today.                                                                                                                                                                                                                                                                        |
| `service/impl/ProjectRoleService`                             | `@Cacheable("project-roles")` (line 18), key `userId-projectId`; returns the explicit role or `null`. Used only by `ProjectGuard`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `@Cacheable` users                                            | Only `ProjectRoleService:18` and `ProjectGuard:33` (grep).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `configuration/CacheConfig`                                   | `@EnableCaching`, Caffeine manager with only the `project-roles` cache, 30 s after write, max 1000.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `build.gradle`                                                | Lines 22–23: `spring-boot-starter-cache`, `com.github.ben-manes.caffeine:caffeine`. Used only by `CacheConfig` and the two `@Cacheable` methods.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `repository/ProjectRepository`, `ProjectMembershipRepository` | Plain `JpaRepository`; the second has `findByUserIdAndProjectId`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `model/Invitation`                                            | `role` is `String` ("Can be either OrgRole or ProjectRole"); `organization` and `project` both nullable `@ManyToOne`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `InvitationServiceImpl.accept`                                | When `invitation.getOrganization()` is null and `getProject()` isn't, it saves a `ProjectMembership` with `ProjectRole.valueOf(role)`, skips the seat check, has a `TODO: Check org role ceiling`, and returns `organization: null`, contrary to phase-1 Q2 (lines 115–123). No API creates such invitations.                                                                                                                                                                                                                                                                                                                                                              |
| `OrganizationServiceImpl`                                     | Writes `role(request.role().name())` (line 132); reads `OrganizationRole.valueOf(invitation.getRole())` in the list (151) and revoke (170) paths. `removeMember` and `changeMemberRole` lock the organization row (`PESSIMISTIC_WRITE`).                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| Tests depending on `Invitation.role` being a `String`         | backend-swe: `controller/InvitationsTest.java:458`, `controller/InvitationAcceptanceTest.java:553` (`.role(role.name())`). **qa:** `qa/QaSupport.java:87` (`.role(role.name())`, compile-time) and `qa/QaAuthorizationMatrixTest.java:106` (`isEqualTo(role.name())`, run-time). See E37.                                                                                                                                                                                                                                                                                                                                                                                  |
| `support/AcmeFixture.reset()`                                 | Already deletes project memberships and projects, children first. No change needed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `model/AuditLog`                                              | No accessors, `metadata` is a `String` in a `json` column; `AuditLogRepository` is used only by `AcmeFixture.reset()`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `SecurityConfig`                                              | Three public operations, `/error`, Swagger; `anyRequest().authenticated()`. A new `/internal/**` path is authenticated without any change.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `ErrorCode`                                                   | 25 codes; its Javadoc says never rename or remove one. Adding is allowed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| Gateway                                                       | Routes `/api/auth/**` and `/api/orgs`, `/api/orgs/**` to auth-service. `/api/projects/**` and `/internal/**` are not routed. **Seven gateway test files use `GET /api/projects/1` as the canonical unrouted path** (404 with a valid token, 401 without): `RoutingTest`, `exception/GatewayErrorResponseTest` and `security/JwtAuthenticationTest` (backend-swe), and qa's `QaUnknownPathsTest`, `QaAccessLogTest`, `QaCorsAdversarialTest` and `QaLeakageTest`. Gateway spec D2 says "each prefix maps to exactly one service, and prefixes must not overlap". It also says nested cross-service paths such as `/api/projects/{id}/features` "need an explicit decision". |

### Layering and unused-code audit (D16)

pm read every controller, service, service implementation, repository, configuration class and the advice at HEAD
`8345746`. Evidence is grep over `auth-service/src/main` and `auth-service/src/test`.

| #   | File and lines                                                                                                                                                                                | Finding                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | Kind                                                                      | Proposed item                                                               |
|-----|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| A1  | `controller/AuthController.java:62–88`                                                                                                                                                        | `acceptInvitation`: `@Valid` plus a `BindingResult` parameter; on a missing body it builds an empty request (`AcceptInvitationRequest.builder()`), wraps it in a `BeanPropertyBindingResult` and validates it with `new SpringValidatorAdapter(validator)`; it finds its own method with `ReflectionUtils.findMethod`, builds a `MethodParameter` and throws `MethodArgumentNotValidException`; it branches on `principal != null` and calls one of two service methods, choosing 200 or 201 by branch. | Layering: hand validation, reflection, business branching in a controller | E42                                                                         |
| A2  | `controller/AuthController.java:43`                                                                                                                                                           | `private final Validator validator` exists only for A1 (phase-1 E28 addition).                                                                                                                                                                                                                                                                                                                                                                                                                          | Workaround                                                                | E42                                                                         |
| A3  | `controller/AuthController.java:47–50`, `51`, `58`                                                                                                                                            | `@ApiResponses({@ApiResponse(useReturnTypeSchema = true, description = "Api v1"), @ApiResponse})`: an empty response entry and a misleading description, runtime docs only. `ResponseEntity<@NonNull ...>` uses Lombok's `@NonNull` as a type-use annotation, where Lombok does nothing.                                                                                                                                                                                                                | Leftovers with no effect                                                  | E46                                                                         |
| A4  | `controller/OrganizationController.java`                                                                                                                                                      | One service call per handler, no repositories, no hand validation.                                                                                                                                                                                                                                                                                                                                                                                                                                      | Compliant                                                                 | —                                                                           |
| A5  | `service/impl/JwtTokenProvider.java:86–92`, `94–105`, `37`                                                                                                                                    | `getEmailFromJWT` and `validateToken` have no caller in `src/main`; `JwtAuthenticationFilter` calls `getUserIdFromJWT` directly. The `log` field is used only in `validateToken`. Only `JwtTokenProviderTest:75`, `:76` and `:104` call them.                                                                                                                                                                                                                                                           | Unused methods                                                            | E44                                                                         |
| A6  | `aop/Logging.java`                                                                                                                                                                            | An empty `@Aspect` class without `@Component`, and no `@EnableAspectJAutoProxy` anywhere, so Spring never registers it. "Logging" matches nothing else in `src` except one test method name. It compiles only because `org.aspectj` arrives transitively.                                                                                                                                                                                                                                               | Unused class                                                              | ~~E43~~ withdrawn 2026-10-13; the class is implemented by E55 instead (D17) |
| A7  | `configuration/OpenApiConfiguration.java:23–24`                                                                                                                                               | `.type(SecurityScheme.Type.HTTP)` is immediately overwritten by `.type(SecurityScheme.Type.OAUTH2)`, so the runtime `/v3/api-docs` declares an OAuth2 scheme without flows (invalid OpenAPI). The checked-in contract is HTTP bearer.                                                                                                                                                                                                                                                                   | Leftover override                                                         | E45                                                                         |
| A8  | `dto/request/RegisterRequest`, `InviteMemberRequest`, `LoginRequest`, `CreateOrganizationRequest`, `AcceptInvitationRequest`; `dto/response/OrgClaim`, `UserResponse`, `OrganizationResponse` | Lombok `@Builder` on records. `<Record>.builder` is called only at `AuthController:77` (A1). Records have canonical constructors, and Jackson uses those.                                                                                                                                                                                                                                                                                                                                               | Unused generated code                                                     | E47                                                                         |
| A9  | `service/impl/AuthServiceImpl.java:87`                                                                                                                                                        | `login` puts the `Authentication` into `SecurityContextHolder`. The chain is `STATELESS`, login is a public operation, and nothing reads the context later in the request. Setting the security context is the security layer's job, not a service's. (Phase-1 T10 kept it under G4.)                                                                                                                                                                                                                   | Unneeded side effect, layering                                            | E48                                                                         |
| A10 | `AuthServiceImpl.java:111`, `InvitationServiceImpl.java:168`, `OrganizationServiceImpl.java:267`                                                                                              | Three identical private `normalizeEmail` methods (`trim().toLowerCase(Locale.ROOT)`).                                                                                                                                                                                                                                                                                                                                                                                                                   | Duplication (FU-5)                                                        | E49                                                                         |
| A11 | `service/impl/OrganizationServiceImpl.java:37`, `49`                                                                                                                                          | `@EnableConfigurationProperties(OrganizationProperties.class)` on a service; `PropertiesConfiguration` registers `JwtProperties` the same way.                                                                                                                                                                                                                                                                                                                                                          | Configuration in a service                                                | E50                                                                         |
| A12 | `OrganizationServiceImpl.java:32`, `62`, `238`; `InvitationServiceImpl.java:28`, `52`, `153`                                                                                                  | Both services inject `EntityManager` and call `entityManager.find(Organization.class, id, PESSIMISTIC_WRITE)`. That's data access in services, and the project service needs the same lock (P13).                                                                                                                                                                                                                                                                                                       | Layering: persistence mechanics in services                               | E51                                                                         |
| A13 | `AuthServiceImpl.java:69–70`, `InvitationServiceImpl.java:113–114`, `130–131`, `OrganizationServiceImpl.java:278–279`                                                                         | `new UserResponse(...)` is built twice and `new OrganizationResponse(...)` twice, field by field.                                                                                                                                                                                                                                                                                                                                                                                                       | Duplicated mapping                                                        | E52 (optional)                                                              |
| A14 | `configuration/CacheConfig.java`; `build.gradle:22–23`                                                                                                                                        | After E32 and E33 remove the only two `@Cacheable` methods, the cache manager and both dependencies are unused.                                                                                                                                                                                                                                                                                                                                                                                         | Unused once phase 2 lands                                                 | E53, E54                                                                    |
| A15 | `InvitationServiceImpl.java:20`, `24`, `49`, `115–123` (project branch, `ProjectMembershipRepository`, `ProjectRole`, `ProjectMembership`)                                                    | Dead branch (see above).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | Unused code                                                               | E36                                                                         |

**Considered and not proposed** (kept, with the reason):

- `InvitationRepository.findByToken`: unused in `src/main`, but used by backend-swe tests (
  `InvitationAcceptanceTest:228`, `:543`) and by qa tests (`QaInvitationSecurityTest`, `QaInputEdgeCasesTest`,
  `QaFixRoundTest`). Removing it would break qa's read-only tests.
- `UserPrincipal.getStatus()`: called only by `UserPrincipalTest:29`, but it's the accessor of the field `isEnabled()`
  uses (phase-1 E6). Trivial.
- `AuditLogRepository`: used only by `AcmeFixture.reset()`, and needed by the audit spec (D11).
- `OrgMembershipRepository.findByUser`, `CustomUserDetailsService.loadUserByUsername`: used (token claims;
  `DaoAuthenticationProvider` at login).
- `OpenEndpoints`: both remaining entries (`SWAGGER_UI`, `SWAGGER_DOCS`) are used by `SecurityConfig`.
- The duplicated ceiling text (`OrganizationServiceImpl:54` and `ApiExceptionHandler:55`): the user's
  `OrgRoleCeilingException(String)` requires a message, and phase 1 kept that class unchanged.
- `JwtAuthenticationFilter` is a `@Component` and is also added to the security chain, so Boot registers it twice.
  `OncePerRequestFilter` makes the second registration a no-op. It's a known Spring pitfall, but changing the
  registration changes when authentication happens relative to the security chain. That's behaviour-sensitive and needs
  its own investigation, so it's recorded as Known issue 9 for a later round.
- Lombok `@Builder` on entities: used by tests and fixtures.

## Decisions (recommended defaults)

Each decision has a recommended default and a reason. The items that change existing code are listed under "Changes to
existing code", for the user's approval.

### D1. Phase scope and slug

**Default:** phase 2 = the In scope list, slug `auth-service-projects`. 7 stories, 51 ACs, a clean-up batch, then three
feature batches.
**Why:** it covers what scenario-service and report-service need before they can start (projects and the access check),
plus the defects routed here by the ERD review and the clean-up the user asked for, and nothing more.

### D2. Where the project API lives, and the gateway

**Default (option B):**

- Every user-facing project operation lives under the organization: `/api/orgs/{orgId}/projects[/{projectId}[/...]]`.
  The gateway's existing `orgs` route already covers these paths, so **no gateway code or configuration changes**.
- The service-facing access check is `GET /internal/projects/{projectId}/access` (D9). Services call auth-service
  directly; the gateway never routes `/internal/**`, so it isn't part of the public API.
- `/api/projects/**` stays unrouted and is **reserved for project-scoped content services** (for example
  `/api/projects/{projectId}/features/**` for scenario-service, `/api/projects/{projectId}/runs/**` for report-service).
  Each of those specs adds its own non-overlapping route.

**Why:** it avoids an overlap with future content routes (the gateway D2 rule), needs no gateway change, and leaves all
seven gateway test files valid (four of them are qa's, which backend-swe can't change). The cost: a project URL carries
its organization id, and auth-service must check that the project belongs to that organization (AC-2.3).

**Alternative (option A, not recommended):** project paths under `/api/projects/{projectId}/...` and a gateway route for
them (EG1).

- It changes `api-gateway/src/main/resources/application.yaml`.
- It breaks the seven gateway test files that use `/api/projects/1` as the unrouted example, and qa has to change four
  of them.
- Future content routes would overlap with it unless the gateway spec's D2 rule changes.

If the user picks A, pm rewrites the paths in the ACs; the behaviour stays the same.

### D3. Project roles, ranks and the link to organization roles

**Default:**

- **Ranks:** `ADMIN` 4 > `MEMBER` 3 > `TESTER` 2 > `READER` 1. `ProjectRole` gets an explicit `rank` and
  `isAtLeast(ProjectRole)`, as `OrganizationRole` has (E30). Declaration order is unchanged and never used.
- **Meaning** (for consumers; their specs confirm the details). Each role includes the ones below it.
    - `READER` reads features, runs and reports.
    - `TESTER` also records manual results.
    - `MEMBER` also edits features and manages runs.
    - `ADMIN` also manages the project (name, archive) and its members.
- **Implicit admins:** organization `OWNER` and `ADMIN` have effective project `ADMIN` on every project of their
  organization, with no row.
- **Cap:** an explicit membership's role is at most the project role cap of the user's organization role. An
  organization `READER` can only be a project `READER`; an organization `MEMBER` (or higher) can hold any project role.
- **Effective role** (computed on every request):
    - not an organization member → none;
    - organization `OWNER` or `ADMIN` → `ADMIN`;
    - otherwise the explicit role (always within the cap, see D6), or none.
- An explicit membership for an implicit admin is allowed and kept. It matters only if they lose organization `ADMIN`
  later (AC-4.9, AC-6.4).

**Why:** organization admins must be able to manage every project without being added to each one, and an organization
`READER` is read-only by definition. The linear order matches the user's enum order and keeps checks simple ("at least
`TESTER`"). Computing the role on each request means no stale permissions, as in phase 1's D3.

### D4. Who can do what

| Operation                                    | Allowed                         | Notes                                                                                          |
|----------------------------------------------|---------------------------------|------------------------------------------------------------------------------------------------|
| Create a project in an organization          | Organization `OWNER` or `ADMIN` | A project costs nothing today, but plans may limit projects later; organization admins decide. |
| List the organization's projects             | Any organization member         | Returns only the projects where the caller has access, with `myRole`.                          |
| View a project, list its members             | Has access (any effective role) |                                                                                                |
| Rename, archive, restore                     | Effective project `ADMIN`       |                                                                                                |
| Add a member, change a role, remove a member | Effective project `ADMIN`       |                                                                                                |
| Leave (remove yourself)                      | Any explicit member             | Implicit admins have no row to remove (AC-5.9).                                                |
| Access check                                 | Has access                      | Any other caller gets 403.                                                                     |

**Why:** it mirrors phase 1 (admins manage, members see), and the organization owner keeps final control through
implicit admin. There's no "last project admin" rule, because organization admins always remain implicit admins.

### D5. Project lifecycle

**Default:**

- **Name:** 1–100 characters, not blank, stored trimmed (leading and trailing whitespace removed).
    - Unique per organization, **including archived projects**, so restoring never conflicts.
    - Names are compared after trimming and lower-casing with `Locale.ROOT`.
    - A name can repeat across organizations.
- **Name length (Q3, pm decision 2026-10-13):** the 1–100 limit is counted on the name **as sent, before trimming**.
  Why: it's the 400 request-validation step that runs before authorization (A3), it matches the contract's
  `maxLength: 100` on the raw string, and the stored trimmed name can then never exceed 100. A 100-character name with
  surrounding spaces gets 400; that edge is accepted.
- **Rename:** the same rules. Renaming to the project's own name in another letter case is allowed.
    - **Error order (Q2, pm decision 2026-10-13, confirmed):** request validation 400; then 403 `FORBIDDEN`; then 409
      `PROJECT_ARCHIVED`; then 409 `PROJECT_NAME_TAKEN`. Why: an archived project is read-only, so its state is checked
      before comparing the name with other projects, the same as D6 puts `PROJECT_ARCHIVED` first; contract 1.2.0
      already says this.
- **Archive:** sets `archivedAt`.
    - An archived project stays visible to everyone with access, and is listed with its `archivedAt`.
    - It's read-only: rename and every membership change get 409 `PROJECT_ARCHIVED`. That includes leaving: restore
      first.
    - The access check reports `archived: true`, and consumers must refuse content writes (C5).
- **Restore:** clears `archivedAt`. Archive and restore are idempotent (200 with the unchanged state).
- **No hard delete** in phase 2.

**Why:** names identify projects in a team's UI, so duplicates inside one organization are confusing. Hard delete would
leave orphaned features and results in other services, with no event mechanism to clean them up. Archive is reversible.

### D6. Project memberships

**Default:**

- Only **current members of the project's organization** can be added, by user id, with a project role. There are no
  project invitations (out of scope).
- Adding, changing and removing take effect on the next request (no cache).
- **Seats** stay an organization concept: a project membership uses no seat, because every project member is already an
  organization member.
- **Consistency with organization changes** (E38, E39), in the same transaction as the organization change:
    - when a user leaves or is removed from an organization, all their memberships in that organization's projects are
      deleted, so rejoining later starts without project access;
    - when a user's organization role becomes `READER`, their explicit project roles above `READER` in that organization
      are lowered to `READER`. A later promotion doesn't restore them (A6).
- Self role change: 422 `SELF_ROLE_CHANGE`, as in phase 1.
- **Error order** (the first failure wins, as in phase 1's Q6): request validation 400 (runs before authorization,
  phase-1 A3); then 403 `FORBIDDEN`; then 409 `PROJECT_ARCHIVED`; then:
    - **add:** 422 `NOT_ORG_MEMBER`, then 409 `ALREADY_MEMBER`, then 422 `PROJECT_ROLE_NOT_ALLOWED`;
    - **change role:** 404 `MEMBER_NOT_FOUND`, then 422 `SELF_ROLE_CHANGE`, then 422 `PROJECT_ROLE_NOT_ALLOWED`;
    - **remove or leave:** 404 `MEMBER_NOT_FOUND`.

**Why:** adding members directly is the smallest correct model. It keeps the seat rule in one place and removes the need
for project invitations, which is what made `invitations.role` ambiguous (D7). Doing the cleanup at write time keeps
stored roles equal to effective roles, so lists never show a role the person can't use.

### D7. Invitations are for organizations only

**Default:**

- Remove the project link from invitations: the `project` field and the project branch of the accept flow (E36).
  `org_id` becomes required in the entity.
- Make `Invitation.role` an `OrganizationRole` stored as a string (E37). Stored values don't change (`OWNER`,
  `ADMIN`, ...), and the H2 schema is recreated at every start.
- **Coordination needed for E37:** four test lines depend on `role` being a `String` (see Code facts).
    - Two are backend-swe's: a G3 exception in T10.
    - Two are qa's. Either qa changes them in lockstep with T10, or the user grants backend-swe a one-time F1 exception
      for exactly those two lines. Recommended: the F1 exception, with qa reviewing the diff in its re-verification.
- If the user rejects E37, the role stays a `String` that only the organization flow writes; the database can't validate
  it until phase 4 adds a check constraint.

**Why:** with D6 there are no project invitations, so the "exactly one of `org_id` and `project_id`" problem disappears.
A typed role lets the database and the compiler reject anything that isn't an organization role.

### D8. `projects.secret_token`

**Default:**

- **Purpose:** it was meant as a project **CI upload token**, a credential that lets a CI job upload automated Cucumber
  results to report-service without a user (ERD-D2; `test_runs.created_by` is null "when uploaded with a project
  token").
- **Phase 2:** remove the column from the entity (E31). Nothing reads or writes it, and the H2 database holds no data.
- **Rules for the later feature**, recorded here so the ingestion spec starts from them:
    - at least 256 bits of randomness;
    - stored only as a SHA-256 hash, which is enough for high-entropy tokens, and unique;
    - shown once, at creation;
    - several active tokens per project, each with a name, creation time, last-used time and optional expiry, so
      rotation can overlap;
    - revocable by a project `ADMIN`;
    - verified through an auth-service contract that report-service calls with a service credential (D9);
    - likely its own table (`project_upload_tokens`), not a column on `projects`.

**Why:** a credential that nothing can verify or use adds attack surface and nothing else. Its verification needs
service-to-service authentication, which belongs with the ingestion design. Removing the column now means the plain-text
design can't be used by accident.

### D9. The access check for other services

**Default:**

- **In this phase**, as a tested endpoint: `GET /internal/projects/{projectId}/access` (US-7, contract C5).
- **Authentication:** the consuming service forwards the end user's `Authorization: Bearer <jwt>` header unchanged. It
  calls auth-service directly at its base URL (`AUTH_SERVICE_URL`, port 8081 locally), not through the gateway. No
  service credential is needed, because every phase-2 use is on behalf of a logged-in user.
- **Answer:**
    - 200 with `projectId`, `orgId`, `role` (the effective role) and `archived` when the caller has access;
    - 403 `FORBIDDEN` when they don't, or when the project doesn't exist (no probing);
    - 401 for a missing or invalid token;
    - 400 for a malformed id.
- **Consumer rules** (each consumer's spec repeats them):
    - compare `role` with `ProjectRole` ranks, never with string order;
    - refuse content writes when `archived` is true;
    - treat a timeout or a 5xx as "deny" (fail closed);
    - don't cache the answer, unless that consumer's spec accepts a staleness window of at most 30 s, documented as a
      known delay for removals.
- **Later (not in phase 2):** a service credential for calls without a user (CI uploads, background jobs), and checks
  about another user (for example "can user X be assigned this manual test?"). Both are decided with the ingestion spec.

**Why:** forwarding the user's token reuses the existing, tested JWT validation and needs no new secret. A dedicated,
minimal endpoint is a stable contract that other teams can rely on, independent of the user-facing project view, which
may grow. The `/internal/` prefix makes it obvious the path isn't public.

### D10. Authorization pattern

**Default:**

- Rewrite `ProjectGuard` to mirror `OrgGuard` (E33):
    - the caller comes from `UserPrincipal`;
    - the effective role comes from the database on every call, through the rewritten `ProjectRoleService` (E32);
    - roles are compared with `ProjectRole.isAtLeast`;
    - an unknown role name or a missing principal fails closed (returns false), never with an exception.
- Bean name `projectGuard` and the method `hasRole(UUID projectId, Authentication, String)` stay. backend-swe adds what
  the endpoints need, for example a variant that also checks that the project belongs to the path's organization, and
  an "is self and an explicit member" check for leaving.
- Remove `@Cacheable` from `ProjectRoleService` and the self-invoked cached method from `ProjectGuard`. **No caching of
  project roles.** Then delete the unused `CacheConfig` and the two cache dependencies (E53, E54).
- Business rules (cap, already member, self, archived) live in the service layer with typed exceptions, never in
  guards (phase-1 P5, PL).
- Non-member, no access, unknown project and project in another organization all get the same 403 `FORBIDDEN`.

**Why:** these are the same rules as `OrgGuard`, which qa verified in phase 1. A 30 s cache would break "removal takes
effect on the next request" (AC-5.8, AC-7.5). Every request already loads the user, so one or two more indexed lookups
cost little. Unused caching infrastructure is dead code (D16).

### D11. Audit log

**Default:** don't write audit entries in phase 2. Give the audit log its own small spec, `auth-service-audit`, after
phase 2.
**Why:**

- Doing it properly means fixing `audit_log.metadata` first. It's a `String` in a `json` column, which is double-encoded
  on H2 and fails on PostgreSQL. That fix is entity work scheduled for phase 4, and it's sensitive to the Hibernate and
  Jackson versions.
- A useful audit log covers organization events too, so it touches every accepted phase-1 write path.
- Without a read API nothing is observable, so it needs its own stories.

Phase 2 keeps phase 1's INFO logs with ids only. `AuditLog` is not touched.
**Alternative:** add it to phase 2 as US-8 ("organization admins can read the last 100 audit entries"), with a metadata
mapping fix and its own stop condition. That adds about 6 ACs, 2 E-items and another batch.

### D12. Dependencies and migrations

**Default:**

- **No new dependency and no version change.** The only approved build change is E54, which removes the two cache
  dependencies once E53 has deleted their last user. E57 (declare the AspectJ starter for E55) is proposed and needs the
  user's approval (open question L1).
- **No migrations.** Persistence stays on H2 with `ddl-auto: create` until phase 4, and the entity changes (E31, E36,
  E37) take effect at the next start.
- No new test libraries.

**Flags for phase 4** (added to its scope):

- a unique index on `(org_id, lower(name))` for projects;
- NOT NULL on `projects.name`;
- a check or enum type for `invitations.role`;
- `archived_at` as `timestamptz`;
- FU-8 re-verification extended to the project name race (AC-1.7) and the organization lock used by project membership
  writes (and by E51's repository lock).

### D13. Docs-only follow-ups

**Default:**

- **FU-7** (contract wording for padded role strings) goes into T8, together with the same wording for `ProjectRole`.
- **FU-9** (gateway spec "Known issues" catch-up) is done by pm as X2. X2 also adds a gateway spec note that
  `/internal/**` is never routed and `/api/projects/**` is reserved (D2). It needs the main session to let pm write
  `docs/specs/api-gateway.md`.
- **E29 / FU-6:** recommended for fold-in with the logging work, as one extra key in E56 (D17). It still needs the
  user's own approval (open question L2). Until then it isn't part of phase 2.

**Why:** they're docs only and touch files that phase 2 updates anyway.

### D14. Error codes

New `ErrorCode` constants (E35). Every phase-1 code keeps its meaning.

| Situation                                                                                                | Status | `code`                                                                             |
|----------------------------------------------------------------------------------------------------------|--------|------------------------------------------------------------------------------------|
| Another project in the organization already has that name (trimmed, case-insensitive, archived included) | 409    | `PROJECT_NAME_TAKEN` (new)                                                         |
| Rename or membership change on an archived project                                                       | 409    | `PROJECT_ARCHIVED` (new)                                                           |
| Adding a user who isn't a member of the project's organization (or doesn't exist)                        | 422    | `NOT_ORG_MEMBER` (new)                                                             |
| A project role above the cap of the target's organization role                                           | 422    | `PROJECT_ROLE_NOT_ALLOWED` (new)                                                   |
| No access to the project, unknown project, project of another organization                               | 403    | `FORBIDDEN` (existing)                                                             |
| Target isn't an explicit member of the project                                                           | 404    | `MEMBER_NOT_FOUND` (existing)                                                      |
| Target is already an explicit member                                                                     | 409    | `ALREADY_MEMBER` (existing)                                                        |
| Changing your own project role                                                                           | 422    | `SELF_ROLE_CHANGE` (existing)                                                      |
| An unknown project role (including the organization-only `OWNER`) or a number                            | 400    | `MALFORMED_REQUEST` (existing; phase-1 E24 already rejects numbers for every enum) |

**Why `NOT_ORG_MEMBER` is 422 and not 404:** the user id is part of the request body, not the path. A 422 tells the UI "
pick someone from the organization". Unknown ids get the same answer, so nothing can be probed.

### D15. Testing approach

**Default:** phase-1 D10 applies unchanged:

- full-context MockMvc tests against the real security chain and H2;
- `@Tag("AC-x.y")` on every AC test;
- API tests are not `@Transactional`;
- the test secret lives in test code, and `src/test/resources` isn't used.

Additions:

- A new test helper, `support/ProjectFixture`, seeds the Projects fixture on top of `AcmeFixture.seed()`. `AcmeFixture`
  isn't modified; its `reset()` already clears projects.
- Unit tests for `ProjectRole` ranks (every pair), and for the effective-role rule (every organization role × every
  explicit role, or none).
- **Test first** for every change to existing behaviour (T5, T9, T15, P15): write the test, show that it fails against
  HEAD, then fix.
- **Refactor and removal items (D16)** prove themselves with the existing suite: the full `./gradlew build` is green
  before and after, with the same counts of backend-swe and qa tests (minus any test lines the item names). New tests
  are added only for new units (for example the shared email normaliser).
- AC-7.6 is a manual cross-service check through the gateway, as in phase 1.

**Why:** no new libraries, and the same proof style qa already reproduces.

### D16. Code quality: layering and unused code

**Default:**

- The layering guardrail **PL** is binding for every task in this spec. That includes new project code, which must
  follow it from the first commit.
- The audit above becomes refactor items E42, E49–E52 and removal items E43–E48, E53–E54. Each is small, keeps behaviour
  and the contract unchanged, and can be approved on its own. (2026-10-13: E43 withdrawn, replaced by E55; see D17.)
- They run **first**, in Batch A1, so the project controllers and services are written against clean templates. The
  exceptions are E53 and E54, which can only run once the guard rewrite has removed the caches (end of Batch A2).
- **Accept invitation (E42), recommended design: the service owns the "anonymous callers must supply account details"
  rule.**
    - **Controller:** `AuthController.acceptInvitation` makes exactly one service call. It passes the token, the
      caller's id (or none when anonymous) and the raw body (or none). It answers 201 when the service reports that an
      account was created, else 200.
        - The body parameter is `@RequestBody(required = false)` without `@Valid`. A logged-in caller's body is
          therefore never validated (phase-1 Q3).
        - Unreadable JSON is still rejected by Spring MVC before the handler runs (400 `MALFORMED_REQUEST`, AC-1.2).
    - **Service:** one `accept` method replaces the two existing ones. For an anonymous caller, before anything else (
      the phase-1 Q6 order), it validates the body with the injected `jakarta.validation.Validator`. A missing body is
      validated as `{}` (AC-6.10). On failure it throws a typed exception carrying the field errors.
    - **Advice:** `ApiExceptionHandler` maps that exception to the same Problem as Spring's validation errors: 400
      `VALIDATION_FAILED`, the same `detail`, and `errors` entries with the same field names, messages and sort order (
      AC-6.6, AC-6.10).
    - **Deleted:** the `Validator` field, `BindingResult`, `BeanPropertyBindingResult`, `SpringValidatorAdapter`,
      `ReflectionUtils`, `MethodParameter` and the hand-thrown `MethodArgumentNotValidException`.
    - **Rejected alternatives:**
        - a custom `HandlerMethodArgumentResolver`: it moves a business rule ("anonymous needs details") into web
          plumbing, and is more custom code than it removes;
        - validation groups: Bean Validation groups can't depend on whether the caller is logged in;
        - a dedicated validator component called from the controller: the controller would still branch, and the rule
          would stay outside the use case.

**Why:** the user asked for thin controllers and for unneeded code to be removed. Cleaning up first means the phase-2
controllers copy a correct pattern, not the accept-invitation workaround. Behaviour-neutral items backed by the existing
719-test suite are low risk, and each can be rejected without affecting the rest.

### D17. Logging (E55, approved in principle by the user on 2026-10-13)

**Background.** E43 proposed deleting the empty `aop/Logging.java`. The user asked whether comprehensive AOP logging had
been implemented, and if not, to implement it. It hasn't been:

- `aop/Logging.java` is an empty `@Aspect` with no advice, and it isn't a bean.
- Logging is ad hoc in 4 classes: `OrgGuard` (2 WARN), `OrganizationServiceImpl` (5 INFO), `InvitationServiceImpl` (1
  INFO), `ApiExceptionHandler` (2 WARN, 2 ERROR).
- There's no MDC and no request-ID correlation. `Problems.requestId` (package-private, `exception/Problems.java:52–58`)
  validates `X-Request-Id` against `^[A-Za-z0-9._-]{1,128}$` (line 24), but only to echo it in Problem bodies.
- The gateway already sends a valid `X-Request-Id` on every request it forwards, generating a UUID when the client's
  value is missing or invalid (gateway spec, "Request ID"). Phase 1 recommended putting it in the MDC (
  `docs/specs/auth-service.md`, NFR "Observability"); that was never done.

So **E43 is withdrawn**, and E55 implements logging in that class. It runs as its own **Batch A3** (task T17) after
review point A2 and before Batch B. **Why a separate batch:** A2 is authorization-critical and deserves a focused
review. Logging is cross-cutting. Doing it before Batch C means the new project services are covered from their first
commit.

**Default design** (binding for T17; backend-swe chooses class names and mechanics within it):

1. **The aspect** is the existing `aop/Logging.java`, made a Spring bean (`@Component`).
    - **Pointcut:** public methods of `@Service` beans in `com.example.authservice.service..`. Explicitly **excluded**:
      `CustomUserDetailsService`. It runs on the JWT filter path for every authenticated request, and its
      `loadUserByUsername` argument is an email. `JwtTokenProvider` is a `@Component`, so the pointcut doesn't match it,
      and it must stay that way. Guards (`OrgGuard`, `ProjectGuard`), filters, controllers and the advice aren't
      advised.
    - **Controllers aren't advised.** Recommended: the service call already covers every use case, a controller's
      arguments are request bodies (credentials included), and the gateway already writes an access log. That makes a
      controller toggle unnecessary code.
    - **Order:** the aspect wraps the transaction advice. Its order is higher precedence than `@Transactional`'s, so the
      duration includes the commit, and an exception thrown at commit (for example a unique violation at flush) is seen
      as the call's outcome.
    - **Entry and exit at DEBUG.** One entry line naming the class and method. One exit line with the outcome and the
      duration in ms. Guard message building with `isDebugEnabled()`.
    - **No arguments, return values or exception messages, ever** (not only by default). Arguments carry passwords,
      emails and tokens, and exception messages do too (`EmailAlreadyExistsException("Email already exists: " + email)`
      at `AuthServiceImpl:45`, `:62`; `CustomUserDetailsService:26`). A future need for argument logging needs its own
      approval and an allow-list.
    - **One outcome line per advised call, at one level:**

      | Outcome | Outermost advised call in the thread | Nested advised call |
           |---|---|---|
      | Duration above the slow-call threshold (any outcome) | WARN: class, method, duration, threshold, and the outcome | DEBUG |
      | `ApiException` (business error) | INFO: class, method, the `ErrorCode` name, duration; no stack trace | DEBUG |
      | Any other exception | DEBUG: class, method, the exception's simple class name, duration; no stack trace and no message. `ApiExceptionHandler` already logs these once (WARN for constraint and lock failures, ERROR with the stack trace for unexpected ones), so the aspect doesn't log them again at INFO or above | DEBUG |
      | Normal return | DEBUG | DEBUG |

      "Outermost" means no other advised call is in progress on the same thread. It keeps a business error that crosses
      two services from being logged twice at INFO.
    - **Slow-call text** must not contain the advice's message "Request rejected because a lock could not be acquired".
      A lock timeout is slow, and `LockFailureTest` and `QaFixRoundTest` count that WARN line exactly once.
2. **Request correlation in the MDC.**
    - **Request ID** (MDC key `requestId`). A servlet filter that runs **before** the Spring Security filter chain sets
      it, so 401s from the entry point are correlated too. The value is the `X-Request-Id` header when it matches
      `Problems`' pattern. Otherwise it's a newly generated UUID. An invalid header value is never logged (log
      injection).
    - **One validation rule:** the filter reuses `Problems.requestId(HttpServletRequest)`, made `public`. That's a
      one-word change, and the only change E55 makes to `Problems`. No second copy of the pattern.
    - **Problems are unchanged.** A Problem still echoes only a valid request header, and no response header is added.
      So a generated ID appears in logs only, and the contract (C3, C4) doesn't change.
    - **User ID** (MDC key `userId`): the authenticated `UserPrincipal`'s id. It's set by a filter that runs **after**
      the Spring Security filter chain, where the JWT filter has authenticated the request. Anonymous requests, login
      included, have no `userId`. The JWT filter and `SecurityConfig` aren't changed.
    - **Cleanup:** both keys are removed in `finally`, so nothing leaks to the next request on a pooled thread.
    - backend-swe verifies the Boot 4.1 filter ordering (Spring Security's filter chain order; P12) and reports it.
3. **Log pattern.** Every console line shows `requestId` and `userId`. Prefer Boot's `logging.pattern.correlation`
   property, which inserts into the default pattern without replacing it; backend-swe verifies that it exists in Boot
   4.1 and where it renders. Otherwise use a full `logging.pattern.console`. **Constraint:** every line still starts
   with a timestamp without spaces, followed by whitespace and the level. `LockFailureTest:64–68`,
   `UnexpectedErrorTest:39`, `QaFixRoundTest:69–72` and `QaTokenAttackTest:57` match `^\S+\s+LEVEL\s`.
4. **No personal data or secrets in any log line, at any level:** passwords, password hashes, JWTs and `Authorization`
   values, invitation tokens, emails, and full names. Ids (user, organization, invitation, project) are allowed;
   existing INFO lines already use them. BUG-5 and FU-6 established that emails must not appear in logs.
5. **E29 (FU-6), recommended fold-in.** Add `logging.level.org.hibernate.orm.jdbc.error: ERROR` in E56. **Why:** without
   it, E55's "no emails in logs" guarantee is false under a registration race (BUG-5), it's the same file in the same
   batch, and the advice still logs the exception type. backend-swe verifies with a test first that this logger name is
   the one that prints the constraint message on Hibernate 7 / Boot 4.1. **Needs the user's approval** (open question
   L2). If it's not approved, AC-9.10 is dropped and BUG-5 stays open.
6. **Dependency.** AspectJ is on the classpath only transitively. Verified on 2026-10-13 in the Gradle cache:
   `spring-boot-data-jpa-4.1.0.pom` depends on `org.springframework:spring-aspects`, and `spring-aspects-7.0.8.pom`
   depends on `org.aspectj:aspectjweaver` 1.9.25 (compile scope). Boot 4.1's AOP starter is *
   *`org.springframework.boot:spring-boot-starter-aspectj`**: it's listed in `spring-boot-dependencies-4.1.0.pom` (line
   2114). There's no `spring-boot-starter-aop` in that BOM; the Boot 3 name doesn't exist in 4.1. **Recommended (E57,
   needs the user's approval, open question L1):** declare
   `implementation 'org.springframework.boot:spring-boot-starter-aspectj'`, version managed by the Boot plugin, so no
   version change. **Why:** the code uses AspectJ annotations directly, and a direct use should be a declared
   dependency, not a side effect of the JPA starter. **Alternative:** rely on the transitive weaver, with no build
   change. That works today. Either way, T17 proves that the aspect is actually applied, through AC-9.1.
7. **Configuration** (typed, E56). A `@ConfigurationProperties` record under `app.logging`, registered in
   `PropertiesConfiguration` next to the others (E50's pattern). It has two settings:
    - `enabled` (default `true`): when `false`, the aspect logs nothing;
    - `slow-call-threshold` (a `Duration`, default `500ms`): above it, the outermost call logs WARN. 500 ms leaves room
      for BCrypt at login and register.

   No other toggles.
8. **Existing log statements stay as they are** (G4): the 4 classes keep their messages and levels. E55 adds; it doesn't
   rewrite.
9. **Reuse later.** scenario-service and report-service should adopt the same pattern in their first specs: the same MDC
   keys `requestId` and `userId`, the same `X-Request-Id` validation, the same aspect rules (no arguments, one outcome
   line, business errors at INFO) and the same no-PII test. G9 forbids a shared library, so each service has its own
   copy, specified by reference to D17 and US-9.

**Why:** the user asked for comprehensive logging rather than deleting the class. This is the smallest design that gives
a trace per request (request ID end to end, from the gateway), performance signals (durations, slow-call WARN), and
business-error visibility. It keeps the phase-1 guarantees: no secrets, no emails, exactly one ERROR per unexpected
failure, and no stack traces for expected failures.

## User stories

### US-1: Create projects (P0)

As an organization admin, I want to create projects in my organization, so that each team's features and test runs have
their own place.

```gherkin
Feature: Create projects

  Background:
    Given the Projects fixture

  @US-1 @AC-1.1 @P0
  Scenario: An organization admin creates a project and administers it implicitly
    When Ben sends "POST {acme-projects}" with "{token of Ben}" and body {"name":"Mobile App"}
    Then the response status is 201
    And the body has a UUID "id", "orgId" "{acme}", "name" "Mobile App", a "createdAt", "archivedAt" null and "myRole" "ADMIN"
    And the "Location" header is exactly "/api/orgs/{acme}/projects/" followed by that id
    And "GET {acme-projects}/{that id}/members" by Ben returns []

  @US-1 @AC-1.2 @P0
  Scenario Outline: Only organization owners and admins can create projects
    When <actor> sends "POST {acme-projects}" with "{token of <actor>}" and body {"name":"Mobile App"}
    Then <outcome>

    Examples:
      | actor | outcome                                                                                        |
      | Ana   | the response status is 201                                                                     |
      | Ben   | the response status is 201                                                                     |
      | Cai   | the response is a Problem 403 / FORBIDDEN, and Acme has no project named "Mobile App"          |
      | Gus   | the response is a Problem 403 / FORBIDDEN, and Acme has no project named "Mobile App"          |
      | Dan   | the response is a Problem 403 / FORBIDDEN, and Acme has no project named "Mobile App"          |
      | Eve   | the response is a Problem 403 / FORBIDDEN, and Acme has no project named "Mobile App"          |

  @US-1 @AC-1.3 @P0
  Scenario Outline: An invalid project name is a validation error
    When Ana sends "POST {acme-projects}" with "{token of Ana}" and body <body>
    Then the response is a Problem 400 / VALIDATION_FAILED with an "errors" entry for "name"
    And Acme still has exactly the projects "Billing" and "Checkout"

    Examples:
      | body                        |
      | {}                          |
      | {"name":""}                 |
      | {"name":"   "}              |
      | {"name":"<101 characters>"} |

  @US-1 @AC-1.4 @P1
  Scenario Outline: A project name can't repeat inside one organization, whatever the case or spacing
    Given <setup>
    When Ana sends "POST {acme-projects}" with "{token of Ana}" and body {"name":"<name>"}
    Then the response is a Problem 409 / PROJECT_NAME_TAKEN
    And Acme still has exactly 2 projects

    Examples:
      | setup                         | name           |
      | nothing else                  | Checkout       |
      | nothing else                  | CHECKOUT       |
      | nothing else                  |   checkout     |
      | Ana has archived Billing      | billing        |

  @US-1 @AC-1.5 @P1
  Scenario: The same name is allowed in another organization
    When Eve sends "POST /api/orgs/{initech}/projects" with "{token of Eve}" and body {"name":"Checkout"}
    Then the response status is 201 and the body has "orgId" "{initech}"

  @US-1 @AC-1.6 @P1
  Scenario Outline: An unknown or malformed organization id reveals nothing
    When Ana sends "POST /api/orgs/<id>/projects" with "{token of Ana}" and body {"name":"Mobile App"}
    Then the response is a Problem <status> / <code>

    Examples:
      | id           | status | code              |
      | {unknown id} | 403    | FORBIDDEN         |
      | {initech}    | 403    | FORBIDDEN         |
      | not-a-uuid   | 400    | MALFORMED_REQUEST |

  @US-1 @AC-1.7 @P2
  Scenario: Simultaneous creations with one name create exactly one project
    When Ana and Ben together send 5 requests "POST {acme-projects}" with body {"name":"Mobile App"} at the same moment
    Then exactly one response has status 201
    And each of the other 4 is a Problem 409 / PROJECT_NAME_TAKEN
    And Acme has exactly one project named "Mobile App"

  @US-1 @AC-1.8 @P2
  Scenario: The name is stored without surrounding whitespace
    When Ana sends "POST {acme-projects}" with "{token of Ana}" and body {"name":"  Mobile App  "}
    Then the response status is 201 and the body has "name" "Mobile App"
```

### US-2: List and view projects (P0)

As an organization member, I want to see the projects I have access to and my role in each, so that I know where I can
work and what I may do.

```gherkin
Feature: List and view projects

  Background:
    Given the Projects fixture

  @US-2 @AC-2.1 @P0
  Scenario Outline: Each member sees only the projects they have access to
    When <actor> sends "GET {acme-projects}" with "{token of <actor>}"
    Then <outcome>

    Examples:
      | actor | outcome |
      | Ana   | the response status is 200 and the body lists exactly "Billing" with "myRole" "ADMIN", then "Checkout" with "myRole" "ADMIN" |
      | Ben   | the response status is 200 and the body lists exactly "Billing" with "myRole" "ADMIN", then "Checkout" with "myRole" "ADMIN" |
      | Cai   | the response status is 200 and the body lists exactly "Checkout" with "myRole" "ADMIN"   |
      | Gus   | the response status is 200 and the body lists exactly "Checkout" with "myRole" "MEMBER"  |
      | Hal   | the response status is 200 and the body lists exactly "Checkout" with "myRole" "TESTER"  |
      | Dan   | the response status is 200 and the body lists exactly "Checkout" with "myRole" "READER"  |
      | Ivy   | the response status is 200 and the body is []                                            |
      | Eve   | the response is a Problem 403 / FORBIDDEN                                                |

  @US-2 @AC-2.2 @P0
  Scenario Outline: Viewing a project shows the caller's effective role
    When <actor> sends "GET {acme-projects}/{checkout}" with "{token of <actor>}"
    Then <outcome>

    Examples:
      | actor | outcome |
      | Ana   | the response status is 200, the body has "id" "{checkout}", "orgId" "{acme}", "name" "Checkout", a "createdAt", "archivedAt" null and "myRole" "ADMIN" |
      | Ben   | the response status is 200 and "myRole" is "ADMIN"  |
      | Cai   | the response status is 200 and "myRole" is "ADMIN"  |
      | Gus   | the response status is 200 and "myRole" is "MEMBER" |
      | Hal   | the response status is 200 and "myRole" is "TESTER" |
      | Dan   | the response status is 200 and "myRole" is "READER" |
      | Ivy   | the response is a Problem 403 / FORBIDDEN           |
      | Eve   | the response is a Problem 403 / FORBIDDEN           |

  @US-2 @AC-2.3 @P0
  Scenario Outline: A project without access, of another organization, or unknown reveals nothing
    When <actor> sends "GET <path>" with "{token of <actor>}"
    Then the response is a Problem <status> / <code>

    Examples:
      | actor | path                                    | status | code              |
      | Cai   | {acme-projects}/{billing}               | 403    | FORBIDDEN         |
      | Ana   | {acme-projects}/{portal}                | 403    | FORBIDDEN         |
      | Eve   | /api/orgs/{initech}/projects/{checkout} | 403    | FORBIDDEN         |
      | Ana   | {acme-projects}/{unknown id}            | 403    | FORBIDDEN         |
      | Ana   | {acme-projects}/not-a-uuid              | 400    | MALFORMED_REQUEST |

  @US-2 @AC-2.4 @P1
  Scenario: Archived projects are listed with their archive time
    Given Ana has archived Billing
    When Ana sends "GET {acme-projects}" with "{token of Ana}"
    Then the body lists "Billing" with a non-null "archivedAt", then "Checkout" with "archivedAt" null
```

### US-3: Rename, archive and restore a project (P1)

As a project admin, I want to rename a project and archive it when it's finished, so that the project list stays
accurate without losing history.

```gherkin
Feature: Rename, archive and restore a project

  Background:
    Given the Projects fixture

  @US-3 @AC-3.1 @P1
  Scenario Outline: Only effective project admins can rename
    When <actor> sends "PATCH {acme-projects}/{checkout}" with "{token of <actor>}" and body {"name":"Checkout Web"}
    Then <outcome>

    Examples:
      | actor | outcome                                                                                |
      | Ana   | the response status is 200 and the body has "name" "Checkout Web" and "myRole" "ADMIN" |
      | Ben   | the response status is 200 and the project's name is "Checkout Web"                   |
      | Cai   | the response status is 200 and the project's name is "Checkout Web"                   |
      | Gus   | the response is a Problem 403 / FORBIDDEN and the project's name is still "Checkout"   |
      | Hal   | the response is a Problem 403 / FORBIDDEN and the project's name is still "Checkout"   |
      | Dan   | the response is a Problem 403 / FORBIDDEN and the project's name is still "Checkout"   |
      | Ivy   | the response is a Problem 403 / FORBIDDEN and the project's name is still "Checkout"   |
      | Eve   | the response is a Problem 403 / FORBIDDEN and the project's name is still "Checkout"   |

  @US-3 @AC-3.2 @P1
  Scenario Outline: Renaming follows the uniqueness rule, except for the project's own name
    When Cai sends "PATCH {acme-projects}/{checkout}" with "{token of Cai}" and body {"name":"<name>"}
    Then <outcome>

    Examples:
      | name     | outcome                                                                              |
      | billing  | the response is a Problem 409 / PROJECT_NAME_TAKEN and the name is still "Checkout"  |
      | CHECKOUT | the response status is 200 and the project's name is "CHECKOUT"                      |

  @US-3 @AC-3.3 @P1
  Scenario Outline: An invalid new name is a validation error
    When Cai sends "PATCH {acme-projects}/{checkout}" with "{token of Cai}" and body <body>
    Then the response is a Problem 400 / VALIDATION_FAILED with an "errors" entry for "name"
    And the project's name is still "Checkout"

    Examples:
      | body                        |
      | {}                          |
      | {"name":"   "}              |
      | {"name":"<101 characters>"} |

  @US-3 @AC-3.4 @P1
  Scenario Outline: Only effective project admins can archive
    When <actor> sends "POST {acme-projects}/{checkout}/archive" with "{token of <actor>}"
    Then <outcome>

    Examples:
      | actor | outcome                                                                         |
      | Ana   | the response status is 200 and the body has a non-null "archivedAt"             |
      | Cai   | the response status is 200 and the body has a non-null "archivedAt"             |
      | Gus   | the response is a Problem 403 / FORBIDDEN and Checkout's "archivedAt" is null   |
      | Dan   | the response is a Problem 403 / FORBIDDEN and Checkout's "archivedAt" is null   |
      | Eve   | the response is a Problem 403 / FORBIDDEN and Checkout's "archivedAt" is null   |

  @US-3 @AC-3.5 @P1
  Scenario: An archived project stays readable for everyone with access
    Given Cai has archived Checkout
    When Dan sends "GET {acme-projects}/{checkout}" with "{token of Dan}"
    Then the response status is 200 with "myRole" "READER" and a non-null "archivedAt"
    And "GET {acme-projects}/{checkout}/members" by Dan has status 200

  @US-3 @AC-3.6 @P1
  Scenario Outline: An archived project rejects every change except restore
    Given Cai has archived Checkout
    When <actor> sends "<request>" with "{token of <actor>}"
    Then the response is a Problem 409 / PROJECT_ARCHIVED
    And the project's name and its member list are unchanged

    Examples:
      | actor | request                                                                                  |
      | Cai   | PATCH {acme-projects}/{checkout} with body {"name":"Checkout Web"}                       |
      | Cai   | POST {acme-projects}/{checkout}/members with body {"userId":"{Ivy.id}","role":"READER"}  |
      | Cai   | PATCH {acme-projects}/{checkout}/members/{Hal.id} with body {"role":"MEMBER"}            |
      | Cai   | DELETE {acme-projects}/{checkout}/members/{Hal.id}                                       |
      | Hal   | DELETE {acme-projects}/{checkout}/members/{Hal.id}                                       |

  @US-3 @AC-3.7 @P1
  Scenario Outline: Only effective project admins can restore
    Given Cai has archived Checkout
    When <actor> sends "POST {acme-projects}/{checkout}/restore" with "{token of <actor>}"
    Then <outcome>

    Examples:
      | actor | outcome                                                                                                                         |
      | Cai   | the response status is 200, the body has "archivedAt" null, and renaming Checkout to "Checkout Web" by Cai then has status 200  |
      | Gus   | the response is a Problem 403 / FORBIDDEN and Checkout's "archivedAt" is not null                                               |
      | Eve   | the response is a Problem 403 / FORBIDDEN and Checkout's "archivedAt" is not null                                               |

  @US-3 @AC-3.8 @P2
  Scenario Outline: Archive and restore are idempotent
    Given <setup>
    When Cai sends "POST {acme-projects}/{checkout}/<action>" with "{token of Cai}"
    Then the response status is 200 and <state>

    Examples:
      | setup                               | action  | state                              |
      | Cai has archived Checkout at time T | archive | the body's "archivedAt" equals T   |
      | nothing else                        | restore | the body's "archivedAt" is null    |
```

### US-4: View and add project members (P0)

As a project admin, I want to add colleagues from my organization to the project with the right role, so that they can
work on it.

```gherkin
Feature: View and add project members

  Background:
    Given the Projects fixture

  @US-4 @AC-4.1 @P0
  Scenario Outline: Everyone with access sees the explicit members, nobody else does
    When <actor> sends "GET {acme-projects}/{checkout}/members" with "{token of <actor>}"
    Then <outcome>

    Examples:
      | actor | outcome |
      | Ana   | the response status is 200 and the body lists exactly Cai ADMIN, Dan READER, Gus MEMBER, Hal TESTER, ordered by email, each with "userId", "email", "fullName", "role" and "joinedAt" and no member whose name contains "password" |
      | Hal   | the response status is 200 and the body lists the same 4 members |
      | Dan   | the response status is 200 and the body lists the same 4 members |
      | Ivy   | the response is a Problem 403 / FORBIDDEN |
      | Eve   | the response is a Problem 403 / FORBIDDEN |

  @US-4 @AC-4.2 @P0
  Scenario: A project admin adds an organization member, who gets access at once
    Given Ivy holds "{token of Ivy}", issued before the change
    When Cai sends "POST {acme-projects}/{checkout}/members" with "{token of Cai}" and body {"userId":"{Ivy.id}","role":"TESTER"}
    Then the response status is 201
    And the body has "userId" "{Ivy.id}", "email" "ivy@example.com", "fullName" "Ivy Moss", "role" "TESTER" and a "joinedAt"
    And "GET {acme-projects}/{checkout}" by Ivy with that token has status 200 and "myRole" "TESTER"

  @US-4 @AC-4.3 @P0
  Scenario Outline: Only effective project admins can add members
    When <actor> sends "POST {acme-projects}/{checkout}/members" with "{token of <actor>}" and body {"userId":"{Ivy.id}","role":"READER"}
    Then <outcome>

    Examples:
      | actor | outcome                                                                       |
      | Ana   | the response status is 201                                                    |
      | Ben   | the response status is 201                                                    |
      | Cai   | the response status is 201                                                    |
      | Gus   | the response is a Problem 403 / FORBIDDEN and Ivy is not a member of Checkout |
      | Hal   | the response is a Problem 403 / FORBIDDEN and Ivy is not a member of Checkout |
      | Dan   | the response is a Problem 403 / FORBIDDEN and Ivy is not a member of Checkout |
      | Ivy   | the response is a Problem 403 / FORBIDDEN and Ivy is not a member of Checkout |
      | Eve   | the response is a Problem 403 / FORBIDDEN and Ivy is not a member of Checkout |

  @US-4 @AC-4.4 @P0
  Scenario Outline: Only members of the project's organization can be added
    When Ana sends "POST {acme-projects}/{checkout}/members" with "{token of Ana}" and body {"userId":"<user>","role":"READER"}
    Then the response is a Problem 422 / NOT_ORG_MEMBER
    And the Checkout member list is unchanged

    Examples:
      | user         |
      | {Eve.id}     |
      | {unknown id} |

  @US-4 @AC-4.5 @P1
  Scenario: Adding someone who is already an explicit member is a conflict
    When Ana sends "POST {acme-projects}/{checkout}/members" with "{token of Ana}" and body {"userId":"{Gus.id}","role":"ADMIN"}
    Then the response is a Problem 409 / ALREADY_MEMBER
    And Gus's role in Checkout is still "MEMBER"

  @US-4 @AC-4.6 @P0
  Scenario Outline: An organization reader can only be a project reader
    When Ana sends "POST {acme-projects}/{billing}/members" with "{token of Ana}" and body {"userId":"{Dan.id}","role":"<role>"}
    Then <outcome>

    Examples:
      | role   | outcome                                                                                    |
      | READER | the response status is 201 and Dan's "myRole" in Billing is "READER"                       |
      | TESTER | the response is a Problem 422 / PROJECT_ROLE_NOT_ALLOWED and Dan has no access to Billing  |
      | MEMBER | the response is a Problem 422 / PROJECT_ROLE_NOT_ALLOWED and Dan has no access to Billing  |
      | ADMIN  | the response is a Problem 422 / PROJECT_ROLE_NOT_ALLOWED and Dan has no access to Billing  |

  @US-4 @AC-4.7 @P1
  Scenario Outline: An invalid add request is rejected
    When Ana sends "POST {acme-projects}/{checkout}/members" with "{token of Ana}" and body <body>
    Then the response is a Problem 400 / <code>
    And the Checkout member list is unchanged

    Examples:
      | body                                    | code              |
      | {}                                      | VALIDATION_FAILED |
      | {"userId":"{Ivy.id}"}                   | VALIDATION_FAILED |
      | {"userId":"{Ivy.id}","role":"OWNER"}    | MALFORMED_REQUEST |
      | {"userId":"{Ivy.id}","role":1}          | MALFORMED_REQUEST |
      | {"userId":"not-a-uuid","role":"READER"} | MALFORMED_REQUEST |

  @US-4 @AC-4.8 @P2
  Scenario: A project membership uses no organization seat
    Given Acme's seat limit is seeded to 7, so its 7 members use every seat
    When Ana sends "POST {acme-projects}/{billing}/members" with "{token of Ana}" and body {"userId":"{Ivy.id}","role":"MEMBER"}
    Then the response status is 201
    And Ben's invitation of "fay@example.com" to Acme is still a Problem 409 / SEAT_LIMIT_REACHED

  @US-4 @AC-4.9 @P2
  Scenario: An implicit admin can also be added explicitly, and keeps admin rights
    When Ana sends "POST {acme-projects}/{checkout}/members" with "{token of Ana}" and body {"userId":"{Ben.id}","role":"READER"}
    Then the response status is 201
    And "GET {acme-projects}/{checkout}" by Ben has "myRole" "ADMIN"
```

### US-5: Change project roles, remove members, leave (P1)

As a project admin, I want to change roles and remove people, and as a project member I want to leave, so that project
access matches who works on it.

```gherkin
Feature: Change project roles, remove members, leave

  Background:
    Given the Projects fixture

  @US-5 @AC-5.1 @P1
  Scenario Outline: Only effective project admins can change roles
    When <actor> sends "PATCH {acme-projects}/{checkout}/members/{Hal.id}" with "{token of <actor>}" and body {"role":"<new role>"}
    Then <outcome>

    Examples:
      | actor | new role | outcome                                                                                                       |
      | Ana   | MEMBER   | the response status is 200, the body has "userId" "{Hal.id}" and "role" "MEMBER", and Hal's role is "MEMBER"  |
      | Ben   | READER   | the response status is 200 and Hal's role is "READER"                                                         |
      | Cai   | ADMIN    | the response status is 200 and Hal's role is "ADMIN"                                                          |
      | Gus   | READER   | the response is a Problem 403 / FORBIDDEN and Hal's role is still "TESTER"                                    |
      | Dan   | READER   | the response is a Problem 403 / FORBIDDEN and Hal's role is still "TESTER"                                    |
      | Eve   | READER   | the response is a Problem 403 / FORBIDDEN and Hal's role is still "TESTER"                                    |

  @US-5 @AC-5.2 @P1
  Scenario Outline: Nobody can change their own project role
    When <actor> sends "PATCH {acme-projects}/{checkout}/members/{<actor>.id}" with "{token of <actor>}" and body {"role":"READER"}
    Then the response is a Problem 422 / SELF_ROLE_CHANGE
    And <actor>'s role in Checkout is unchanged

    Examples:
      | actor |
      | Cai   |
      | Gus   |

  @US-5 @AC-5.3 @P1
  Scenario Outline: The target must be an explicit member of the project
    When Ana sends "PATCH {acme-projects}/{checkout}/members/<target>" with "{token of Ana}" and body {"role":"READER"}
    Then the response is a Problem 404 / MEMBER_NOT_FOUND

    Examples:
      | target       |
      | {Ivy.id}     |
      | {Ben.id}     |
      | {unknown id} |

  @US-5 @AC-5.4 @P1
  Scenario: A role change can't exceed the organization role's cap
    When Ana sends "PATCH {acme-projects}/{checkout}/members/{Dan.id}" with "{token of Ana}" and body {"role":"TESTER"}
    Then the response is a Problem 422 / PROJECT_ROLE_NOT_ALLOWED
    And Dan's role in Checkout is still "READER"

  @US-5 @AC-5.5 @P1
  Scenario: A demotion takes effect at once, even for a token issued earlier
    Given Cai holds "{token of Cai}"
    And Ana has changed Cai's role in Checkout to "READER"
    When Cai sends "PATCH {acme-projects}/{checkout}" with that token and body {"name":"Checkout Web"}
    Then the response is a Problem 403 / FORBIDDEN

  @US-5 @AC-5.6 @P1
  Scenario Outline: Only effective project admins can remove others
    When <actor> sends "DELETE {acme-projects}/{checkout}/members/{Hal.id}" with "{token of <actor>}"
    Then <outcome>

    Examples:
      | actor | outcome                                                              |
      | Ana   | the response status is 204 and Hal is no longer a member of Checkout |
      | Ben   | the response status is 204 and Hal is no longer a member of Checkout |
      | Cai   | the response status is 204 and Hal is no longer a member of Checkout |
      | Gus   | the response is a Problem 403 / FORBIDDEN and Hal is still a member  |
      | Dan   | the response is a Problem 403 / FORBIDDEN and Hal is still a member  |
      | Eve   | the response is a Problem 403 / FORBIDDEN and Hal is still a member  |

  @US-5 @AC-5.7 @P1
  Scenario Outline: Any explicit member can leave
    When <actor> sends "DELETE {acme-projects}/{checkout}/members/{<actor>.id}" with "{token of <actor>}"
    Then the response status is 204 and <actor> is no longer a member of Checkout

    Examples:
      | actor |
      | Cai   |
      | Gus   |
      | Hal   |
      | Dan   |

  @US-5 @AC-5.8 @P1
  Scenario: A removed member loses access at once
    Given Hal holds "{token of Hal}"
    And Cai has removed Hal from Checkout
    When Hal sends "GET {acme-projects}/{checkout}" with that token
    Then the response is a Problem 403 / FORBIDDEN
    And "GET {acme-projects}" by Hal with that token returns []

  @US-5 @AC-5.9 @P1
  Scenario Outline: Implicit admins have no membership to remove
    When <actor> sends "DELETE {acme-projects}/{checkout}/members/{Ben.id}" with "{token of <actor>}"
    Then the response is a Problem 404 / MEMBER_NOT_FOUND
    And "GET {acme-projects}/{checkout}" by Ben still has "myRole" "ADMIN"

    Examples:
      | actor |
      | Ana   |
      | Ben   |

  @US-5 @AC-5.10 @P2
  Scenario: Setting the role a member already has is a no-op success
    When Ana sends "PATCH {acme-projects}/{checkout}/members/{Gus.id}" with "{token of Ana}" and body {"role":"MEMBER"}
    Then the response status is 200 and Gus's role in Checkout is "MEMBER"
```

### US-6: Organization changes carry through to projects (P1)

As an organization owner, I want project access to follow organization membership and roles, so that nobody keeps
project access they shouldn't have.

```gherkin
Feature: Organization changes carry through to projects

  Background:
    Given the Projects fixture

  @US-6 @AC-6.1 @P1
  Scenario Outline: Leaving or being removed from the organization removes project memberships
    Given <change>
    When Cai sends "GET {acme-projects}/{checkout}/members" with "{token of Cai}"
    Then the body doesn't list <person>
    And no project membership row exists for <person>

    Examples:
      | change                        | person |
      | Ben has removed Hal from Acme | Hal    |
      | Gus has left Acme             | Gus    |

  @US-6 @AC-6.2 @P1
  Scenario: Someone who rejoins the organization starts without project access
    Given Ben has removed Hal from Acme
    And Ben has invited "hal@example.com" to Acme as "MEMBER", and Hal has accepted while logged in
    When Hal sends "GET {acme-projects}" with "{token of Hal}"
    Then the response status is 200 and the body is []

  @US-6 @AC-6.3 @P1
  Scenario: Becoming an organization reader lowers project roles to reader
    Given Ana has changed Cai's Acme role to "READER"
    When Cai sends "PATCH {acme-projects}/{checkout}" with "{token of Cai}" and body {"name":"Checkout Web"}
    Then the response is a Problem 403 / FORBIDDEN
    And "GET {acme-projects}/{checkout}/members" by Ana lists Cai with role "READER"

  @US-6 @AC-6.4 @P1
  Scenario Outline: Other organization role changes keep explicit project roles
    Given Ana has changed <person>'s Acme role to "<org role>"
    When Ana sends "GET {acme-projects}/{checkout}/members" with "{token of Ana}"
    Then the body lists <person> with role "<project role>"
    And "GET {acme-projects}/{checkout}" by <person> has "myRole" "<effective role>"

    Examples:
      | person | org role | project role | effective role |
      | Gus    | ADMIN    | MEMBER       | ADMIN          |
      | Dan    | MEMBER   | READER       | READER         |
      | Hal    | READER   | READER       | READER         |

  @US-6 @AC-6.5 @P1
  Scenario: Becoming an organization admin gives admin rights on every project
    Given Ana has changed Dan's Acme role to "ADMIN"
    When Dan sends "GET {acme-projects}/{billing}" with "{token of Dan}"
    Then the response status is 200 and "myRole" is "ADMIN"

  @US-6 @AC-6.6 @P1
  Scenario: Losing organization admin removes implicit project admin rights
    Given Ana has changed Ben's Acme role to "MEMBER"
    When Ben sends "GET {acme-projects}/{billing}" with "{token of Ben}"
    Then the response is a Problem 403 / FORBIDDEN
    And "GET {acme-projects}" by Ben returns []
```

### US-7: Project access check for other services (P0)

As a scenario-service or report-service developer, I want one call that tells me the caller's effective role in a
project, so that my service enforces the same permissions as auth-service without copying its data.

All scenarios except AC-7.6 are direct calls (port 8081).

```gherkin
Feature: Project access check for other services

  Background:
    Given the Projects fixture

  @US-7 @AC-7.1 @P0
  Scenario Outline: The access check returns the caller's effective role
    When a service sends "GET /internal/projects/{checkout}/access" with header "Authorization: Bearer {token of <user>}"
    Then <outcome>

    Examples:
      | user | outcome |
      | Ana  | the response status is 200 and the body is exactly {"projectId":"{checkout}","orgId":"{acme}","role":"ADMIN","archived":false} |
      | Ben  | the response status is 200 and "role" is "ADMIN"  |
      | Cai  | the response status is 200 and "role" is "ADMIN"  |
      | Gus  | the response status is 200 and "role" is "MEMBER" |
      | Hal  | the response status is 200 and "role" is "TESTER" |
      | Dan  | the response status is 200 and "role" is "READER" |
      | Ivy  | the response is a Problem 403 / FORBIDDEN         |
      | Eve  | the response is a Problem 403 / FORBIDDEN         |

  @US-7 @AC-7.2 @P0
  Scenario Outline: An unknown or malformed project id reveals nothing
    When a service sends "GET /internal/projects/<id>/access" with header "Authorization: Bearer {token of Ana}"
    Then the response is a Problem <status> / <code>

    Examples:
      | id           | status | code              |
      | {unknown id} | 403    | FORBIDDEN         |
      | {portal}     | 403    | FORBIDDEN         |
      | not-a-uuid   | 400    | MALFORMED_REQUEST |

  @US-7 @AC-7.3 @P0
  Scenario Outline: The access check needs the end user's valid token
    When a service sends "GET /internal/projects/{checkout}/access" with Authorization header <authorization>
    Then the response is a Problem 401 / UNAUTHENTICATED
    And the response has a "WWW-Authenticate" header whose value starts with "Bearer"

    Examples:
      | authorization                                                         |
      | (none)                                                                |
      | "Bearer {Hal's token whose exp is 1 hour in the past}"                |
      | "Bearer {Hal's claims signed HS512 with a different 64-byte secret}"  |

  @US-7 @AC-7.4 @P0
  Scenario: An archived project is reported as archived, with the role
    Given Cai has archived Checkout
    When a service sends "GET /internal/projects/{checkout}/access" with header "Authorization: Bearer {token of Hal}"
    Then the response status is 200 and the body has "role" "TESTER" and "archived" true

  @US-7 @AC-7.5 @P0
  Scenario: A removal is visible to the access check on the next request
    Given "GET /internal/projects/{checkout}/access" with "{token of Hal}" has just returned 200 with "role" "TESTER"
    And Cai has removed Hal from Checkout
    When a service immediately sends "GET /internal/projects/{checkout}/access" with the same token
    Then the response is a Problem 403 / FORBIDDEN

  @US-7 @AC-7.6 @P1
  Scenario: The access check isn't reachable through the gateway
    Given the gateway and auth-service run with the "dev" profile
    When a client sends "GET /internal/projects/{checkout}/access" to the gateway on port 8080 with "{token of Ana}"
    Then the response is the gateway's 404 Problem, without a "code" member
```

### US-9: Correlated, safe operational logs (P1)

As an operator of auth-service, I want every log line of a request to carry its request ID and user ID, with timings and
business outcomes of each use case, so that I can trace one call end to end without personal data or secrets ending up
in the logs.

**Numbering.** US-8 is skipped on purpose: this spec cites phase 1's AC-8.1 to AC-8.6 (E38), and a phase-2 AC-8.x would
be ambiguous.

**Test setup for every US-9 scenario:**

- The Acme fixture (phase 1). Log output is captured with `OutputCaptureExtension`.
- "The aspect logger at DEBUG" means `logging.level.com.example.authservice.aop=DEBUG` for that test only. A test with
  its own properties starts its own context, so it uses its own database URL (G3).
- "Log of the request" means the captured output between the request's start and its end.
- "An aspect line" means a line written by the `aop.Logging` logger.

```gherkin
Feature: Operational logging and request correlation

  @US-9 @AC-9.1 @P1
  Scenario: A use case logs its entry and exit at DEBUG, with a duration and without arguments
    Given the aspect logger at DEBUG
    When Ana sends "POST /api/orgs" with name "Logging Org QA" and "X-Request-Id: req-91"
    Then the response status is 201
    And the log of the request has exactly one DEBUG aspect line for entering the organization service's create method
    And exactly one DEBUG aspect line for leaving it, with a duration in ms
    And no line of the log of the request contains "Logging Org QA"

  @US-9 @AC-9.2 @P1
  Scenario: At the default level, a successful use case writes no aspect line
    Given the default logging levels
    When Ana sends "GET /api/orgs"
    Then the response status is 200
    And the log of the request has no aspect line

  @US-9 @AC-9.3 @P1
  Scenario: A business error is logged once at INFO, with its code and without a stack trace
    Given the default logging levels
    When Ana sends "POST /api/orgs/{acme}/invitations" with email "dan@example.com" and role "MEMBER"
    Then the response is a Problem 409 "ALREADY_MEMBER"
    And the log of the request has exactly one INFO aspect line, and it contains "ALREADY_MEMBER"
    And the log of the request has no stack trace line
    And no line of the log of the request contains "dan@example.com"

  @US-9 @AC-9.4 @P1
  Scenario: An unexpected failure is not logged twice
    Given the aspect logger at DEBUG
    And listing Ana's organizations fails inside the organization service with an unexpected IllegalStateException with message "boom-QA"
    When Ana sends "GET /api/orgs"
    Then the response is a Problem 500 "INTERNAL_ERROR"
    And the log of the request has exactly one ERROR line, the one from the exception handler
    And the aspect lines of the request name "IllegalStateException" and contain neither "boom-QA" nor a stack trace line

  @US-9 @AC-9.5 @P1
  Scenario Outline: A call slower than the configured threshold is logged at WARN
    Given "app.logging.slow-call-threshold" is <threshold>
    When Fay registers with email "fay@example.com", full name "Fay Lowe" and password "Secret123!"
    Then the response status is 201
    And the log of the request has <count> WARN aspect lines
    And no line of the log of the request contains "fay@example.com", "Fay Lowe" or "Secret123!"

    Examples:
      | threshold | count |
      | 1ms       | 1     |
      | 1h        | 0     |

  @US-9 @AC-9.6 @P1
  Scenario Outline: Every log line of a request carries its request ID and user ID
    Given the aspect logger at DEBUG
    When <caller> sends <request> with "X-Request-Id: req-96"
    Then every line in the log of the request contains "req-96"
    And <user id rule>

    Examples:
      | caller          | request              | user id rule                                                      |
      | Ana (logged in) | "GET /api/orgs"      | every line in the log of the request contains Ana's user id       |
      | an anonymous caller | "POST /api/auth/login" with Ana's credentials | no line in the log of the request contains Ana's user id |

  @US-9 @AC-9.7 @P1
  Scenario Outline: A missing or invalid request ID is replaced in the logs and never echoed
    Given the aspect logger at DEBUG
    When Ana sends "GET /api/orgs/{unknown id}" <header>
    Then the response is a Problem 403 "FORBIDDEN" without a "requestId" member
    And every line in the log of the request contains the same generated UUID as its request ID
    And the sent header value, if any, appears in no line of the log of the request

    Examples:
      | header                             |
      | without "X-Request-Id"             |
      | with "X-Request-Id: bad id QA"     |
      | with an "X-Request-Id" of 129 "a"s |

  @US-9 @AC-9.8 @P1
  Scenario: Request correlation is cleared after each request
    Given Ana sent "GET /api/orgs" with "X-Request-Id: req-98"
    When the request has completed
    Then the logging MDC of the thread has neither "requestId" nor "userId"
    And a following anonymous "GET /api/orgs" without "X-Request-Id" has no log line containing "req-98" or Ana's user id

  @US-9 @AC-9.9 @P1
  Scenario: No personal data or secrets are logged during the full account flow
    Given the logger "com.example.authservice" at DEBUG
    When Fay registers, logs in and creates "Fay Org QA"
    And Ana invites "gus@example.com" as "MEMBER" to Acme
    And Gus accepts the invitation anonymously with full name "Gus Hale" and password "Secret123!"
    And Fay logs in with a wrong password "Wrong123!"
    And Fay registers again with "fay@example.com"
    Then the captured log contains none of these values:
      | every password and password hash used |
      | every JWT issued, and every Authorization header value |
      | the invitation token |
      | "fay@example.com", "gus@example.com" (any letter case) |
      | "Fay Lowe", "Gus Hale" |

  @US-9 @AC-9.10 @P2
  Scenario: A registration race doesn't log the email
    # Only if E29 is folded into E56 (open question L2). Repeated 5 times, like AC-2.11.
    Given the default logging levels
    When two registrations for "race-qa@example.com" are sent at the same time
    Then one is 201 and the other is a Problem 409 "EMAIL_ALREADY_REGISTERED"
    And no line of the captured log contains "race-qa@example.com" in any letter case

  @US-9 @AC-9.13 @P2
  Scenario: A duplicate registration doesn't log the email when resolved exceptions are logged
    # E58, T18. Forces the setting devtools turns on under bootRun.
    Given "spring.mvc.log-resolved-exception" is forced to true, as devtools does
    And "dev-dup@example.com" is already registered
    When "dev-dup@example.com" is registered again
    Then the response is a Problem 409 "EMAIL_ALREADY_REGISTERED"
    And no line of the captured log contains "dev-dup@example.com" in any letter case

  @US-9 @AC-9.11 @P2
  Scenario: The JWT filter path is not advised
    Given the aspect logger at DEBUG
    When Ana sends "GET /api/orgs"
    Then no aspect line of the request names the user details service or the JWT token provider

  @US-9 @AC-9.12 @P2
  Scenario: The aspect can be switched off
    Given the aspect logger at DEBUG
    And "app.logging.enabled" is false
    When Ana sends "GET /api/orgs"
    Then the response status is 200
    And the log of the request has no aspect line
```

**Notes for testers.**

- AC-9.3 uses phase 1's AC-5.x "already a member" case. If the code at the time of T17 answers a different 409 code for
  it, use the code the phase-1 tests assert, and report the difference to pm.
- In AC-9.4, stub a **repository** method that the real organization service calls (with `@MockitoBean`, after the token
  has been obtained), so the exception passes through the advised service. `UnexpectedErrorTest` mocks
  `OrganizationService` itself. A mocked service bean isn't a `@Service` class, so the aspect never sees it, and that
  test stays unchanged.
- In AC-9.9, Fay's second registration throws `EmailAlreadyExistsException`, whose message contains the email (
  `AuthServiceImpl:45`). That's why the aspect never logs exception messages.
- In the AC-9.6 outline, the anonymous login row proves that `userId` is absent for anonymous requests. Ana's user id is
  only known after login.

**AC count:** 64 acceptance criteria across 8 stories: US-1 8, US-2 4, US-3 8, US-4 9, US-5 10, US-6 6, US-7 6, US-9 13.
P0: 16, P1: 38, P2: 10. AC-9.10 is in (L2 approved). AC-9.13 was added on 2026-10-13 with E58 (placed after AC-9.10
because it is the same kind of check). The refactor and removal items add no ACs; they're proven by the unchanged
phase-1 suite (D15, D16).

## Changes to existing code (approved by the user on 2026-10-13)

Every file at HEAD `8345746` is the user's code, including the phase-1 work and its tests. Only the items below may
change it. Numbering continues from phase 1 (E1–E29, X1).

**User decision, 2026-10-13:** approved E30–E51, E53, E54, X2 and X3 (E42 with the D16 design). **E52 is not approved**:
it's dropped, together with its task steps. **EG1 is rejected** (D2 option B: no gateway route). For E37, the user
granted backend-swe a one-time F1 exception to change exactly `qa/QaSupport.java:87` and
`qa/QaAuthorizationMatrixTest.java:106`.

**User decision, 2026-10-13 (after review point A1):** **E43 is withdrawn.** **E55 (logging) is approved in principle**,
with its configuration in `application.yaml` (E56). Still open: E57 (build change, L1), the E29 fold-in into E56 (L2),
and confirmation of E55's final file list (L3). T17 starts only once these are answered.

**User decision, 2026-10-13 (later):**

- **L1 approved:** E57.
- **L2 approved:** E29 is folded into E56, and AC-9.10 stays. BUG-5 and FU-6 close with T17.
- **L3 approved as written.**
- **New one-time F1 exception for backend-swe** (`qa/QaSchemaTest.columnsMatchTheErd` pins the H2 schema). Batch A2
  stopped in T4 on it: E31 was reverted, and E30 is done. backend-swe may edit exactly these lines:
    - `QaSchemaTest` lines 44–45 in T4 (E31);
    - lines 52–53 and the line-122 foreign key in T9/T10 (E36/E37).

  The expected values must equal the as-built ERD that backend-swe updates in the same task. qa reviews these edits at
  re-verification.

**User decision, 2026-10-13 (review point A3):**

- **A3 passed.** Clean build verified by the main session: 897 tests (486 backend-swe, 411 qa), 0 failed; the 834
  existing tests unchanged.
- **BUG-5 and FU-6 are closed** by AC-9.10.
- **backend-swe's 6 T17 notes are accepted.**
- **E58 approved** (Batch B, task T18). RC-8 and RC-9 stay refactoring candidates.

**User decision, 2026-10-13 (Batch B):**

- **Batch B accepted** (T8, T9, T18 done; 901 tests green; contract 1.2.0 valid). Continue to Batch C after T10.
- **Extended F1 exception for T10.** T10 stopped because `qa/QaSchemaTest.enumValuesMatchTheErd` (lines 87–95) pins enum
  column values, and E37 makes `INVITATIONS.ROLE` an H2 ENUM. backend-swe may also edit:
    - `QaSchemaTest` line 53 → `"INVITATIONS.ROLE ENUM NULL"`;
    - `QaSchemaTest` lines 94–95 → add `INVITATIONS.ROLE=OWNER`, `=ADMIN`, `=MEMBER`, `=READER`;
    - the one `.role("MEMBER")` line in backend-swe's own `repository/InvitationRepositoryTest`.
- **ERD:** `invitations.role` becomes type `organization_role`.

### Refactor and removal items (Batch A1, D16): behaviour and contract unchanged

For every item in this table:

- The full `cd auth-service && ./gradlew build` is green before and after.
- Backend-swe and qa test counts are unchanged, except for the test lines the item names.
- No qa test is edited.
- The checked-in contracts don't change.

| ID  | Change                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | Files                                                                                                                                                                                                                                                                                                    | Why (evidence)                                                                                                                                                               | Risk                                                                                                                                                                                                                                                                                                                             | Depends on                                                                     | Task |
|-----|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------|------|
| E42 | **Accept invitation, clean design (D16).** Controller: one service call with the token, the caller's id or none, and the body or none; `@RequestBody(required = false)` without `@Valid`; 201 when the service reports a new account, else 200. Delete the `Validator` field and the hand validation, reflection and `BindingResult` code. Service: one `accept` method replaces `acceptInvitation` and `acceptInvitationAsNewUser`. For an anonymous caller it first validates the body (a missing body counts as `{}`) with the injected `jakarta.validation.Validator`, and throws a new typed exception (extends `ApiException`, code `VALIDATION_FAILED`) that carries the field errors. Advice: one added handler maps it to the same Problem as Spring's validation errors (same `detail`, `errors` names, messages and sort order). | `controller/AuthController.java` (lines 17, 20, 24–28, 43, 62–88), `service/InvitationService.java`, `service/impl/InvitationServiceImpl.java` (the two public methods and a new validation step), `exception/ApiExceptionHandler.java` (one handler), one new exception class and one new result record | Audit A1, A2: hand validation, reflection and business branching in a controller.                                                                                            | Medium (public operation). Proven by AC-1.2 row 3, AC-6.1 to AC-6.10, and qa's invitation suites, all unchanged.                                                                                                                                                                                                                 | —                                                                              | T1   |
| E43 | **Withdrawn (2026-10-13); not to be done.** Was: delete `aop/Logging.java`. The user chose to implement logging in that class instead (E55, D17). The file is still present after Batch A1.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | —                                                                                                                                                                                                                                                                                                        | —                                                                                                                                                                            | —                                                                                                                                                                                                                                                                                                                                | —                                                                              | —    |
| E44 | `JwtTokenProvider`: delete `getEmailFromJWT` (lines 86–92), `validateToken` (lines 94–105), and the `log` field and its two imports that only `validateToken` used. **Test lines that only cover the removed code, deleted with it:** `service/impl/JwtTokenProviderTest.java:75` and `:76` (in the AC-2.3 test) and `:104` (in the AC-3.1 test). Their other assertions, which call `getUserIdFromJWT`, stay and keep both ACs covered.                                                                                                                                                                                                                                                                                                                                                                                                    | `service/impl/JwtTokenProvider.java`, `service/impl/JwtTokenProviderTest.java` (3 lines)                                                                                                                                                                                                                 | Audit A5: no caller in `src/main`.                                                                                                                                           | Low                                                                                                                                                                                                                                                                                                                              | —                                                                              | T2   |
| E45 | `OpenApiConfiguration`: delete line 24 (`.type(SecurityScheme.Type.OAUTH2)`), so that the runtime `/v3/api-docs` declares the HTTP bearer scheme set on line 23.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `configuration/OpenApiConfiguration.java`                                                                                                                                                                                                                                                                | Audit A7: line 24 overrides line 23, and the result is invalid OpenAPI. Only the runtime docs change; the checked-in contract already says HTTP bearer. AC-3.7 is unchanged. | Low                                                                                                                                                                                                                                                                                                                              | —                                                                              | T2   |
| E46 | `AuthController`: delete the `@ApiResponses({...})` block (lines 47–50) and the two Lombok `@NonNull` type-use annotations (lines 51, 58), with their imports. `@Tag` and `@Operation` stay.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | `controller/AuthController.java`                                                                                                                                                                                                                                                                         | Audit A3: no effect, or a misleading effect on runtime docs only.                                                                                                            | None                                                                                                                                                                                                                                                                                                                             | E42 (same file; do after it)                                                   | T2   |
| E47 | Delete the unused Lombok `@Builder` (and its import) from the 8 DTO records listed in audit A8.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `dto/request/RegisterRequest.java`, `InviteMemberRequest.java`, `LoginRequest.java`, `CreateOrganizationRequest.java`, `AcceptInvitationRequest.java`; `dto/response/OrgClaim.java`, `UserResponse.java`, `OrganizationResponse.java`                                                                    | Audit A8: no caller of any of these builders once E42 removes `AuthController:77`.                                                                                           | Low                                                                                                                                                                                                                                                                                                                              | E42 (for `AcceptInvitationRequest`; without E42, that record keeps `@Builder`) | T2   |
| E48 | `AuthServiceImpl.login`: delete `SecurityContextHolder.getContext().setAuthentication(authentication)` (line 87) and its import. The `authenticationManager.authenticate(...)` call stays, because it performs the credential check; its result is no longer assigned.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `service/impl/AuthServiceImpl.java`                                                                                                                                                                                                                                                                      | Audit A9: a stateless chain on a public operation; nothing reads the context. It reverses phase-1 T10's "must not remove" rule, which only guarded against drive-by changes. | Low. Proven by AC-2.3 to AC-2.10.                                                                                                                                                                                                                                                                                                | —                                                                              | T2   |
| E49 | One email normaliser: a new final class with one static method in `util/` (trim, then `toLowerCase(Locale.ROOT)`), used in place of the three private `normalizeEmail` copies, which are deleted. Add a unit test for the method.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `service/impl/AuthServiceImpl.java`, `InvitationServiceImpl.java`, `OrganizationServiceImpl.java`; new `util/` class                                                                                                                                                                                     | Audit A10; the FU-5 item, re-proposed. FU-2 (NFC) will then change one place.                                                                                                | Low. Proven by AC-2.2, AC-2.5, AC-5.1, AC-5.5, AC-6.3.                                                                                                                                                                                                                                                                           | —                                                                              | T3   |
| E50 | Move `@EnableConfigurationProperties(OrganizationProperties.class)` from `OrganizationServiceImpl` (lines 37, 49) to `PropertiesConfiguration`, next to `JwtProperties`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `service/impl/OrganizationServiceImpl.java`, `configuration/PropertiesConfiguration.java`                                                                                                                                                                                                                | Audit A11: registering configuration is a configuration class's job.                                                                                                         | Low. Proven by AC-4.1 (seat limit 5).                                                                                                                                                                                                                                                                                            | —                                                                              | T3   |
| E51 | Organization lock through the repository: add a locked lookup by id (`@Lock(PESSIMISTIC_WRITE)`) to `OrganizationRepository`. Use it in `OrganizationServiceImpl.lockOrganization` (line 238) and `InvitationServiceImpl.checkFreeSeat` (line 153), and remove both services' `EntityManager` fields and imports.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `repository/OrganizationRepository.java`, `service/impl/OrganizationServiceImpl.java`, `service/impl/InvitationServiceImpl.java`                                                                                                                                                                         | Audit A12: persistence mechanics in services. The project service needs the same lock (P13).                                                                                 | **Medium.** Spring translates JPA lock exceptions on repository calls (for example `LockTimeoutException` becomes `CannotAcquireLockException`), and the advice already maps both to 409 `CONFLICT`. `exception/LockFailureTest` (AC-1.9) and qa's lock tests in `QaFixRoundTest` must pass **unchanged**. Stop condition in T3. | —                                                                              | T3   |
| E52 | **Not approved (2026-10-13); not to be done.** (Optional) One mapping function per response: `UserResponse` from a `User`, and `OrganizationResponse` from an `Organization` and a role. Put them in `service/impl`, so DTOs don't depend on entities, and use them at the four places in audit A13.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | `service/impl/AuthServiceImpl.java`, `InvitationServiceImpl.java`, `OrganizationServiceImpl.java`; one new `service/impl` class                                                                                                                                                                          | Audit A13: duplicated field-by-field mapping.                                                                                                                                | Low                                                                                                                                                                                                                                                                                                                              | E49, E51 (same files; do after them)                                           | T3   |
| E53 | Delete `configuration/CacheConfig.java`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `configuration/CacheConfig.java`                                                                                                                                                                                                                                                                         | Audit A14: after E32 and E33, nothing is `@Cacheable`.                                                                                                                       | Low                                                                                                                                                                                                                                                                                                                              | E32, E33                                                                       | T6   |
| E54 | `build.gradle`: delete lines 22–23 (`spring-boot-starter-cache`, `com.github.ben-manes.caffeine:caffeine`). Nothing else in the file changes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `auth-service/build.gradle`                                                                                                                                                                                                                                                                              | Audit A14: no user left after E53.                                                                                                                                           | Low–medium (stop condition in T6)                                                                                                                                                                                                                                                                                                | E53                                                                            | T6   |

### Project and data-model items

| ID  | Change                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | Files                                                                                                                                                                                                                                | Why                                                                                                                                                                                                                                                                                                 | Risk                                                                                                                                                                                                   | Depends on           | Task               |
|-----|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------|--------------------|
| E30 | `ProjectRole`: add an explicit rank (`ADMIN` 4, `MEMBER` 3, `TESTER` 2, `READER` 1) and `isAtLeast(ProjectRole)`. Constants and their order stay unchanged.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `enums/ProjectRole.java`                                                                                                                                                                                                             | D3; replaces the inverted `ordinal()` comparison.                                                                                                                                                                                                                                                   | Low                                                                                                                                                                                                    | —                    | T4                 |
| E31 | `Project`: add the Lombok annotations `ProjectMembership` uses (`@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`); add `archivedAt` (`LocalDateTime`, nullable); remove `secretToken`. `id`, `organization`, `name` and `createdAt` stay as they are.                                                                                                                                                                                                                                                                                                                                                                                                                                               | `model/Project.java`                                                                                                                                                                                                                 | The entity can't be used without accessors; D5, D8.                                                                                                                                                                                                                                                 | Low–medium (entity; the H2 schema changes at the next start)                                                                                                                                           | —                    | T4                 |
| E32 | `ProjectRoleService`: rewrite as the effective-role resolver (D3): organization membership, implicit admin, explicit role. Remove `@Cacheable`. The class name and package stay.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `service/impl/ProjectRoleService.java`                                                                                                                                                                                               | D3, D10.                                                                                                                                                                                                                                                                                            | Medium (authorization input)                                                                                                                                                                           | E30, E34             | T5                 |
| E33 | `ProjectGuard`: rewrite like `OrgGuard` (D10): `UserPrincipal`, a database lookup through E32, `isAtLeast`, fails closed. Remove the cached self-invoked method. The bean name `projectGuard` and `hasRole(UUID, Authentication, String)` stay; methods may be added.                                                                                                                                                                                                                                                                                                                                                                                                                                                | `security/ProjectGuard.java`                                                                                                                                                                                                         | `ClassCastException` and inverted ranks today.                                                                                                                                                                                                                                                      | High (authorization). Covered by every role matrix in US-2 to US-5 and by AC-7.1.                                                                                                                      | E30, E32             | T5                 |
| E34 | Repositories: add derived query methods only; no existing method changes. `ProjectRepository`: by organization, name lookups, by id and organization. `ProjectMembershipRepository`: by project, by user and organization, and the deletes and updates for E38 and E39. `OrgMembershipRepository`: only if a needed lookup is missing.                                                                                                                                                                                                                                                                                                                                                                               | `repository/ProjectRepository.java`, `repository/ProjectMembershipRepository.java`, `repository/OrgMembershipRepository.java`                                                                                                        | Needed by the resolver and the rules. Additive.                                                                                                                                                                                                                                                     | Low                                                                                                                                                                                                    | —                    | T4, T5, T11–T15    |
| E35 | `ErrorCode`: add `PROJECT_NAME_TAKEN` (409), `PROJECT_ARCHIVED` (409), `NOT_ORG_MEMBER` (422), `PROJECT_ROLE_NOT_ALLOWED` (422). No existing constant changes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `exception/ErrorCode.java`                                                                                                                                                                                                           | D14.                                                                                                                                                                                                                                                                                                | Low                                                                                                                                                                                                    | —                    | T11, T12, T13      |
| E36 | Invitations are organization-only (D7): remove the `project` field from `Invitation`, and set `nullable = false` on its `org_id` join column. In `InvitationServiceImpl.accept`, remove the project branch, and the `ProjectMembershipRepository` field and imports that only it used.                                                                                                                                                                                                                                                                                                                                                                                                                               | `model/Invitation.java`, `service/impl/InvitationServiceImpl.java`                                                                                                                                                                   | Audit A15: a dead, unsafe branch (no seat check, null organization); also the ERD's "exactly one of" defect.                                                                                                                                                                                        | Low–medium. Every phase-1 invitation AC must still pass. `qa/QaSchemaTest` pins the schema: lines 52–53 and the line-122 FK change under the F1 exception (2026-10-13).                                | —                    | T9                 |
| E37 | Typed invitation role (D7): `Invitation.role` becomes `OrganizationRole` with `@Enumerated(EnumType.STRING)`. Its three users in `OrganizationServiceImpl` (create, list, revoke) and one in `InvitationServiceImpl` (accept) drop `name()` and `valueOf`. **Test fallout:** `controller/InvitationsTest.java:458` and `controller/InvitationAcceptanceTest.java:553` change `.role(role.name())` to `.role(role)` (a G3 exception for backend-swe). `qa/QaSupport.java:87` (the same change) and `qa/QaAuthorizationMatrixTest.java:106` (`isEqualTo(role.name())` becomes `isEqualTo(role)`) are qa's: either qa changes them in lockstep, or the user grants a one-time F1 exception for exactly those two lines. | `model/Invitation.java`, `service/impl/OrganizationServiceImpl.java`, `service/impl/InvitationServiceImpl.java`, the four test lines                                                                                                 | The ERD's free-text role defect.                                                                                                                                                                                                                                                                    | Medium (touches accepted invitation code and qa tests). `qa/QaSchemaTest` lines 52–53 change under the F1 exception (2026-10-13).                                                                      | E36                  | T10                |
| E38 | `OrganizationServiceImpl.removeMember`: in the same transaction, delete the removed or leaving user's memberships in that organization's projects (D6). Nothing else in the method changes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `service/impl/OrganizationServiceImpl.java`                                                                                                                                                                                          | No orphaned project access after a removal (US-6).                                                                                                                                                                                                                                                  | Low–medium. AC-8.1 to AC-8.6 must still pass.                                                                                                                                                          | E34                  | T15                |
| E39 | `OrganizationServiceImpl.changeMemberRole`: when the new organization role is `READER`, lower that user's project roles above `READER` in that organization to `READER`, in the same transaction (D6). Nothing else in the method changes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | `service/impl/OrganizationServiceImpl.java`                                                                                                                                                                                          | The project role cap holds after a demotion (AC-6.3).                                                                                                                                                                                                                                               | Low. AC-7.1 to AC-7.8 must still pass.                                                                                                                                                                 | E34                  | T15                |
| E40 | Contract: add every phase-2 operation, the access check (C5), the new codes and the D6 error orders. Add the FU-7 wording for `OrganizationRole` and the same wording for `ProjectRole`. Bump `info.version` to `1.2.0`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `docs/api/auth-service.openapi.yaml`                                                                                                                                                                                                 | Contract-first for the frontend and consumers (C1, C5).                                                                                                                                                                                                                                             | Low (docs)                                                                                                                                                                                             | —                    | T8, T16            |
| E41 | README: add a "Projects" section (roles, effective role, archive) and an "Access check for other services" section (how to call it, consumer rules, D9). Existing sections stay.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `auth-service/README.md`                                                                                                                                                                                                             | Consumers need it in one place.                                                                                                                                                                                                                                                                     | Low (docs)                                                                                                                                                                                             | E40                  | T16                |
| X2  | (pm, docs only, not the user's code) FU-9: in `docs/specs/api-gateway.md`, mark "Known issues in other services" items 2–6 as fixed. Add to its D2 a note that `/internal/**` is never routed and `/api/projects/**` is reserved for project-scoped content services (D2 option B).                                                                                                                                                                                                                                                                                                                                                                                                                                  | `docs/specs/api-gateway.md`                                                                                                                                                                                                          | D13.                                                                                                                                                                                                                                                                                                | Low (docs)                                                                                                                                                                                             | Approval of D2       | pm task P1         |
| X3  | (docs) In `docs/api/api-gateway.openapi.yaml`, next to the existing "`/api/projects/**` is not routed" note, add one sentence: `/internal/**` is never routed (auth-service's access check is service-to-service). Patch version bump.                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | `docs/api/api-gateway.openapi.yaml`                                                                                                                                                                                                  | Gateway contract readers see the rule.                                                                                                                                                                                                                                                              | Low (docs)                                                                                                                                                                                             | Approval of D2       | T16                |
| E55 | **Logging (D17), approved in principle 2026-10-13.** Implement the aspect in `aop/Logging.java` (`@Component`; pointcut, order, levels and "never log arguments, return values or messages" per D17 items 1 and 4). Add the request-ID filter (before the security chain) and the user-ID filter (after it), both clearing the MDC in `finally` (D17 item 2). Add a typed `app.logging` properties record (D17 item 7), registered in `PropertiesConfiguration`. Make `Problems.requestId(HttpServletRequest)` `public`, changing nothing else in `Problems`.                                                                                                                                                        | modify `aop/Logging.java`, `configuration/PropertiesConfiguration.java` (one registration), `exception/Problems.java` (one modifier); create the filter class or classes and the properties record in `configuration/`; create tests | Audit A6; the user's 2026-10-13 instruction; phase-1 NFR "Observability" (MDC) was never done.                                                                                                                                                                                                      | **Medium.** Cross-cutting proxies on every service, and new servlet filters. Every existing test must pass unchanged, including the log-format assertions named in D17 item 3. Stop conditions in T17. | E56; E57 if approved | T17                |
| E56 | **`application.yaml` logging keys (D17), approved in principle with E55.** Add `app.logging.enabled: true` and `app.logging.slow-call-threshold: 500ms`, plus the correlation pattern (`logging.pattern.correlation`, or a full `logging.pattern.console` that keeps the line start; D17 item 3). **Only if L2 is approved:** also `logging.level.org.hibernate.orm.jdbc.error: ERROR` (E29, FU-6). Nothing else in the file changes.                                                                                                                                                                                                                                                                                | `auth-service/src/main/resources/application.yaml`                                                                                                                                                                                   | D17 items 3, 5, 7.                                                                                                                                                                                                                                                                                  | Low–medium: the pattern change affects every log line, and 4 test files parse the line start.                                                                                                          | —                    | T17                |
| E57 | **Approved 2026-10-13 (L1).** `build.gradle`: add `implementation 'org.springframework.boot:spring-boot-starter-aspectj'` (verified in the Boot 4.1.0 BOM; no version, managed by the plugin). Nothing else in the file changes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `auth-service/build.gradle`                                                                                                                                                                                                          | D17 item 6: declare a dependency the code uses directly, instead of relying on JPA's transitive `spring-aspects`.                                                                                                                                                                                   | Low                                                                                                                                                                                                    | —                    | T17                |
| E58 | **Approved 2026-10-13.** `application.yaml`: add `spring.mvc.log-resolved-exception: false`. Nothing else in the file changes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | `auth-service/src/main/resources/application.yaml`                                                                                                                                                                                   | Under `bootRun`, devtools turns this setting on, and `ExceptionHandlerExceptionResolver` then logs WARN "Resolved [...EmailAlreadyExistsException: Email already exists: <email>]", leaking the email in local development (D17 item 4). RC-8 and RC-9 remain the root causes and stay unscheduled. | Low                                                                                                                                                                                                    | —                    | T18                |
| EG1 | **Rejected (2026-10-13); not to be done.** Only if the user chooses D2 option A (not recommended). A gateway route `projects` for `/api/projects/**` in `api-gateway/src/main/resources/application.yaml`. The unrouted example must then change in `RoutingTest`, `GatewayErrorResponseTest` and `JwtAuthenticationTest` (backend-swe) and in `QaUnknownPathsTest`, `QaAccessLogTest`, `QaCorsAdversarialTest` and `QaLeakageTest` (qa), and the gateway spec's D2 table changes too.                                                                                                                                                                                                                               | as listed                                                                                                                                                                                                                            | —                                                                                                                                                                                                                                                                                                   | Medium (cross-service, qa tests)                                                                                                                                                                       | —                    | (would add a task) |

**Will not be touched in phase 2** (any change needs a new approval):

- `AuthServiceApplication.java`.
- In `configuration/`: `SecurityConfig`, `JwtAuthenticationFilter`, `JwtProperties`, `OrganizationProperties`.
  `PropertiesConfiguration` changes only through E50 and E55 (one registration), `OpenApiConfiguration` only through
  E45, and `CacheConfig` only through E53.
- `aop/Logging.java` changes only through E55.
- `enums/` other than `ProjectRole`.
- `security/` other than `ProjectGuard`: `OrgGuard`, `UserPrincipal`, the Problem entry point and handler.
- `service/AuthService`, `service/OrganizationService`, `CustomUserDetailsService`. `JwtTokenProvider` changes only
  through E44.
- `util/TokenGenerator`.
- Entities other than `Project` and `Invitation`: `AuditLog`, `OrgMembership`, `Organization`, `ProjectMembership`,
  `User`.
- `AuditLogRepository`, `UserRepository`, `InvitationRepository`. `OrganizationRepository` changes only through E51.
- `OrganizationController` (it's compliant, audit A4).
- Phase-1 DTOs other than the `@Builder` removal (E47).
- Phase-1 exception classes and `ApiException`. `Problems` changes only through E55 (one modifier).
  `ApiExceptionHandler` changes only through E42's one added handler.
- `application-dev.yaml`, `settings.gradle`, the Gradle wrapper, `gradlew*`, `.gitattributes`, every `.gitignore`.
  `application.yaml` changes only through E56 and E58. `build.gradle` changes only through E54 and, if approved, E57.
- The existing log statements in `OrgGuard`, `OrganizationServiceImpl`, `InvitationServiceImpl` and
  `ApiExceptionHandler` (messages and levels), E55 included.
- Every existing test file, except the lines named in E37 and E44.
- Everything in `api-gateway/`, `scenario-service/` and `report-service/` (unless EG1 is chosen).

### Refactoring candidates for a later E-list (not scheduled)

Reported by backend-swe at review point A1 (RC-1 to RC-7), and by pm while specifying E55 (RC-8 to RC-10). **None of
them is approved or scheduled.** They're recorded so a later audit round can propose E-items. G4 still applies: no task
fixes them in passing.

| #     | Where                                                                                                                                                                  | Finding                                                                                                                                                                                                                                                                                                                        | Source          |
|-------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------|
| RC-1  | `service/impl/AuthServiceImpl.java` ~L57–65, `isUniqueViolation` ~L95–104                                                                                              | The service inspects the kind of Hibernate's `ConstraintViolationException`: persistence-provider details in a service (PL).                                                                                                                                                                                                   | backend-swe, A1 |
| RC-2  | `OrganizationServiceImpl:94`, `:234`, `:241`, `:247` (`AccessDeniedException`); `AuthServiceImpl:76`, `:86`; `InvitationServiceImpl:111` (`UsernameNotFoundException`) | Services throw Spring Security exceptions instead of typed `ApiException`s (P6, PL). It also means E55 logs these outcomes at DEBUG, not at INFO like other business errors. Confirmed at A3: `AccessDeniedException`, `BadCredentialsException` and `EmailAlreadyExistsException` outcomes are logged at DEBUG by the aspect. | backend-swe, A1 |
| RC-3  | `AuthServiceImpl`, `InvitationServiceImpl`, `OrganizationServiceImpl` (audit A13)                                                                                      | Duplicated `UserResponse` and `OrganizationResponse` mapping. E52 wasn't approved; this keeps the finding open.                                                                                                                                                                                                                | backend-swe, A1 |
| RC-4  | `service/impl/JwtTokenProvider.java`, `service/impl/CustomUserDetailsService.java`                                                                                     | Security infrastructure placed in `service/impl`. E55 has to exclude `CustomUserDetailsService` from the service pointcut for this reason.                                                                                                                                                                                     | backend-swe, A1 |
| RC-5  | `dto/response/OrganizationResponse.java:11`                                                                                                                            | A no-op Lombok `@NonNull`.                                                                                                                                                                                                                                                                                                     | backend-swe, A1 |
| RC-6  | `exception/ApiExceptionHandler.handleLockFailure` Javadoc; `@DisplayName` texts in `exception/LockFailureTest`                                                         | Stale text about `EntityManager.find`, which E51 replaced with a repository lock.                                                                                                                                                                                                                                              | backend-swe, A1 |
| RC-7  | `ApiExceptionHandler.handleLockFailure` (`LockTimeoutException`, `PessimisticLockException`)                                                                           | The JPA lock exception types are no longer reached in production after E51; only Spring's translated types are.                                                                                                                                                                                                                | backend-swe, A1 |
| RC-8  | `exception/EmailAlreadyExistsException.java`; `AuthServiceImpl:45`, `:62`                                                                                              | Extends `RuntimeException` rather than `ApiException`, and its message contains the email. The advice doesn't log it, and E55 never logs messages, but it's a latent leak.                                                                                                                                                     | pm, D17         |
| RC-9  | `service/impl/CustomUserDetailsService.java:26`                                                                                                                        | `UsernameNotFoundException("User not found with email: " + email)`: an email in an exception message (same risk as RC-8).                                                                                                                                                                                                      | pm, D17         |
| RC-10 | `configuration/JwtAuthenticationFilter`                                                                                                                                | Registered twice (Known issue 9). Recorded again because E55 adds filters next to it, so the ordering must be read with this in mind.                                                                                                                                                                                          | pm, D17         |

## Engineering guardrails (binding for every task)

Phase 1's guardrails carry over, adapted below. A task isn't done while any rule is broken. If a rule blocks you, stop
and ask in your handoff; don't work around it.

### Scope control

- **G1. Allowed files only.** A task creates, modifies, moves or deletes only the files on its **Allowed files** line.
  New files may only go under:
    - `auth-service/src/main/java/com/example/authservice/<existing package>/`, with no new main packages;
    - `auth-service/src/test/java/com/example/authservice/**`, mirroring main, with `support/` for shared helpers;
    - `docs/api/auth-service.openapi.yaml` and `auth-service/README.md` (E40, E41).

  `auth-service/src/test/resources/` is not allowed.
- **G2. Existing code changes only through approved E-items.** "Existing" means every file at HEAD `8345746`. A task may
  change one only as the E-items it names describe, and only once they're approved. If an approved item turns out to
  need more, stop and ask.
- **G3. Files from earlier phase-2 tasks** may change only if the task's Allowed files line names them, and tests may
  only be strengthened. The only changes to phase-1 tests are these:
    - E37: two backend-swe lines, plus two qa lines if the user grants that exception;
    - E44: three deleted lines that test only removed code.

  The phase-1 rule stays: a test that starts and closes its own application context uses its own database URL.
- **G4. No drive-by changes.** No refactors, reformatting, import reshuffles, renames, comment edits or "while I'm here"
  fixes beyond the E-items. Report other defects in your handoff, as candidates for the next audit.
- **G5. No version or dependency changes** except E54 (D12) and E57 (D17, approved 2026-10-13).
- **G6. Tests stay honest.** No `@Disabled`, no skipping assumptions, no weakened or deleted tests, except the E44
  lines. `cd auth-service && ./gradlew build` is green at the end of every task. Report total, failed and skipped
  counts, separately for backend-swe and qa tests.
- **G7. No git operations.** No `git add`, `commit`, `stash`, `restore`, `checkout`, `reset`, `mv` or `rm`; the user
  commits. Each handoff includes `git status --short`, `git diff --stat`, and every new or deleted file with a one-line
  summary.
- **G8. Self-review the diff** before handing off. Check for:
    - files outside the Allowed files line;
    - unrelated whitespace;
    - debug output;
    - commented-out code;
    - unused imports in touched files;
    - secrets other than the test-only secret in test code.
- **G9. Service boundaries.** auth-service never reads another service's database, code or configuration, and never
  calls the gateway. No shared library.
- **G10. Gateway untouched.** No file under `api-gateway/` changes in phase 2 unless EG1 is approved.

### PL. Layering: controller, service and repository responsibilities (binding for every task, new and touched code)

Mirrors backend-swe's working rule 10.

| Layer                                  | Owns                                                                                                                                                                                             | Never                                                                                                                                                                                                                                                                                                    |
|----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Controller**                         | HTTP only: the mapping; `@Valid` on request bodies; `@PreAuthorize`; reading the principal's id; **exactly one service call per handler**; turning the result into a status, `Location` and body | Repositories, entities, `EntityManager`; hand-written validation (`Validator`, `BindingResult`, `SpringValidatorAdapter`); reflection (`ReflectionUtils`, building a `MethodParameter`); throwing framework exceptions by hand; business branching (choosing a use case by caller state, checking rules) |
| **Service**                            | Use cases: business rules, transactions (P2), calls to repositories, mapping entities to response DTOs inside the transaction (P3), typed exceptions (P6)                                        | HTTP types (`ResponseEntity`, `HttpServletRequest`, status codes); `SecurityContextHolder`; persistence mechanics (`EntityManager`, lock modes, native SQL); `@Enable…`/configuration annotations                                                                                                        |
| **Repository**                         | Data access only: derived and annotated queries, locks (`@Lock`), counts                                                                                                                         | Business rules; mapping to DTOs                                                                                                                                                                                                                                                                          |
| **Advice** (`ApiExceptionHandler`)     | The only place that turns exceptions into Problems                                                                                                                                               | Business decisions                                                                                                                                                                                                                                                                                       |
| **Guard** (`OrgGuard`, `ProjectGuard`) | Authorization questions only ("is the caller at least R here?"), from the database                                                                                                               | Business rules (ceiling, cap, seats, archived)                                                                                                                                                                                                                                                           |
| **Configuration**                      | Beans, property registration, the security chain                                                                                                                                                 | Business logic                                                                                                                                                                                                                                                                                           |

Rules:

- **New project code follows PL from the first commit** (T7, T11–T15). The phase-2 controllers are written to the clean
  `OrganizationController` and post-E42 `AuthController` pattern.
- An existing violation that is outside the E-items is reported in the handoff, not fixed (G4).
- A choice between two status codes based on the service's result (for example 201 versus 200 after accept) is HTTP
  mapping, not business branching.

### Package layout (phase-2 additions)

| Package (main and test)       | New contents in phase 2                                                                                                                          |
|-------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `controller`                  | Project endpoints under `/api/orgs/{orgId}/projects`, and the `/internal/projects/{projectId}/access` endpoint (it may be a separate controller) |
| `service`, `service/impl`     | A project service (interface and implementation) with the lifecycle and membership rules and transactions; the accept result record (E42)        |
| `repository`                  | Additive derived queries (E34), the organization lock (E51)                                                                                      |
| `dto/request`, `dto/response` | Records for every new request and response body, without `@Builder` unless something calls it                                                    |
| `exception`                   | One typed exception per new code (E35), plus E42's validation exception, each extending `ApiException`                                           |
| `security`                    | `ProjectGuard` (rewritten)                                                                                                                       |
| `util`                        | The email normaliser (E49)                                                                                                                       |
| `aop`                         | `Logging`, implemented (E55)                                                                                                                     |
| `configuration`               | The request-ID and user-ID MDC filters and the `app.logging` properties record (E55)                                                             |
| `support` (test only)         | `ProjectFixture` (the Projects fixture)                                                                                                          |

### Patterns and practices

Phase 1's P1–P12 apply unchanged: layering (now detailed by PL), transactions, DTOs rather than entities, validation,
errors, configuration, injection, Java hygiene, HTTP, tests, and "don't guess framework APIs". Phase-2 additions:

- **P5 (projects).** Every project endpoint has an explicit `@PreAuthorize` using `@projectGuard` (or `@orgGuard` for
  create and list).
    - Compare project roles only with `ProjectRole.isAtLeast`, never with `ordinal()`, `compareTo` or string order.
    - The guard checks that the project belongs to the path's organization.
    - Never authorize on `X-User-Id` or the `orgs` claim.
    - No role caching.
- **P13. One lock for organization-scoped writes.** Project creation and rename (name uniqueness), and every project
  membership write (the organization-membership check), take the same organization row lock that phase-1 membership
  writes take, through the repository (E51), before checking. So a project add can't race an organization removal (D6),
  and two creations can't both take one name (AC-1.7). If E51 isn't approved, new code still takes the lock through a
  repository method, and the two existing call sites stay as they are.
- **P14. Effective role in one place.** Only the E32 resolver computes effective roles. The guard, the services,
  `myRole` in responses and the access check all use it.
- **P15. Test first for changed behaviour.** For E33, E36, E37, E38 and E39: write the test, run it against the
  unchanged code, report how it failed, then change the code. A test that passes before the change proves nothing;
  strengthen it or explain why. Refactor and removal items (D16) are behaviour-neutral, so the existing suite is their
  proof.
- **P16. Refactor discipline.** For each refactor or removal item:
    - Run the full build first and record the counts.
    - Make only that item's change.
    - Run the full build again: the counts must be the same, minus any test lines the item names.
    - One item at a time. Report each item's before and after counts separately.

### Fix rounds after QA

F1–F4 from phase 1 apply unchanged. qa's tests under `auth-service/src/test/java/com/example/authservice/qa/` are
read-only for backend-swe, with these one-time exceptions (granted 2026-10-13):

- the two E37 lines (`QaSupport.java:87`, `QaAuthorizationMatrixTest.java:106`);
- `QaSchemaTest` lines 44–45 (T4, E31);
- `QaSchemaTest` lines 52–53 and the line-122 FK (T9/T10, E36/E37). The values must equal the as-built ERD updated in
  the same task, and qa reviews them at re-verification.

## Tasks for backend-swe

Do the tasks in order, batch by batch. **Stop at each review point** and hand off; the user reviews the diff before the
next batch. A task may only start once its E-items are approved. If one isn't, skip that item (or the whole task, if
nothing in it is approved) and say so. Run `cd auth-service && ./gradlew build` at the end of every task. Every task
follows PL.

### Batch A1: clean-up first (review point A1)

These tasks change structure only. They add no endpoint and change no behaviour or contract.

**T1. Accept invitation, clean design.** E-items: E42.

- Change: per E42 and D16.
- Proof:
    - The full suite before and after: same counts.
    - All of AC-1.2 row 3, AC-6.1 to AC-6.10, `InvitationAcceptanceTest` and qa's `QaInvitationSecurityTest`,
      `QaInputEdgeCasesTest` and `QaFixRoundTest` pass unchanged.
- New tests, for the new units only:
    - the service rejects an anonymous acceptance with no body, with exactly `fullName` and `password` errors, before
      looking up the token (an unknown token plus no body is 400, not 404, as phase-1 Q6 orders);
    - a logged-in caller's invalid body is ignored;
    - the advice maps the new exception to the same Problem shape as a `MethodArgumentNotValidException` with the same
      errors.
- Done when: the above is green, and `AuthController` contains no `Validator`, `BindingResult`,
  `BeanPropertyBindingResult`, `SpringValidatorAdapter`, `ReflectionUtils`, `MethodParameter` or
  `MethodArgumentNotValidException`.
- **Stop condition:** if any existing test (backend-swe or qa) needs a change to pass, or the `errors` entries differ in
  any way (names, messages, order), stop and report the test and the difference. Don't adapt the test.
- **Allowed files:**
    - modify `controller/AuthController.java` (the accept method, the `Validator` field, imports),
      `service/InvitationService.java`, `service/impl/InvitationServiceImpl.java` (the two public methods, a validation
      step at the start, the `Validator` injection), `exception/ApiExceptionHandler.java` (one added handler, reusing
      the existing field-error sorting);
    - create one exception class in `exception/` and one result record in `service/` or `dto/response/`;
    - create tests.
- **Must not:** change the accept path, statuses, response bodies or the Q6 check order; validate a logged-in caller's
  body; change `AcceptInvitationRequest`'s constraints or messages; touch `register` or `login`.

**T2. Removals.** E-items: E44, E45, E46, E47, E48 (each only if approved; P16, one at a time). E43 was withdrawn on
2026-10-13 (replaced by E55, T17).

- Proof per item: the full suite before and after, with the same counts. For E44 the count stays the same (no test
  method is deleted, only 3 assertion lines); report the lines.
- Done when: every approved item is done and the build is green. In your handoff, give grep evidence that nothing
  references the deleted code.
- **Stop condition:** if a removal breaks compilation or any test outside the lines its item names, stop and report.
  Don't restore the code with a workaround.
- **Allowed files:** exactly the files named in E43–E48.
- **Must not:** remove anything not listed. Report further candidates instead.

**T3. Service-layer tidy-up.** E-items: E49, E50, E51 (P16, one at a time, in this order). E52 is not approved: don't do
it.

- Proof per item: the full suite before and after, with the same counts. For E49, a new unit test of the normaliser (
  ASCII upper case, surrounding spaces, `Locale.ROOT` for `I`).
- **E51 stop condition:** if `exception/LockFailureTest` or any qa lock test (`QaFixRoundTest`, `QaConcurrencyTest`)
  fails or needs a change, stop, revert E51's change by hand (no git), and report the failing test and the exception
  type observed. The other items proceed.
- **Allowed files:** exactly the files named in E49–E51, plus their new classes and tests.
- **Must not:** change transaction boundaries, lock order, check order or log messages.

**Review point A1.** Hand off to the main session. The user reviews the clean-up before any project code is written.

**Review point A1: passed (2026-10-13).**

- **Result:** 731 tests, all green: 320 backend-swe and 411 qa. Verified by the main session. This is the baseline count
  for Batch A2.
- **Deviations accepted by the user** (backend-swe reported 4):
    1. The shared `INVALID_FIELD_ORDER` comparator (E42).
    2. An extra unused `Authentication` import removed in E48.
    3. Anonymous body validation runs inside the `@Transactional` `accept` (E42).
    4. `orElseThrow()` in an unreachable `checkFreeSeat` path (E51).
- **E43:** not done in T2, and withdrawn afterwards. `aop/Logging.java` stays for E55.
- **Further findings:** recorded as refactoring candidates RC-1 to RC-7 (not scheduled).

### Batch A2: project authorization core and the access check (review point A2)

**T4. Project role ranks and a usable `Project` entity.** E-items: E30, E31, E34 (`ProjectRepository` only, as needed).

- Change: per E30 and E31.
- ACs: none end to end. Unit tests: `ProjectRole.isAtLeast` for every pair (16 cases); a repository test that a
  `Project` with `archivedAt` round-trips.
- Done when: those tests pass, every phase-1 test passes, and the build is green.
- **Stop condition:** if any existing test or main class reads `secretToken` or relies on `Project` having no other
  constructor, stop and report. Any schema-pinning test other than `qa/QaSchemaTest` lines 44–45 (covered by the F1
  exception) is also a stop.
- **Allowed files:** modify `enums/ProjectRole.java`, `model/Project.java`, `repository/ProjectRepository.java` (add
  methods only); create tests in `.../enums/` and `.../repository/`. Under the one-time F1 exception (2026-10-13):
  `qa/QaSchemaTest.java` lines 44–45 only, with values equal to the as-built ERD, and `docs/erd/cucumber.dbml` (the
  `projects` table, as-built) in the same task.
- **Must not:** reorder or rename `ProjectRole` constants; change `Project`'s table name, `id` generation,
  `organization` mapping, or its `name` and `createdAt` columns; touch `ProjectMembership`.

**T5. Effective role and `ProjectGuard`.** E-items: E32, E33, E34 (`ProjectMembershipRepository`, and
`OrgMembershipRepository` if needed).

- Test first (P15). Each of these tests fails at HEAD:
    - calling `projectGuard.hasRole(...)` with a `UserPrincipal` authentication throws `ClassCastException`;
    - a `READER` passes an `ADMIN` check (wrongly);
    - a removal isn't visible to the next call, because of the cache.
- Change: per E32, E33, D3 and D10. Create `support/ProjectFixture` (the Projects fixture).
- ACs: none end to end yet. Unit tests:
    - the effective-role rule for every organization role (`OWNER`, `ADMIN`, `MEMBER`, `READER`, none) × every explicit
      role or none (25 cases), including the `READER` cap;
    - the guard with no authentication, a principal that isn't a `UserPrincipal`, an unknown role string, an unknown
      project, and a project of another organization.
- Done when: those tests pass, the three test-first tests failed before the change as described, and the build is green.
- **Stop condition:** if Spring Security 7 method security can't call the guard with the path variables the endpoints
  need, stop and report what you verified (P12).
- **Allowed files:** modify `service/impl/ProjectRoleService.java`, `security/ProjectGuard.java`, and
  `repository/ProjectMembershipRepository.java` and `repository/OrgMembershipRepository.java` (add methods only); create
  `support/ProjectFixture.java` and tests in `.../security/` and `.../service/impl/`.
- **Must not:** touch `OrgGuard`; cache roles; read the JWT `orgs` claim or `X-User-Id`; throw from the guard for bad
  input (return false). `CacheConfig` waits for T6.

**T6. Remove the unused cache.** E-items: E53, E54 (each only if approved; P16).

- Done when: the build is green with the same test count as after T5, and
  `./gradlew dependencies --configuration runtimeClasspath` no longer lists Caffeine or the cache starter. grep shows no
  `@Cacheable`, `@EnableCaching` or `CacheManager` in `src`.
- **Stop condition:** if compilation or a test fails because one of the two dependencies supplied a class used
  elsewhere, stop and report the class and file.
- **Allowed files:** delete `configuration/CacheConfig.java`; modify `auth-service/build.gradle` (delete exactly lines
  22–23).
- **Must not:** add or change any other dependency line.

**T7. Access check for other services.** E-items: none beyond T5's.

- Change: `GET /internal/projects/{projectId}/access` per D9 and C5, with `@PreAuthorize` through `projectGuard`, plus a
  response record. **The controller follows PL:** one service call, no repositories, no branching.
- ACs: AC-7.1, AC-7.2, AC-7.3, AC-7.5. AC-7.4 is "completed in T12" (it needs archive).
- Done when: those tests pass and the build is green.
- **Stop condition:** if the path needs a `SecurityConfig` or `OpenEndpoints` change to be authenticated and reachable,
  stop and report. It shouldn't (`anyRequest().authenticated()`).
- **Allowed files:** create a controller in `controller/`, a service (interface in `service/`, implementation in
  `service/impl/`) and a record in `dto/response/`; create tests in `.../controller/`.
- **Must not:** make the path public; return any field beyond C5's; add a gateway route.

**Review point A2.** The user reviews T4–T7 (role ranks, entity, resolver, guard, cache removal, access check) before
Batch A3.

### Batch A3: logging (review point A3)

T17 is numbered after T16 so that the existing task IDs stay stable. It runs after review point A2 and before Batch B.

**T17. Logging aspect and request correlation.** E-items: E55, E56; E57 only if approved (L1); the E29 key in E56 only
if approved (L2).

- **Precondition:** met. L1, L2 and L3 were approved on 2026-10-13, so E57 and the E29 key apply. Close BUG-5 and FU-6
  in your handoff, with AC-9.10 as the evidence.
- **Before you start:** run the full build and record the counts (expected 731 plus A2's new tests).
- **Test first** (P15) for AC-9.1, AC-9.6 and AC-9.9 (and AC-9.10 if L2 is approved). Run them against the unchanged
  code and report how each failed. AC-9.1 and AC-9.6 must fail, because there's no aspect and no MDC. AC-9.9 may pass
  before the change; if it does, say so. It's a regression guard for the new aspect, not a fix. AC-9.10 should fail on
  BUG-5. If it passes, report that the E29 logger name needs re-checking.
- **Change:** per E55, E56 (and E57) and D17.
- **ACs:** AC-9.1 to AC-9.12 (AC-9.10 only with L2), each tagged `@Tag("AC-9.x")`.
- **Verify and report** (P12, don't guess):
    - the Boot 4.1 order of the Spring Security filter chain, and the order you gave both MDC filters;
    - that `logging.pattern.correlation` exists in Boot 4.1 and where it renders (or which full pattern you used
      instead);
    - that the aspect wraps `@Transactional` (it sees a commit-time exception);
    - whether AspectJ auto-proxying was active without `@EnableAspectJAutoProxy`. Don't add that annotation unless
      verification shows it's needed, and then report it.
- **Done when:**
    - every US-9 AC passes;
    - every existing test (backend-swe and qa) passes **unchanged**, in particular `UnexpectedErrorTest` (exactly one
      ERROR), `LockFailureTest` and `QaFixRoundTest` (one lock WARN, no stack frames, no bound values), `QaLeakageTest`,
      `QaTokenAttackTest`, and the token-leak assertions in `InvitationsTest`, `InvitationAcceptanceTest`,
      `QaInvitationSecurityTest`, `BearerAuthenticationTest` and `JwtSecretStartupTest`;
    - the build is green;
    - the handoff shows 3 sample log lines with the default levels (a business error, a slow call and an unexpected
      error), with real request and user IDs;
    - if E57 was applied, `./gradlew dependencies --configuration runtimeClasspath` shows `spring-boot-starter-aspectj`
      and no version change elsewhere.
- **Stop conditions:**
    - an existing test fails or would need a change (for example because of the log line format, or an extra WARN or
      ERROR line). Stop and report the test and the line;
    - the MDC user ID can't be set without changing `JwtAuthenticationFilter` or `SecurityConfig`. Stop and report what
      you verified;
    - the aspect isn't applied without a build change and E57 isn't approved. Stop and report.
- **Allowed files:**
    - modify `aop/Logging.java`, `configuration/PropertiesConfiguration.java` (one registration),
      `exception/Problems.java` (`requestId` becomes `public`, nothing else), `src/main/resources/application.yaml` (E56
      keys only), and, if E57 is approved, `auth-service/build.gradle` (one added line);
    - create the MDC filter class or classes and the `app.logging` properties record in `configuration/`;
    - create tests (under `aop/`, `configuration/`, or a new test class in `controller/` for the end-to-end ACs). Tests
      with their own properties use their own database URL (G3).
- **Must not:**
    - log arguments, return values, exception messages or stack traces from the aspect;
    - advise controllers, guards, filters, `CustomUserDetailsService` or `JwtTokenProvider`;
    - change any existing log statement, `JwtAuthenticationFilter`, `SecurityConfig`, `application-dev.yaml`, or the
      Problem body or headers (no `X-Request-Id` response header);
    - add any other dependency, or Logback XML files (`src/main/resources/logback*.xml`).

**Review point A3.** The user reviews the logging change, including the sample lines, before Batch B. **Passed
2026-10-13** (897 tests, 0 failed; see "User decision, 2026-10-13 (review point A3)").

### Batch B: contract and invitation cleanup (review point B)

**T8. Contract 1.2.0.** E-items: E40.

- Change:
    - Add every phase-2 operation from US-1 to US-7, with external paths, request and response schemas, status codes and
      `code` values.
    - Mark `/internal/projects/{projectId}/access` as service-to-service (not routed by the gateway), with C5's consumer
      rules.
    - Add the D14 codes, and the D5 and D6 rules and error orders in operation descriptions.
    - Add the FU-7 wording for `OrganizationRole` ("leading and trailing ASCII whitespace is ignored; otherwise only
      these exact strings…") and the same wording for `ProjectRole`.
    - Set `info.version` to 1.2.0.
- Done when: the file parses as OpenAPI 3.1 (name the validator, or say none was available), and every AC's operation
  and code appears.
- **Allowed files:** modify `docs/api/auth-service.openapi.yaml`.
- **Must not:** touch code; document behaviour that isn't in this spec (raise gaps with pm); change phase-1 operations
  except for the FU-7 wording. T1's refactor changes nothing in the contract.

**T9. Invitations are organization-only.** E-items: E36.

- Test first (P15): a test that an `Invitation` without an organization can't be saved. At HEAD it can.
- Done when: that test passes, every phase-1 invitation and acceptance test (backend-swe and qa) passes unchanged, and
  the build is green.
- **Stop condition:** if any existing test saves an invitation without an organization or with a project, stop and
  report the test. Schema pinning in `qa/QaSchemaTest` is handled by the F1 exception (lines 52–53 and the line-122 FK);
  any other pinned line is a stop.
- **F1 exception (2026-10-13):** you may also modify `qa/QaSchemaTest.java` lines 52–53 and the line-122 FK, plus
  `docs/erd/cucumber.dbml` (as-built `invitations`), with the test's values equal to that ERD.
- **Allowed files:** modify `model/Invitation.java` (remove `project`, set the `org_id` join to `nullable = false`) and
  `service/impl/InvitationServiceImpl.java` (remove the project branch, and the `ProjectMembershipRepository` field and
  the imports only it used); create tests in `.../repository/` or `.../service/impl/`.
- **Must not:** change the organization accept path, its checks or their order; change `InvitationRepository`.

**T10. Typed invitation role.** E-items: E37.

- **Precondition:** the user has decided who changes the two qa lines.
    - If qa does it, the main session routes qa first, and qa's change lands together with yours (they can't compile
      separately).
    - If the user granted the F1 exception, you change exactly those two lines.
- Test first (P15): a repository test that saving an invitation and reading it back gives an `OrganizationRole`.
- Done when: every phase-1 test (backend-swe and qa) passes, including AC-5.1 to AC-5.12 and AC-6.1 to AC-6.10, and the
  build is green.
- **Stop condition:** if any other test or main file depends on `role` being a `String`, stop and report it.
- **Allowed files:**
    - modify `model/Invitation.java` (the `role` field only), `service/impl/OrganizationServiceImpl.java` (the three
      role conversions only) and `service/impl/InvitationServiceImpl.java` (the role conversion only);
    - modify `controller/InvitationsTest.java:458` and `controller/InvitationAcceptanceTest.java:553`;
    - with the F1 exception only, modify `qa/QaSupport.java:87` and `qa/QaAuthorizationMatrixTest.java:106`;
    - under the 2026-10-13 F1 exception, `qa/QaSchemaTest.java` lines 52–53 (as far as E37 affects them), with values
      equal to the as-built ERD, and `docs/erd/cucumber.dbml` in the same task;
    - under the extended F1 exception (2026-10-13, Batch B decision): `QaSchemaTest` line 53 →
      `"INVITATIONS.ROLE ENUM NULL"`, lines 94–95 → add `INVITATIONS.ROLE=OWNER`, `=ADMIN`, `=MEMBER`, `=READER`; the
      one `.role("MEMBER")` line in `repository/InvitationRepositoryTest`; in `docs/erd/cucumber.dbml`,
      `invitations.role` becomes `organization_role`;
    - create tests.
- **Must not:** change any other assertion; change role names or JSON shapes.

**T18. No resolved-exception logging (Batch B).** E-items: E58. Numbered after T17 so that existing task IDs stay
stable; independent of T8–T10.

- **Test first** (P15) for AC-9.13: force `spring.mvc.log-resolved-exception=true` in the test (as devtools does under
  `bootRun`) and register the same email twice. Run it against the unchanged code and report how it failed. Expected
  failure: a WARN "Resolved [...EmailAlreadyExistsException: ...]" line containing the email. If it passes, stop and
  report.
    - The test must then pass with E58 applied. If forcing the property in the test also overrides E58's value, make the
      test prove the fix another way (for example, devtools' default applied below `application.yaml`) and report what
      you verified (P12).
- **ACs:** AC-9.13, tagged `@Tag("AC-9.13")`.
- **Done when:** AC-9.13 passes, all 897 existing tests pass unchanged, and the build is green.
- **Stop condition:** an existing test fails or would need a change.
- **Allowed files:** modify `auth-service/src/main/resources/application.yaml` (the one E58 key only); create one test
  class (under `controller/` or `configuration/`) with its own database URL (G3).
- **Must not:** change `EmailAlreadyExistsException`, `CustomUserDetailsService` or any exception message (RC-8, RC-9
  stay unscheduled); change `application-dev.yaml`, `build.gradle` or any existing log statement; add devtools or any
  other dependency.

**Review point B.** The user reviews the contract and the invitation changes. frontend-swe can start from contract
1.2.0. **Accepted 2026-10-13** (T10 finishes under the extended F1 exception before Batch C).

### Batch C: the project API (review point C)

Every controller in this batch follows PL from the start: one service call per handler, `@Valid` request records,
`@PreAuthorize` with `@projectGuard` or `@orgGuard`, no repositories and no business branching. Services take the
organization lock through the repository (P13) and use the E32 resolver (P14).

**T11. Create, list and view projects.** E-items: E34, E35 (`PROJECT_NAME_TAKEN`).

- ACs: AC-1.1 to AC-1.8, AC-2.1 to AC-2.3. AC-2.4 and AC-1.4 row 4 are "completed in T12" (they need archive).
- Done when: those tests pass, the AC-1.7 test is repeated at least 5 times and is green 3 runs in a row, and the build
  is green.
- **Contract wording (Q3):** in `docs/api/auth-service.openapi.yaml`, `CreateProjectRequest.name` description, add "The
  length is counted before trimming." No schema or version change. A test sends a name of 100 characters plus one
  leading space and expects 400.
- **Allowed files:** create the project controller, service, request and response records, and exception; modify
  `exception/ErrorCode.java` (add the constant only) and `repository/ProjectRepository.java` (add methods only); modify
  that one description in `docs/api/auth-service.openapi.yaml`; create tests.
- **Must not:** touch `OrganizationController` or `OrganizationServiceImpl`; add hard delete or pagination.

**T12. Rename, archive, restore.** E-items: E35 (`PROJECT_ARCHIVED`).

- ACs: AC-3.1 to AC-3.5, AC-3.7, AC-3.8, and AC-3.6 row 1. Also finish AC-1.4 row 4, AC-2.4 and AC-7.4. AC-3.6 rows 2–5
  are "completed in T13 and T14".
- **Allowed files:** the T7 and T11 project files; `exception/ErrorCode.java` (add the constant only); create tests.
- **Must not:** let an archived project change except through restore.

**T13. Project members: list and add.** E-items: E34, E35 (`NOT_ORG_MEMBER`, `PROJECT_ROLE_NOT_ALLOWED`).

- ACs: AC-4.1 to AC-4.9, and AC-3.6 row 2.
- Done when: those tests pass, including a test that two simultaneous adds of one user give one membership and one 409
  `ALREADY_MEMBER`, never a 500.
- **Allowed files:** the project files from T11 and T12; `exception/ErrorCode.java` (add the two constants only);
  `repository/ProjectMembershipRepository.java` and `repository/OrgMembershipRepository.java` (add methods only); create
  tests.
- **Must not:** create organization invitations or touch seat counting.

**T14. Change roles, remove, leave.** E-items: none new.

- ACs: AC-5.1 to AC-5.10, and AC-3.6 rows 3–5.
- **Allowed files:** the project files from T11–T13; create tests.
- **Must not:** let a guard contain business rules; add a "last project admin" rule.

**T15. Organization changes carry through.** E-items: E38, E39.

- Test first (P15): AC-6.1, AC-6.2 and AC-6.3 fail at HEAD, because the memberships and roles remain.
- ACs: AC-6.1 to AC-6.6. Every phase-1 AC-7.x and AC-8.x test still passes unchanged.
- **Allowed files:** modify `service/impl/OrganizationServiceImpl.java` (`removeMember` and `changeMemberRole` only: one
  added call each, plus a constructor-injected repository field and its import) and
  `repository/ProjectMembershipRepository.java` (add methods only); create tests.
- **Must not:** change the phase-1 checks, their order or the organization lock; change `OrganizationService`'s
  interface.

**T16. Contract sync, README, gateway contract note, end to end.** E-items: E40, E41, X3.

- Change:
    - Sync `docs/api/auth-service.openapi.yaml` with the final behaviour. It stays 1.2.0, unless something changed since
      T8; then 1.2.1.
    - Add the README sections per E41.
    - Make the X3 change in `docs/api/api-gateway.openapi.yaml`.
    - Run a manual check through the gateway, with both services on `dev`, and record each status code:
        1. Ana registers and logs in, and creates Acme.
        2. Ana creates "Checkout" and invites `cai@example.com` as `MEMBER`.
        3. Cai accepts and logs in.
        4. Ana adds Cai to Checkout as `TESTER`, and Cai views Checkout (`myRole` `TESTER`).
        5. Ana removes Cai, and Cai's view is 403.
        6. AC-7.6.
        7. Directly on 8081: Cai's access check before and after the removal.
- Done when:
    - `./gradlew clean build` is green;
    - both contract files parse;
    - the manual results are in your handoff;
    - a p95 measurement on H2 for `GET {acme-projects}/{checkout}` and for the access check is reported (informational;
      target 100 ms).
- **Allowed files:** modify `docs/api/auth-service.openapi.yaml`, `auth-service/README.md`, and
  `docs/api/api-gateway.openapi.yaml` (the X3 lines and the version only).
- **Must not:** change code or configuration to make a manual check pass. Report instead.

**Review point C.** Then qa verifies.

### pm task (independent of the batches)

**P1. Gateway spec catch-up.** Items: X2. pm edits `docs/specs/api-gateway.md` once D2 is approved and the main session
lets pm write that file. No code.

## Breakdown

| Area                             | Work                                                                                                                                                                                                                                                                                                                                                                                 | Depends on                                                     |
|----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------|
| auth-service                     | T1–T3 (clean-up, review point A1 passed), then T4–T7 (A2), T17 (A3, logging, passed), T8–T10 and T18 (Batch B), T11–T16                                                                                                                                                                                                                                                              | User approval of the E-list; batches in order; T17 needs L1–L3 |
| api-gateway                      | No code or configuration change (D2 option B). Docs: X2 (pm), X3 (T16).                                                                                                                                                                                                                                                                                                              | D2 approval                                                    |
| scenario-service                 | None now. Its first spec uses contract C5 for every project-scoped operation and picks its `/api/projects/{projectId}/...` gateway route. It also adopts the D17 logging pattern (MDC keys, request-ID validation, aspect rules, no-PII test).                                                                                                                                       | C5 (T8); D17                                                   |
| report-service                   | None now. Same as scenario-service, including the D17 logging pattern. Result ingestion later specifies CI upload tokens and service credentials (D8, D9).                                                                                                                                                                                                                           | C5 (T8); D17                                                   |
| docs/api                         | `auth-service.openapi.yaml` 1.2.0 (T8, T16); X3                                                                                                                                                                                                                                                                                                                                      | Review point A2                                                |
| docs/erd                         | `cucumber.dbml`: `proposed (auth-service-projects)` marks, done with this spec. backend-swe turns them into as-built when T4, T9 and T10 land.                                                                                                                                                                                                                                       | —                                                              |
| qa                               | Test plan `docs/qa/auth-service-projects-test-plan.md` once the spec is `Ready`, including a regression note for Batch A1, and test cases for US-9 (AC-9.1 to AC-9.12). The E37 lockstep change, if chosen. Verification after review point C.                                                                                                                                       | Spec `Ready`; T16                                              |
| Frontend: projects (not in repo) | A project list inside the organization view, with `myRole` and an archived marker. A create-project dialog for organization admins. Project settings (rename, archive, restore) for project admins. A project members screen: add from the organization member list, change a role, remove, leave. Hide actions the user's `myRole` doesn't allow, and switch on the Problem `code`. | Contract 1.2.0 (T8)                                            |

## Contracts needed before parallel work

1. **C1. auth-service contract** `docs/api/auth-service.openapi.yaml` 1.2.0 (T8, backend-swe). It must exist before
   frontend-swe starts. The paths, status codes and `code` values in this spec's ACs are binding. If backend-swe needs a
   different path or shape, raise it with pm before T8 is final. Batch A1 changes nothing in it.
2. **C2. JWT contract (unchanged).** No new claim. The `orgs` claim stays informational, and there's no `projects`
   claim.
3. **C3. Error contract (unchanged)**, plus the four D14 codes. E42 keeps the accept-invitation `VALIDATION_FAILED`
   shape exactly. E55 changes nothing in it: `requestId` is still echoed only from a valid request header, and no
   response header is added.
4. **C4. Header contract (unchanged)**, including relative `Location` headers (
   `/api/orgs/{orgId}/projects/{projectId}`).
5. **C5. Project access check (new, D9).** `GET /internal/projects/{projectId}/access` on auth-service's base URL, never
   through the gateway.
    - Request: the end user's `Authorization: Bearer <jwt>`, forwarded unchanged.
    - 200 body: exactly `projectId` (UUID), `orgId` (UUID), `role` (`ADMIN` | `MEMBER` | `TESTER` | `READER`, the
      effective role) and `archived` (boolean).
    - Errors: 401 `UNAUTHENTICATED`; 403 `FORBIDDEN` (no access or unknown project); 400 `MALFORMED_REQUEST`.
    - Consumer rules:
        - compare roles by rank (`ADMIN` > `MEMBER` > `TESTER` > `READER`);
        - no content writes when `archived`;
        - fail closed on a timeout or 5xx;
        - no caching, unless the consumer's spec accepts at most 30 s of staleness.
    - Stability: fields are only ever added. A breaking change needs a new path.

## Cross-service dependencies

| Provider                    | Provides                                                                                         | Consumer                         | Blocks parallel work?                                                                                            |
|-----------------------------|--------------------------------------------------------------------------------------------------|----------------------------------|------------------------------------------------------------------------------------------------------------------|
| auth-service                | The C5 access check (documented in T8, built in T7)                                              | scenario-service, report-service | Their specs can start from T8's contract; their implementation needs T7 deployed. Not blocking for auth-service. |
| auth-service                | Project ids (`projects.id`), which other services store by id (no foreign keys, ERD conventions) | scenario-service, report-service | No                                                                                                               |
| gateway                     | The existing `orgs` route covers every user-facing phase-2 path; `/internal/**` stays unrouted   | frontend, qa                     | No (option B)                                                                                                    |
| auth-service → gateway docs | X2 (spec), X3 (contract note)                                                                    | gateway contract readers         | No                                                                                                               |
| report-service (later)      | Result ingestion spec: CI upload tokens and a service credential (D8, D9)                        | auth-service (a later phase)     | No; out of scope here                                                                                            |

### Known issues that affect verification

1. **Through the gateway, the gateway answers 401 first** (phase-1 Known issue 1). Verify AC-7.3 and other 401 cases
   with direct calls on 8081.
2. **The gateway's own errors have no `code`** (phase-1 Known issue 2). AC-7.6 expects exactly that.
3. **The runtime `/v3/api-docs` differs from the checked-in contract.** It's generated by springdoc. E45 fixes its
   security scheme, but the checked-in file stays the contract (phase-1 D9).
4. **Database constraints come in phase 4.** Project name uniqueness, NOT NULL on `projects.name` and the invitation
   role check are enforced only by the application on H2 (D12).
5. **An organization promotion doesn't restore project roles** that an earlier demotion to `READER` lowered (A6).
6. **Implicit admins aren't in the project member list** (A4). The organization member list shows them.
7. **`/internal/**` is reachable by anyone who can reach port 8081**, but only with a valid token, and it reveals only
   the caller's own access. Network isolation comes with the Helm work.
8. **qa's E37 lines.** If T10 runs without the qa change or the F1 exception, qa's suite doesn't compile. T10's
   precondition covers this.
9. **`JwtAuthenticationFilter` is registered twice** (as a servlet filter because it's a `@Component`, and in the
   security chain). `OncePerRequestFilter` makes it harmless today. Changing the registration is behaviour-sensitive and
   is left for a later audit round (D16, "Considered and not proposed").

## Non-functional requirements

- **Security and authorization**
    - Deny by default: no new public operation, and the gateway configuration doesn't change.
    - Effective roles come from the database on every request (D3, D10). A role change or removal applies to the next
      request, both in auth-service and through C5 (AC-5.5, AC-5.8, AC-7.5).
    - No access, an unknown project and a project of another organization all get the same 403, so ids can't be probed (
      AC-2.3, AC-7.2). Unknown user ids in an add get the same 422 as non-members (AC-4.4).
    - Concurrency:
        - two simultaneous creations can't both take one name (AC-1.7);
        - two simultaneous adds of one user give one membership and a 409, never a 500;
        - a project add can't race an organization removal into an orphaned membership (P13).
    - Never log tokens or `Authorization` values. Problems never contain internals (phase-1 rules).
- **Maintainability.** PL holds for every controller and service after Batch A1. No unused class, method or dependency
  is introduced, and the ones the audit found are removed (D16).
- **Performance (informational, not an AC).** Locally on H2, project endpoints and the access check should answer within
  100 ms at p95 for the Projects fixture. backend-swe reports a measurement in T16. Consumers call the access check once
  per project-scoped request, so it must stay a few indexed lookups.
- **Audit.** No audit entries in phase 2 (D11). INFO logs record project creation, rename, archive and restore, and
  members added, role changes, and members removed or leaving. They contain only the actor id, project id, organization
  id and target id: no emails, names or tokens.
- **Observability (E55, D17, US-9).**
    - Every log line written while a request is handled carries `requestId` (the gateway's or a generated one) and, when
      authenticated, `userId`.
    - Each service use case has DEBUG timings, a WARN above the slow-call threshold, and one INFO line per business
      error.
    - No line contains passwords, hashes, tokens, invitation tokens, emails or full names. With L2, that includes
      Hibernate's constraint messages.
    - The aspect's overhead at the default level is a timer and a level check per service call. The JWT filter path
      isn't advised.
- **Compatibility.** No phase-1 path or response changes, Batch A1 included. Invitation JSON is unchanged by E37,
  because roles were already serialised as their names.
- **Accessibility.** Not applicable (no UI in this repo). The frontend spec covers it.

## Blocking questions

None open. **Resolved by the user on 2026-10-13**, every answer as recommended except E52:

1. **Clean-up items (D16):** E42–E51, E53 and E54 approved. **E52 not approved** (dropped with its T3 step). E51 keeps
   its stop condition; E44 deletes 3 test assertion lines; E54 is the phase's only approved build change. (E43 was later
   withdrawn; see item 11.)
2. **Accept-invitation design (E42):** the D16 design is approved. The service owns the anonymous-body rule and throws a
   typed exception, which the advice maps to the existing 400 `VALIDATION_FAILED` with per-field errors; the controller
   makes one service call.
3. **Project E-list:** E30–E41, X2 and X3 approved. **EG1 rejected.**
4. **Paths (D2):** option B. Project paths under `/api/orgs/{orgId}/projects`; access check at
   `/internal/projects/{projectId}/access`; no gateway change; `/api/projects/**` reserved for content services.
5. **Project roles (D3):** ranks `ADMIN` > `MEMBER` > `TESTER` > `READER`; organization `OWNER` and `ADMIN` are implicit
   project `ADMIN`; an organization `READER` can only be a project `READER`; E38 and E39 apply.
6. **Who creates projects (D4):** organization `OWNER` and `ADMIN` only.
7. **Membership model (D6, D7):** members are added directly, no project invitations; invitations are organization-only
   with a typed role (E36, E37). One-time F1 exception granted: backend-swe changes exactly `qa/QaSupport.java:87` and
   `qa/QaAuthorizationMatrixTest.java:106` in T10, and qa reviews them in re-verification.
8. **`secret_token` (D8):** removed now (E31); the CI token comes with the result-ingestion spec.
9. **Audit log (D11):** deferred to its own spec, `auth-service-audit`.
10. **X2:** pm may edit `docs/specs/api-gateway.md`, docs only (FU-9 and the routing note).
11. **After review point A1 (2026-10-13):** A1 passed (731 tests). backend-swe's 4 deviations are accepted. E43 is
    withdrawn. E55 (logging) and its `application.yaml` configuration (E56) are approved in principle.
12. **After review point A3 (2026-10-13):** A3 passed (897 tests: 486 backend-swe, 411 qa; 0 failed; 834 existing
    unchanged). BUG-5 and FU-6 closed by AC-9.10. backend-swe's 6 T17 notes accepted. E58 approved (T18, AC-9.13).
13. **After Batch B (2026-10-13):** Batch B accepted (901 tests green). Extended F1 exception for T10 (see "User
    decision, 2026-10-13 (Batch B)"). backend-swe's Q2 and Q3 decided by pm in D5: rename order confirmed (
    `PROJECT_ARCHIVED` before `PROJECT_NAME_TAKEN`); name length counted before trimming (contract wording fix in T11).

### Questions for Batch A3: all answered on 2026-10-13

**L1, L2 and L3 were all approved as recommended** (see "User decision, 2026-10-13 (later)"). T17 may start after review
point A2. The original questions are kept below for the record.

- **L1. Build dependency (E57).** Should `build.gradle` declare `org.springframework.boot:spring-boot-starter-aspectj`?
  That's Boot 4.1's AOP starter, verified in the 4.1.0 BOM; `spring-boot-starter-aop` doesn't exist in 4.1. The version
  is managed by the plugin. **Recommended: yes**, because the code uses AspectJ directly and shouldn't depend on the JPA
  starter's transitive `spring-aspects`. **Alternative:** no build change, relying on the transitive `aspectjweaver`
  1.9.25, which works today. Who answers: the user.
- **L2. Fold E29 (FU-6) into E56?** That adds `logging.level.org.hibernate.orm.jdbc.error: ERROR` and AC-9.10. *
  *Recommended: yes**, because without it the "no emails in logs" guarantee fails under a registration race (BUG-5).
  It's the same file in the same batch, and the advice still logs the exception type. Who answers: the user.
- **L3. Confirm E55's file list.** It includes two files that were on the "will not be touched" list:
    - `exception/Problems.java`: `requestId(...)` becomes `public`, so the filter reuses the one validation rule;
    - `configuration/PropertiesConfiguration.java`: one registration.

  It also confirms the design choices in D17: business errors at INFO, controllers not advised, and arguments never
  logged. **Recommended: confirm as written.** Who answers: the user.

## Assumptions

- A1. All data is in-memory (H2) and recreated at each start, so the entity changes need no migration (D12).
- A2. Every project belongs to exactly one organization for its whole life; moving projects is out of scope.
- A3. For request bodies, validation runs before `@PreAuthorize` (phase-1 A3), so a caller who isn't allowed but sends
  an invalid body may get 400 instead of 403. Permission scenarios always use valid bodies. E42 doesn't change this for
  accept: validation still comes before the invitation lookup (phase-1 Q6).
- A4. The project member list shows explicit members only. Implicit admins are visible in the organization member list.
  Showing them in the project list is a later UI decision.
- A5. Lists are small: no pagination. Projects are ordered by name (case-insensitive, then id), and project members by
  email.
- A6. When a demotion to organization `READER` lowers project roles, a later promotion doesn't restore them; a project
  admin re-assigns roles.
- A7. Timestamps (`archivedAt`) remain `LocalDateTime`, like the other auth-service entities; `Instant` comes with phase
  4.
- A8. scenario-service and report-service will enforce the role meanings in D3. Their specs may refine what each role
  can do, but not the order.
- A9. `PATCH {acme-projects}/{projectId}` takes only `{"name": ...}` in phase 2; archive and restore are separate
  operations.
- A10. Spring Boot 4.1 and Spring Security 7 behave as phase 1 found (P12). backend-swe verifies this and reports
  anything different, including how Bean Validation reports field paths for `jakarta.validation.Validator` versus MVC
  binding (E42 relies on them being the same).
- A11. The phase-1 suite (719 tests at acceptance: 308 backend-swe, 411 qa) is a sufficient safety net for the
  behaviour-neutral items in Batch A1. (Confirmed at review point A1: 731 tests, 320 backend-swe and 411 qa, all green.)
- A12. Behind the gateway, every request carries a valid `X-Request-Id` (gateway spec, "Request ID"). A generated ID
  matters only for direct calls, such as the C5 access check. Whether consumers of C5 forward `X-Request-Id` is decided
  in their own specs, and is recommended.
- A13. User ids (UUIDs) in logs are acceptable. Existing INFO lines already log them, and they identify an account
  without revealing who the person is.
- A14. Console logging only. There's no file appender, log shipping or JSON format in phase 2.

## Acceptance

**Decision (2026-10-14): Accepted with follow-ups.**

**Basis:** qa's report `docs/qa/auth-service-projects-report.md` (verdict "Signed off with known issues"), tested on
`6fd4434` plus the user's uncommitted edits to 4 files. Those edits are green but not part of phase 2.

- Every AC has a result: **64 of 64 pass** (P0 16/16, P1 38/38, P2 10/10). None is blocked or untested.
- **No S1, S2 or S3 bugs.** There are two S4 bugs, BUG-1 and BUG-2. Both are phase-1 behaviour and neither breaks an AC
  row.
- auth-service `clean build`: 1655 tests (679 backend-swe, 976 qa), 0 failed. The concurrency classes were rerun 3
  times, all green. api-gateway build: 411 tests, 0 failed, 4 accepted skips.
- Mutation check: backend-swe's tests kill 26 of 26 mutants. The manual run through the gateway (port 8090, because the
  user's Camunda c8run holds 8080) passed every step, including AC-7.6.
- The ERD matches the DDL. qa confirmed the F1 lines that backend-swe edited in qa's tests.
- Deviations the user had already accepted: the two project-specific exception classes, the RC-2 race fallbacks,
  `archivedAt` truncated to microseconds, and the A1 and T17 notes. qa found no observable problem with any of them.

**Rule applied:** every P0 passes, there are no open S1 or S2 bugs, and every AC has a result. The only open bugs are
S4, so the decision is "accept with follow-ups". **No fix round.**

### Decisions on qa's questions (pm, 2026-10-14)

| #  | Question                                                                                                          | Decision (default)                                                                                                                                                                                                                                                                                                                                  | Reason                                                                                                                                                                                                                                                                                                                            | Follow-up                                                                                                                 |
|----|-------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|
| Q1 | Should FU-3 cover project names (Unicode blanks, control characters, lone surrogates)?                            | **Yes.** FU-3's scope becomes every user-supplied name: organization names, `fullName` and project names. A name is invalid (400 `VALIDATION_FAILED`) if it is blank after removing Unicode whitespace (including NBSP, zero-width space, BOM and NEL), or if it contains a control character (U+0000–U+001F, U+007F) or a lone surrogate anywhere. | It's the same class of input with the same PostgreSQL risk: U+0000 is rejected, and a lone surrogate can't be encoded as UTF-8. One rule for every name is easier to test and explain. O1 shows that project names have the gap today.                                                                                            | FU-3 (extended)                                                                                                           |
| Q2 | Is the 100-character limit counted in code points or UTF-16 units?                                                | **Unicode code points**, for every name limit. Until FU-12 lands, the code keeps counting UTF-16 units.                                                                                                                                                                                                                                             | "Characters" means what users see as characters. JSON Schema `maxLength` (the contract) and PostgreSQL `varchar(n)` both count code points. Today's code is stricter only for characters outside the BMP, such as emoji, so nothing over 100 code points can get in. Changing it now would open a fix round for an S4-class edge. | FU-12                                                                                                                     |
| Q3 | Rename, archive, restore and add-member don't re-check the role after taking the lock: accept, or schedule a fix? | **Accept.** Recorded as a P2 candidate that is not scheduled.                                                                                                                                                                                                                                                                                       | The ACs promise that changes take effect on the next request (AC-5.5, AC-7.5), and they do. The window is milliseconds inside a single concurrent request, and qa's invariants held in every race. Re-checking under the lock matters once actions are audited with the actor's role, so the audit spec should revisit it.        | FU-13                                                                                                                     |
| Q4 | Are `show-sql` and start-up output without the MDC inside AC-9.6's scope?                                         | **No.** AC-9.6 covers log lines written through the logging system under the default configuration. Dev-profile `show-sql` writes to stdout and bypasses logging. The `DispatcherServlet` lazy-initialisation lines are one-time framework output.                                                                                                  | E55 was told not to change `application-dev.yaml`, and qa found 0 personal data in those lines.                                                                                                                                                                                                                                   | FU-14 (P2: when phase 4 rewrites the dev config, replace `show-sql` with the SQL logger so the lines get correlation IDs) |
| Q5 | Schedule BUG-1 and BUG-2?                                                                                         | **Yes, as P2 follow-ups. Not now.** BUG-1 (lenient UUIDs) must land before the first C5 consumer goes to production. BUG-2 (scalar coercion into strings) lands with FU-3's input-validation round.                                                                                                                                                 | Both are S4 and both predate phase 2. Nothing is exposed: authorization uses the parsed UUID. BUG-1 matters once another service keys data on the id string. BUG-2 is a global Jackson setting that affects every string field, so it needs the same regression sweep as FU-3.                                                    | FU-10, FU-11                                                                                                              |

Also: **O2 (NFC vs NFD names)**: deferral confirmed. FU-2 (phase 3) now also decides whether names are NFC-normalised
before comparison. **O4, O7, O8, O9**: no action (they are D5 behaviour, the gateway's FU-3, D17 behaviour and the D6
order). The test weaknesses qa listed leave no AC unproven, because qa's tests cover each one. They're recorded as
FU-15.

### Follow-ups: complete auth-service backlog (phase 1 and phase 2)

None of these blocks acceptance. The user's approval is still required where the table says so.

| ID    | Priority / gate                                           | Item                                                                                                                                                                                                                                                                                               | Status                          | Owner                              |
|-------|-----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------|------------------------------------|
| FU-1  | P2                                                        | Problems for errors outside Spring MVC (firewall rejections, `TRACE`, direct `/error`), together with the gateway's FU-3                                                                                                                                                                           | Open                            | pm, backend-swe, qa                |
| FU-2  | P2, phase 3                                               | NFC normalisation of emails. **Extended:** decide NFC for name comparison (O2)                                                                                                                                                                                                                     | Open                            | pm (phase 3 spec)                  |
| FU-3  | **P1, gate: with the PostgreSQL move (phase 4)**          | Blank and control characters in names. **Extended (Q1):** project names, lone surrogates and control characters anywhere in the name                                                                                                                                                               | Open                            | pm (phase 4 spec), backend-swe, qa |
| FU-4  | P2, phase 4                                               | Injectable `Clock`                                                                                                                                                                                                                                                                                 | Open, not approved              | pm, backend-swe                    |
| FU-5  | P2                                                        | Remaining tidy-ups: the 3 stale test texts, and moving tests off the deprecated jjwt API. The `OrganizationProperties` move and the `normalizeEmail` de-duplication were done by E50 and E49                                                                                                       | Open (rest), not approved       | backend-swe                        |
| FU-6  | —                                                         | No email in logs (E29)                                                                                                                                                                                                                                                                             | **Closed** at A3 by AC-9.10     | —                                  |
| FU-7  | —                                                         | Contract wording for padded role strings                                                                                                                                                                                                                                                           | **Done** in T8 (contract 1.2.0) | —                                  |
| FU-8  | **P1, gate: phase 4**                                     | Re-verify the H2-specific behaviour on PostgreSQL. **Extended:** the project-name uniqueness check (application-level; add the phase-4 unique index), the organization lock used by project writes, the `project_memberships.role` and `invitations.role` enum DDL, and `archived_at timestamp(6)` | Open                            | pm (phase 4 spec), qa              |
| FU-9  | —                                                         | Gateway spec "Known issues" catch-up                                                                                                                                                                                                                                                               | **Done** (X2, 2026-10-13)       | —                                  |
| FU-10 | P2, gate: before the first C5 consumer goes to production | BUG-1: accept only canonical 8-4-4-4-12 UUIDs in path variables, and answer 400 `MALFORMED_REQUEST` otherwise                                                                                                                                                                                      | Open                            | backend-swe, qa                    |
| FU-11 | P2, with FU-3                                             | BUG-2: reject numbers and booleans for string fields (400 `MALFORMED_REQUEST`)                                                                                                                                                                                                                     | Open                            | backend-swe, qa                    |
| FU-12 | P2, with FU-3                                             | Count name lengths in code points (Q2). Until then, add a contract note that the server counts UTF-16 code units                                                                                                                                                                                   | Open                            | pm, backend-swe                    |
| FU-13 | P2, revisit in `auth-service-audit`                       | Re-check the actor's role under the organization lock in rename, archive, restore and add (O5, Q3)                                                                                                                                                                                                 | Recorded, not approved          | pm                                 |
| FU-14 | P2, phase 4                                               | Dev profile: replace `show-sql` with the Hibernate SQL logger so SQL lines get correlation IDs (O6, Q4)                                                                                                                                                                                            | Open                            | pm, backend-swe                    |
| FU-15 | P2                                                        | Test hygiene: `ProjectServiceImplTest` uses strict stubs that break on behaviour-neutral refactors; AC-3.6 and AC-1.2 tests don't assert full state; AC-7.3 doesn't check the 401 body (qa weaknesses 1–6)                                                                                         | Open, not approved (G4)         | backend-swe                        |
| FU-16 | **P1, gate: the first C5 consumer spec**                  | Verify the C5 consumer rules (fail closed, no caching) in the consuming service                                                                                                                                                                                                                    | Open                            | pm (consumer spec), qa             |
| RC-1  | P2                                                        | Hibernate violation inspection in `AuthServiceImpl`                                                                                                                                                                                                                                                | Candidate, not approved         | backend-swe                        |
| RC-2  | P2                                                        | Spring Security exceptions thrown from services (logged at DEBUG). The race fallbacks were accepted as they are                                                                                                                                                                                    | Candidate, not approved         | backend-swe                        |
| RC-3  | P2                                                        | Duplicated `UserResponse` / `OrganizationResponse` mapping                                                                                                                                                                                                                                         | Candidate, not approved         | backend-swe                        |
| RC-4  | P2                                                        | Security infrastructure in `service/impl`                                                                                                                                                                                                                                                          | Candidate, not approved         | backend-swe                        |
| RC-5  | P2                                                        | No-op Lombok `@NonNull` in `OrganizationResponse`                                                                                                                                                                                                                                                  | Candidate, not approved         | backend-swe                        |
| RC-6  | P2                                                        | Stale lock texts in `handleLockFailure` Javadoc and `LockFailureTest`                                                                                                                                                                                                                              | Candidate, not approved         | backend-swe                        |
| RC-7  | P2                                                        | JPA lock exception types no longer reached                                                                                                                                                                                                                                                         | Candidate, not approved         | backend-swe                        |
| RC-8  | P2, before production                                     | `EmailAlreadyExistsException`: not an `ApiException`, and an email in its message (mitigated by E58)                                                                                                                                                                                               | Candidate, not approved         | backend-swe                        |
| RC-9  | P2, before production                                     | Email in the `UsernameNotFoundException` message                                                                                                                                                                                                                                                   | Candidate, not approved         | backend-swe                        |
| RC-10 | P2                                                        | `JwtAuthenticationFilter` registered twice                                                                                                                                                                                                                                                         | Candidate, not approved         | backend-swe                        |

**Notes for the user (not follow-ups, about your uncommitted edits):** qa found that `AcceptInvitationResult`'s new
`@Builder` is unused, that `JwtProperties` has an unused `DefaultValue` import, and that the boxed `Long`/`Boolean`
return types in `OrgMembershipRepository` work but add no value. All are green.

### Next-phase recommendation

**Do phase 4 (persistence: PostgreSQL + Flyway) next, and write the scenario-service spec in parallel.** Why:

- Phase 4 closes the only P1 follow-ups (FU-3, FU-8). FU-10 to FU-12 and FU-14 fit into it naturally.
- It sets the persistence pattern (PostgreSQL, Flyway, `Instant`, `Clock`) that scenario-service will copy, so
  scenario-service doesn't start on H2 and need its own migration later.
- scenario-service can then build against the approved ERD and the C5 access check, with FU-10 and FU-16 already
  handled.
- Phase 3 (accounts) can wait: nothing in the product is blocked on it.
