# Test plan: auth-service phase 2 (auth-service-projects)

Status: executed on 2026-10-13. Results are in `docs/qa/auth-service-projects-report.md`.

## 1. Scope and references

| Item                 | Reference                                                                                                                                                                                                                                                                                                                                                                                    |
|----------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Spec                 | `docs/specs/auth-service-projects.md` (Ready). 64 ACs: US-1 8, US-2 4, US-3 8, US-4 9, US-5 10, US-6 6, US-7 6, US-9 13. P0 16, P1 38, P2 10.                                                                                                                                                                                                                                                |
| Decisions under test | D3 (ranks, implicit admin, READER cap, effective role), D4 (who can do what), D5 (names, archive, rename error order, Q3 length before trimming), D6 (memberships, carry-through, add/change/remove error orders), D7/D8 (invitations organization-only, `secret_token` removed), D9/C5 (access check), D10 (guard), D14 (codes), D17 (logging), P13 (organization lock), P14 (one resolver) |
| Contracts            | `docs/api/auth-service.openapi.yaml` 1.2.1; `docs/api/api-gateway.openapi.yaml` 0.2.2 (`/internal/**` never routed)                                                                                                                                                                                                                                                                          |
| ERD                  | `docs/erd/cucumber.dbml`, `auth_service` group (as-built)                                                                                                                                                                                                                                                                                                                                    |
| README               | `auth-service/README.md` ("Projects", "Access check for other services")                                                                                                                                                                                                                                                                                                                     |
| Code                 | HEAD `8345746` plus the uncommitted phase-2 working tree (T1–T18)                                                                                                                                                                                                                                                                                                                            |
| Earlier qa artifacts | `docs/qa/auth-service-test-plan.md`, `docs/qa/auth-service-report.md` (phase 1); `docs/qa/api-gateway-*.md`                                                                                                                                                                                                                                                                                  |

Out of scope (spec "Out of scope"): project invitations, hard delete, CI upload tokens, service credentials, audit log,
`/api/projects/**` routing, pagination, phase-4 items (PostgreSQL, DB constraints, FU-3, FU-8). FU-3 inputs (Unicode
blanks, control characters) are characterised only and reported, not failed, unless they break a phase-2 AC.

## 2. Testability findings sent to pm

The spec is testable as written. None of these block testing; they're questions for pm (see the report):

1. **"1–100 characters" (D5).** Bean Validation's `@Size` counts UTF-16 code units, while the contract's
   `maxLength: 100` (JSON Schema) counts code points. 51 emoji are 51 characters for the contract and 102 for the
   server. Which one is meant?
2. **Unicode-blank and control-character project names.** Phase 1's FU-3 covers organization names and `fullName`.
   Project names have the same gap (NBSP-only names are accepted, and `" Checkout"` sits next to `"Checkout"`). Should
   FU-3's scope add project names?
3. **AC-4.4 "doesn't exist".** Covered (unknown id = 422). No change needed.
4. **Actor re-check after the organization lock.** Rename, archive, restore and add don't re-check the actor's role
   after taking the lock, while change and remove do (RC-2 fallbacks). In a race, an action that passed the guard just
   before a demotion still completes. "Next request" in AC-5.5 and AC-7.5 is satisfied. Is this acceptable, or should
   every write re-check?

## 3. Approach and levels

| Level                        | Where                                                                                                                                                     | Tooling                                                                                          | Notes                                                                                                                                 |
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| API / integration            | `auth-service/src/test/java/com/example/authservice/qa/`                                                                                                  | `@AuthServiceIntegrationTest` (full context, real security chain, H2, MockMvc), `ProjectFixture` | Not `@Transactional`. Every test reseeds. Tokens minted with the TEST-ONLY secret (`QaSupport.tokenFor`), with an empty `orgs` claim. |
| Concurrency                  | same                                                                                                                                                      | `QaSupport.concurrently` (latch-released thread pool)                                            | `@RepeatedTest(5)`; the classes are rerun 3 times with `--rerun`                                                                      |
| Logging                      | same                                                                                                                                                      | `OutputCaptureExtension`, `LoggingSystem`, backend-swe's `aop.RequestLog`                        | Own H2 URL when the class has its own context (G3)                                                                                    |
| Schema                       | `QaSchemaTest` (existing) plus the dev-profile DDL printed by `show-sql` at the manual start                                                              | `INFORMATION_SCHEMA`                                                                             | Compared with the ERD                                                                                                                 |
| Audit of backend-swe's tests | read every AC test against its Gherkin                                                                                                                    |                                                                                                  | Section 6                                                                                                                             |
| Mutation check               | scratch copy under `/tmp/qa-p2-mut`, deleted afterwards                                                                                                   | 26 single-defect mutants, full suite per mutant, BE and QA kills counted separately              | Section 6                                                                                                                             |
| Manual E2E                   | gateway on **8090** (8080 is held by the user's Camunda `c8run`, which must not be stopped) and auth-service on 8081, both `--spring.profiles.active=dev` | `curl`                                                                                           | AC-7.6 and the T16 flow                                                                                                               |

## 4. Test cases

Priority is the AC's priority. "BE" means backend-swe's test and "QA" means qa's. The Gherkin row tests are
backend-swe's and are audited in section 6. The rows below list what qa adds or checks.

### US-1: Create projects

| ID     | AC     | Level       | Preconditions and data       | Steps                                                                                                                                                                  | Expected                                                                                      | Priority |
|--------|--------|-------------|------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|----------|
| TC-1.1 | AC-1.1 | API         | Projects fixture             | BE `ProjectsTest#anOrganizationAdminCreatesAProject`, `ProjectMembersTest#aNewProjectHasNoExplicitMembers`; QA mass-assignment create                                  | 201, exact fields, relative `Location`, `[]` members; server-owned fields in the body ignored | P0       |
| TC-1.2 | AC-1.2 | API         | Projects fixture; matrix org | BE 6 rows; QA `QaProjectRoleMatrixTest` CREATE for 5 org roles × 5 explicit roles                                                                                      | OWNER/ADMIN 201, others 403 and no project                                                    | P0       |
| TC-1.3 | AC-1.3 | API         |                              | BE 4 rows plus boundaries; QA whitespace-only by Java's definition (tab, CR/LF, U+3000, U+2003, U+2028), length counted before trimming (space+100, 100+tab, 50 emoji) | 400 `VALIDATION_FAILED` with an `errors` entry for `name`; nothing stored                     | P0       |
| TC-1.4 | AC-1.4 | API         | Billing archived for row 4   | BE 4 rows; QA non-ASCII case pairs (Ü, Cyrillic, Greek, accented), ASCII whitespace or control characters at the ends, Locale.ROOT behaviour (`İ`, `ß`)                | 409 `PROJECT_NAME_TAKEN`, count unchanged                                                     | P1       |
| TC-1.5 | AC-1.5 | API         |                              | BE                                                                                                                                                                     | 201 in Initech                                                                                | P1       |
| TC-1.6 | AC-1.6 | API         |                              | BE 3 rows                                                                                                                                                              | 403 / 403 / 400                                                                               | P1       |
| TC-1.7 | AC-1.7 | concurrency |                              | BE ×5; mutant M13 (no lock)                                                                                                                                            | one 201, four 409, one project                                                                | P2       |
| TC-1.8 | AC-1.8 | API         |                              | BE; QA padded own name on rename                                                                                                                                       | stored trimmed                                                                                | P2       |

### US-2: List and view projects

| ID     | AC     | Level | Preconditions and data                                  | Steps                                                                                                                                           | Expected                                                             | Priority |
|--------|--------|-------|---------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|----------|
| TC-2.1 | AC-2.1 | API   |                                                         | BE 8 rows; QA matrix LIST (25 role combinations); QA ordering (A5); QA no other organization's project in a list                                | exactly the accessible projects with `myRole`, by name ignoring case | P0       |
| TC-2.2 | AC-2.2 | API   |                                                         | BE 8 rows; QA matrix VIEW                                                                                                                       | `myRole` = effective role, else 403                                  | P0       |
| TC-2.3 | AC-2.3 | API   | Eve is also an Acme ADMIN; Ana is also an Initech ADMIN | BE 5 rows; QA project under the wrong organization's path for an admin of both organizations, 8 operations × 2 directions; identical 403 bodies | 403 everywhere, nothing changed, 403 bodies indistinguishable        | P0       |
| TC-2.4 | AC-2.4 | API   | Billing archived                                        | BE; QA archived reads                                                                                                                           | `archivedAt` listed                                                  | P1       |

### US-3: Rename, archive, restore

| ID     | AC              | Level            | Preconditions and data | Steps                                                                                                                                                                             | Expected                                           | Priority |
|--------|-----------------|------------------|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------|----------|
| TC-3.1 | AC-3.1          | API              |                        | BE 8 rows; QA matrix RENAME; X-User-Id and forged `orgs`/`projects` claims ignored                                                                                                | admins 200, others 403                             | P1       |
| TC-3.2 | AC-3.2          | API              |                        | BE 2 rows; QA non-ASCII rename duplicates, freed name, padded own name                                                                                                            | as D5                                              | P1       |
| TC-3.3 | AC-3.3          | API              |                        | BE 3 rows; QA rename length before trimming                                                                                                                                       | 400                                                | P1       |
| TC-3.4 | AC-3.4          | API              |                        | BE 5 rows; QA matrix ARCHIVE                                                                                                                                                      | admins 200                                         | P1       |
| TC-3.5 | AC-3.5          | API              | Checkout archived      | BE (view and members in two tests); QA all four reads incl. access check                                                                                                          | readable                                           | P1       |
| TC-3.6 | AC-3.6          | API              | Checkout archived      | BE 5 rows plus order tests; QA 18 rows (every admin kind × every write, self-leave by every member, self role change, same-name rename)                                           | 409 `PROJECT_ARCHIVED`; name and members unchanged | P1       |
| TC-3.7 | AC-3.7          | API              |                        | BE 3 rows; QA matrix RESTORE; writes after restore                                                                                                                                | as AC                                              | P1       |
| TC-3.8 | AC-3.8          | API, concurrency |                        | BE 2 rows; QA 6 simultaneous archives give one `archivedAt`; ping-pong archive/restore; new time after restore                                                                    | idempotent, consistent                             | P2       |
| TC-3.R | D5 rename order | API              |                        | QA 13-row order table (400 > 403 > `PROJECT_ARCHIVED` > `PROJECT_NAME_TAKEN`, incl. unknown project, outsider, member, reader, implicit and explicit admin, padded and own names) | first failure wins                                 | P1       |

### US-4: View and add project members

| ID     | AC           | Level            | Preconditions and data        | Steps                                                                                      | Expected                                   | Priority |
|--------|--------------|------------------|-------------------------------|--------------------------------------------------------------------------------------------|--------------------------------------------|----------|
| TC-4.1 | AC-4.1       | API              |                               | BE 5 rows; QA matrix LIST_MEMBERS                                                          | 200 for access, 403 otherwise              | P0       |
| TC-4.2 | AC-4.2       | API              |                               | BE; QA add with smuggled `projectId`/`orgId`/`joinedAt`                                    | 201; only the path's project               | P0       |
| TC-4.3 | AC-4.3       | API              |                               | BE 8 rows; QA matrix ADD_MEMBER                                                            | admins 201                                 | P0       |
| TC-4.4 | AC-4.4       | API              | Zed is an Initech member only | BE 2 rows; QA another organization's member, identical answers for unknown and foreign ids | 422 `NOT_ORG_MEMBER`                       | P0       |
| TC-4.5 | AC-4.5       | API, concurrency |                               | BE; BE simultaneous adds ×5; mutant M14                                                    | 409 `ALREADY_MEMBER`, never 500            | P1       |
| TC-4.6 | AC-4.6       | API, concurrency |                               | BE 4 rows; QA demotion racing an add as ADMIN                                              | cap holds                                  | P0       |
| TC-4.7 | AC-4.7       | API              |                               | BE 5 rows                                                                                  | 400 codes                                  | P1       |
| TC-4.8 | AC-4.8       | API              | seat limit 7                  | BE                                                                                         | 201; invitation still `SEAT_LIMIT_REACHED` | P2       |
| TC-4.9 | AC-4.9       | API              |                               | BE; QA ADMIN demoted to MEMBER keeps only the explicit row                                 | 201; `myRole` ADMIN                        | P2       |
| TC-4.O | D6 add order | API              |                               | BE archived > not-org-member, already-member > cap; mutant M24                             | first failure wins                         | P1       |

### US-5: Change roles, remove, leave

| ID      | AC      | Level | Preconditions and data | Steps                                                                                          | Expected                                        | Priority |
|---------|---------|-------|------------------------|------------------------------------------------------------------------------------------------|-------------------------------------------------|----------|
| TC-5.1  | AC-5.1  | API   |                        | BE 6 rows + invalid bodies; QA matrix CHANGE_ROLE; IDOR via Checkout path never touches Portal | admins 200, others 403                          | P1       |
| TC-5.2  | AC-5.2  | API   |                        | BE 2 rows + order; QA matrix CHANGE_OWN_ROLE (25 combinations)                                 | 422 `SELF_ROLE_CHANGE`; implicit admin 404      | P1       |
| TC-5.3  | AC-5.3  | API   |                        | BE 3 rows; QA another organization's project member through Checkout                           | 404 `MEMBER_NOT_FOUND`, other project untouched | P1       |
| TC-5.4  | AC-5.4  | API   |                        | BE; QA cap after demotion and re-promotion (A6)                                                | 422 `PROJECT_ROLE_NOT_ALLOWED`                  | P1       |
| TC-5.5  | AC-5.5  | API   |                        | BE; QA access check after each role change                                                     | next request                                    | P1       |
| TC-5.6  | AC-5.6  | API   |                        | BE 6 rows; QA matrix REMOVE_OTHER                                                              | admins 204                                      | P1       |
| TC-5.7  | AC-5.7  | API   |                        | BE 4 rows; QA matrix LEAVE (25 combinations incl. stale rows)                                  | 204 for explicit members with access            | P1       |
| TC-5.8  | AC-5.8  | API   |                        | BE                                                                                             | 403 and `[]`                                    | P1       |
| TC-5.9  | AC-5.9  | API   |                        | BE 2 rows; QA matrix LEAVE for implicit admins                                                 | 404                                             | P1       |
| TC-5.10 | AC-5.10 | API   |                        | BE                                                                                             | 200 no-op                                       | P2       |

### US-6: Organization changes carry through

| ID     | AC     | Level            | Preconditions and data                                                          | Steps                                                                                                                                                                                     | Expected          | Priority |
|--------|--------|------------------|---------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------|----------|
| TC-6.1 | AC-6.1 | API, concurrency | Hal is also an Initech member with Portal TESTER and an explicit Billing MEMBER | BE 2 rows; QA removal or leave deletes every Acme row and no Initech row; old token gets 403 on all reads; races with a project add, a leave and a role change (×5 each); invariant check | as D6/E38         | P1       |
| TC-6.2 | AC-6.2 | API              |                                                                                 | BE                                                                                                                                                                                        | `[]`              | P1       |
| TC-6.3 | AC-6.3 | API, concurrency |                                                                                 | BE; QA demotion lowers every Acme role and no Initech one; demotion racing a promotion to ADMIN (×5)                                                                                      | READER; cap holds | P1       |
| TC-6.4 | AC-6.4 | API              |                                                                                 | BE 3 rows                                                                                                                                                                                 | kept              | P1       |
| TC-6.5 | AC-6.5 | API              |                                                                                 | BE                                                                                                                                                                                        | ADMIN             | P1       |
| TC-6.6 | AC-6.6 | API              |                                                                                 | BE; QA demoted ADMIN with an explicit row                                                                                                                                                 | 403, `[]`         | P1       |

### US-7: Access check

| ID     | AC     | Level      | Preconditions and data     | Steps                                                                                                                                                                                                                                         | Expected                                              | Priority |
|--------|--------|------------|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------|----------|
| TC-7.1 | AC-7.1 | API        |                            | BE 8 rows; QA matrix ACCESS_CHECK (exact body for 25 combinations); response headers; query or header impersonation; forged role claims                                                                                                       | exact C5 body                                         | P0       |
| TC-7.2 | AC-7.2 | API        |                            | BE 3 rows; QA 7 malformed ids, ids of other row kinds and the nil UUID, upper-case ids, identical 403s                                                                                                                                        | 400 / 403                                             | P0       |
| TC-7.3 | AC-7.3 | API        |                            | BE 3 rows; QA 15 unusable credentials (empty bearer, garbage, Basic, `alg: none`, HS256, other secret, expired, altered or non-UUID or missing or unknown `sub`, no `exp`, two tokens, token in query or cookie), suspended and deleted users | 401 with `WWW-Authenticate: Bearer`, nothing revealed | P0       |
| TC-7.4 | AC-7.4 | API        |                            | BE; QA                                                                                                                                                                                                                                        | `archived: true` with the role                        | P0       |
| TC-7.5 | AC-7.5 | API        |                            | BE; QA demotion, promotion, organization READER and removal, each on the next call                                                                                                                                                            | next request                                          | P0       |
| TC-7.6 | AC-7.6 | manual E2E | both services, dev profile | `GET /internal/projects/{checkout}/access` through the gateway with a valid token; also `HEAD`, `POST`, path variants (`//internal`, `/internal/`, encoded)                                                                                   | gateway 404 Problem without `code`                    | P1       |
| TC-7.X | C5     | API        |                            | QA other methods (405), neighbouring `/internal` paths (404, 401 without token)                                                                                                                                                               | nothing else exists                                   | P0       |

### US-9: Logging

| ID                    | AC                             | Level       | Preconditions and data                 | Steps                                                                                                                                            | Expected                                | Priority |
|-----------------------|--------------------------------|-------------|----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------|----------|
| TC-9.1–9.5, 9.10–9.12 | AC-9.1–AC-9.5, AC-9.10–AC-9.12 | integration |                                        | BE tests (audited; accepted at review point A3)                                                                                                  | as AC                                   | P1/P2    |
| TC-9.6                | AC-9.6                         | integration | DEBUG                                  | QA full projects flow (26 requests): every entry line has the request's ID and the caller's ID; 12 simultaneous requests keep their own IDs (×3) | correlated                              | P1       |
| TC-9.7                | AC-9.7                         | integration |                                        | QA access check with an invalid header                                                                                                           | one generated UUID, header never logged | P1       |
| TC-9.8                | AC-9.8                         | integration |                                        | QA MDC empty after each of the 26 requests                                                                                                       | cleared                                 | P1       |
| TC-9.9                | AC-9.9                         | integration | DEBUG                                  | QA full projects flow: no email, full name, password, hash, JWT, `Bearer`, or project name in any line                                           | none                                    | P1       |
| TC-9.13               | AC-9.13                        | integration | devtools default applied like devtools | QA same flow including validation, 403, 409 and 422 errors                                                                                       | no PII                                  | P2       |
| TC-9.M                | US-9                           | manual      | dev profile                            | logs of the manual run grepped for emails, names, tokens                                                                                         | none                                    | P1       |

### Cross-cutting

| ID     | Area         | Level               | Steps                                                                                                                                                                                                                          | Expected                                  |
|--------|--------------|---------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------|
| TC-X.1 | D3/D4 matrix | API                 | `QaProjectRoleMatrixTest`: 5 organization roles (incl. none) × 5 explicit roles (incl. none, and stale rows above the cap or without an organization membership) × 13 operations = 325 cases, expected values computed from D3 | every case as D3/D4                       |
| TC-X.2 | Schema       | integration, manual | `QaSchemaTest` vs ERD; dev DDL from the manual start vs ERD                                                                                                                                                                    | equal                                     |
| TC-X.3 | Mutation     | scratch             | 26 mutants                                                                                                                                                                                                                     | every mutant killed by at least one suite |
| TC-X.4 | Regression   | full build          | `./gradlew clean build`; qa's 411 phase-1 tests unchanged apart from the F1 lines                                                                                                                                              | green                                     |
| TC-X.5 | F1 review    | review              | `QaSchemaTest` lines 45, 52–53, 94–95, 122; `QaSupport.java:87`; `QaAuthorizationMatrixTest.java:106` vs ERD and spec                                                                                                          | values equal the as-built ERD             |
| TC-X.6 | Gateway      | full build          | `cd api-gateway && ./gradlew build`                                                                                                                                                                                            | green, no regression                      |

## 5. Role and permission matrix (expected, from D3 and D4)

Effective project role: none without organization membership; ADMIN for organization OWNER/ADMIN; otherwise the explicit
role, capped at READER for organization READER.

| Operation                        | Organization OWNER | Organization ADMIN | Project ADMIN (explicit) | Project MEMBER | Project TESTER | Project READER | Organization member without access | Outsider |
|----------------------------------|--------------------|--------------------|--------------------------|----------------|----------------|----------------|------------------------------------|----------|
| Create project                   | 201                | 201                | 403                      | 403            | 403            | 403            | 403                                | 403      |
| List projects                    | 200 (all)          | 200 (all)          | 200 (own)                | 200 (own)      | 200 (own)      | 200 (own)      | 200 `[]`                           | 403      |
| View, list members, access check | 200                | 200                | 200                      | 200            | 200            | 200            | 403                                | 403      |
| Rename, archive, restore         | 200                | 200                | 200                      | 403            | 403            | 403            | 403                                | 403      |
| Add, change role, remove others  | 201/200/204        | same               | same                     | 403            | 403            | 403            | 403                                | 403      |
| Leave                            | 404 (no row)       | 404 (no row)       | 204                      | 204            | 204            | 204            | 403                                | 403      |
| Change own role                  | 404 (no row)       | 404 (no row)       | 422                      | 422            | 422            | 422            | 403                                | 403      |

## 6. Audit and mutation method

- **Audit:** every backend-swe AC test is compared with its Gherkin rows and "Then/And" steps: actors, bodies, status,
  `code`, state checks, and "nothing changed" checks. Gaps are reported as test weaknesses, not bugs.
- **Mutants (26):** inverted ranks (M1); implicit admin only for OWNER (M2); no READER cap in the resolver (M3);
  org/project path binding removed in both layers (M4) or in the guard only (M4b); archived checks removed for add (M5)
  and remove/leave (M19); rename order swapped (M6); case-sensitive names (M7); archived names not reserved (M8); E38 or
  E39 removed (M9, M10) or not scoped to the organization (M11, M12); no organization lock for create (M13) or add (
  M14); limit 101 (M15); any member may change or remove others (M16); access check `archived` always false (M17); list
  shows every project (M18); SELF after cap (M20); cap removed from change (M21) and add (M22); `userId` MDC not
  cleared (M23); cap before ALREADY_MEMBER (M24); untrimmed stored name (M25).

## 7. Test data and environment

- `support/ProjectFixture` (backend-swe) on top of `AcmeFixture`; qa adds rows through repositories only (Eve as Acme
  ADMIN, Ana as Initech ADMIN, Hal as Initech MEMBER with Portal TESTER and Billing MEMBER, Zed as an Initech MEMBER and
  Portal READER, stale rows for the matrix).
- No new dependencies and no tooling proposals.
- Manual run: auth-service `./gradlew bootRun --args='--spring.profiles.active=dev'` on 8081; gateway on **8090** (
  `--server.port=8090`) because 8080 is held by the user's Camunda `c8run` (PIDs 55563, 57370), which is not touched.

## 8. Batch A1 regression note

Batch A1 (E42, E44–E51; E43 withdrawn, E52 not approved) is behaviour-neutral by design. It's proven by the unchanged
phase-1 suites:

- qa's 411 phase-1 tests pass, and `git diff` shows that the only changes to qa files are the user-approved F1 lines (
  reviewed in the report);
- E42 (accept invitation) is covered by `QaInvitationSecurityTest`, `QaInputEdgeCasesTest` and `QaFixRoundTest`,
  unchanged;
- E51 (repository lock) is covered by `QaFixRoundTest` real-lock tests and `QaConcurrencyTest`, unchanged and rerun 3
  times;
- E44, E45, E47 and E48 are removals: no qa test referenced the removed code.

## 9. Risks and items not covered

- **H2 only.** Locking and constraint behaviour on PostgreSQL is phase 4 (FU-8). No DB unique index on project names;
  uniqueness relies on the organization lock (AC-1.7 covered by races).
- **FU-3** (Unicode blanks, control characters, lone surrogates) and NFC/NFD duplicates are deferred; qa characterises
  them only.
- **No cross-service consumer exists yet**, so C5 consumer rules (fail closed, no cache) can't be tested end to end.
- **Gateway behaviour** for AC-7.6 is checked manually; the gateway's own test suite already pins unrouted paths.
- **Timing:** p95 is informational and not measured by qa.
