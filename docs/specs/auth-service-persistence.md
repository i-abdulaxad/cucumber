# auth-service phase 4: persistence (PostgreSQL + Flyway)
Status: Accepted (with follow-ups FU-17 to FU-24; final acceptance 2026-10-15 after fix round 1, see Acceptance)
Last updated: 2026-10-18 (post-acceptance FR2 / E85: tests never load `.env`; guardrail G13)

## Problem and goal

auth-service keeps everything in an in-memory H2 database whose schema Hibernate creates at every start (`application.yaml:11-25`, `ddl-auto: create`). That was fine for phases 1 and 2. It now blocks the platform:

- **Nothing survives a restart.** No user, organization or project outlives the process, so auth-service can't be deployed or demonstrated for real.
- **The schema is whatever Hibernate generates on H2.** ERD-D11 (approved 2026-10-12) says each service gets its own PostgreSQL database with Flyway-managed schemas. Today there's no migration, and the H2 DDL differs from PostgreSQL's: H2 uses native `ENUM` types, PostgreSQL doesn't.
- **The database enforces almost nothing.** Business columns are nullable. Email uniqueness is case-sensitive. Project-name uniqueness exists only in the application. Foreign-key columns have no indexes and no stated delete rule. `audit_log.metadata` is a `String` in a `json` column, which fails on PostgreSQL. Timestamps are `LocalDateTime` without a zone (ERD defects routed to phase 4, `docs/specs/auth-service.md:84-93`; phase-2 D12 flags).
- **Behaviour was only verified on H2.** FU-8 (P1) asks to re-verify on PostgreSQL: lock exceptions, unique-violation handling, emails in constraint messages, U+0000 and enum DDL.
- **Some inputs PostgreSQL can't store.** A name containing U+0000 is a 500 on PostgreSQL, and a lone surrogate is silently changed. qa's `QaProjectNamesTest:149-168` already sends both (FU-3, P1, gated on this move).
- **scenario-service and report-service will copy whatever pattern auth-service sets.** It should be the right one before they start.

**Goal of phase 4:**
- auth-service runs on PostgreSQL in every environment (dev, test and later production). Its schema comes only from versioned Flyway migrations, and Hibernate only validates it.
- The database enforces the data rules the application already relies on.
- Every existing behaviour is proven on PostgreSQL.
- Text PostgreSQL can't store is rejected with a 400 before it reaches the database.
- Timestamps are UTC instants.
- The phase-1 and phase-2 follow-ups FU-3, FU-4, FU-8, FU-10, FU-11, FU-12 and FU-14 are closed.
- The database pattern is written down once, so scenario-service and report-service can copy it.

**Success:**
- `cd auth-service && ./gradlew clean build` is green with every test running on PostgreSQL (1655 tests at the phase-2 baseline, plus phase-4 tests). Every test class keeps its test count. The only changed test lines are the ones this spec names.
- With the `dev` profile, auth-service starts against the local PostgreSQL, applies its migrations, and keeps data across restarts.
- The manual end-to-end flow through the gateway passes unchanged.
- Every P0 acceptance criterion passes.

## Users and roles affected

| Persona | Need |
|---|---|
| The user, as code owner and operator | Data that survives restarts; a schema under version control; secrets outside the repository; no surprise edits to existing code |
| Developers (backend-swe, frontend-swe, qa) | One command to build and test; a documented local setup; tests that prove behaviour on the real database |
| Frontend developer (not in the repo) | Timestamps with an explicit zone; stable error codes for bad input |
| scenario-service and report-service developers (not built yet) | A database pattern to copy instead of reinventing it |
| End users (all organization and project roles) | No visible change except: timestamps end in `Z`; some bad inputs are now rejected with 400 |

**Roles.** No role, permission or authorization rule changes. Organization roles (`OWNER`, `ADMIN`, `MEMBER`, `READER`) and project roles (`ADMIN`, `MEMBER`, `TESTER`, `READER`) keep their phase-1 and phase-2 ranks and meaning. The permission cases in this spec reuse the phase-2 matrices unchanged, through AC-3.1.

## Glossary

Phase 1's and phase 2's glossaries apply (Problem, "a Problem S / C", `{token of X}`, `{X.id}`, `{unknown id}`, the Acme fixture, the Projects fixture, `{acme-projects}`, effective project role, refactor item). New or changed terms:

| Term | Meaning in this spec |
|---|---|
| Migration | A versioned SQL file `V<n>__<description>.sql` under `auth-service/src/main/resources/db/migration/`, applied once by Flyway, in version order. |
| Baseline (V1) | The first migration. It creates the schema that the current entities (HEAD `6fd4434`) map to on PostgreSQL: same tables, columns, types, nullability, unique constraints, foreign keys and enum checks as Hibernate's own PostgreSQL DDL for those entities (D4). Constraint names may differ: V1 uses readable names. |
| Migration history | Flyway's `flyway_schema_history` table: one row per applied migration, with its version, checksum and success flag. |
| Test database | The one PostgreSQL database that every test context uses (D2). Isolation between tests comes from fixture resets, not from separate databases. |
| Scratch database | An empty PostgreSQL database that a single test creates, uses and drops. Only start-up tests use one (AC-1.1 to AC-1.6). It has the same encoding and locale as the test database. |
| Direct SQL | A statement a test sends through JDBC to the test database, bypassing the API, to prove a database rule (US-2). Each one runs in its own transaction, which is rolled back after the check. |
| Fresh database | A database with no tables. |
| Schema drift | A difference between the database schema and what the entities need, for example a missing column. |
| Stored value (of a name) | What auth-service writes to the database: the trimmed name for project names (`String.trim()`, phase-2 D5); the name as sent for organization names and full names; the normalised email (trimmed, lower case) for emails. |
| Control character | A code point for which `Character.isISOControl` is true: U+0000 to U+001F and U+007F to U+009F (C0 and C1 controls, including tab, CR, LF and NEL). |
| Lone surrogate | A UTF-16 surrogate (U+D800 to U+DFFF) that isn't part of a valid pair. JSON can carry one as `"\uD800"`; UTF-8 can't encode it. |
| Unicode-blank | A string in which every code point is whitespace (`Character.isWhitespace` or `Character.isSpaceChar`, which includes NBSP U+00A0, U+2007 and U+202F) or one of the invisible format characters U+200B, U+200C, U+200D, U+2060 or U+FEFF. |
| Code-point length | The number of Unicode code points (`String.codePointCount`), so one emoji counts as 1. |
| Canonical UUID | 36 characters: hexadecimal digits in groups of 8-4-4-4-12, separated by hyphens. Upper- and lower-case digits are both accepted. |
| Service clock | The single `java.time.Clock` that auth-service reads the current time from (D7). It is UTC and ticks in microseconds, the precision PostgreSQL stores. |
| UTC timestamp | A JSON string such as `2026-10-14T09:30:00.123456Z`: ISO-8601 with a `Z` offset and 0 to 6 fraction digits. |

## Scope

### In scope
- **PostgreSQL for every environment**, with one database per service (ERD-D11). Settings come from environment variables, with clearly labelled dev values for the `dev` profile (D5).
- **Flyway:**
  - a V1 baseline that equals the current entities' schema;
  - versioned fix migrations, one per E-item;
  - `ddl-auto: validate`.
- **Tests on PostgreSQL:**
  - the whole existing suite moves to PostgreSQL without being weakened (D2, D15);
  - H2 is removed afterwards.
- **Database rules (US-2):**
  - NOT NULL on business columns;
  - enum value checks;
  - lower-case emails;
  - project names unique per organization regardless of letter case;
  - a positive seat limit;
  - indexed foreign keys;
  - explicit `ON DELETE RESTRICT`.
- **The unused `audit_log` table:** dropped, or renamed and fixed (D9, Q6).
- **UTC instants (US-4):** `Instant` and `timestamptz`, timestamps in the API with a `Z`, and an injectable service clock (FU-4, Q4, Q5).
- **PostgreSQL behaviour re-verified (FU-8, US-3):**
  - lock timeouts;
  - races;
  - constraint-message leaks;
  - ordering (collation);
  - enum DDL.
- **Input rules:**
  - text PostgreSQL can't store (US-5, the U+0000 part of FU-3);
  - Unicode-blank names (FU-3);
  - code-point lengths (FU-12);
  - non-string JSON for string fields (FU-11, US-6);
  - strict UUIDs in paths (FU-10, US-7).
- **Dev profile:** SQL lines go through the logging system, so they carry request IDs (FU-14, US-8).
- **Docs:**
  - contract `docs/api/auth-service.openapi.yaml` 1.3.0;
  - README sections for local setup and migrations;
  - a reusable **database pattern** for the other services (section "Database pattern for every service");
  - proposed ERD changes, to be applied by pm later (X4).

### Out of scope
- **Production deployment:**
  - Helm/Kubernetes, managed PostgreSQL, backups, point-in-time recovery, connection pooling outside the app (PgBouncer);
  - separate migration and runtime database roles (least privilege), which are recorded in the pattern as "later".
- **Data migration from H2.** H2 never persisted anything, so there's no data to move (A1).
- **Audit-log writing and reading.** That stays in its own spec, `auth-service-audit` (phase-2 D11). Phase 4 only decides what happens to the unused table (D9).
- **New `updated_at` columns** for organizations, projects, memberships or invitations (D8). They're added by the feature that first needs them.
- **Password content rules** (for example control characters in passwords). Passwords are hashed and never stored as text; phase 3 decides password policy.
- **FU-2:** NFC normalisation of emails and names (phase 3).
- **FU-1:** Problems for errors outside Spring MVC. FU-5, FU-13, FU-15, FU-16 and RC-1 to RC-10 also stay out of scope (see "Refactoring candidates").
- **Body UUIDs.** Strictness for UUIDs inside JSON bodies (`userId` in add-member) isn't covered; FU-10 is about path variables (A9).
- **Other services:**
  - any gateway change: the gateway has no database and isn't affected (D18);
  - any code in scenario-service or report-service.
- Pagination, caching, read replicas, full-text search.

## Inputs and where they're handled

| Input | Where in this spec |
|---|---|
| FU-3 (P1): Unicode-blank names, control characters, lone surrogates, extended to project names (phase-2 Q1) | D11, US-5 (control characters, lone surrogates; before the switch), US-6 AC-6.1 (Unicode-blank), E59, E75 |
| FU-4 (P2): injectable `Clock` | D7, E68, AC-4.2, AC-4.3 (Q5) |
| FU-8 (P1): lock exceptions and SQLStates | D10, AC-3.2 |
| FU-8: unique-violation classification (AC-2.11) | AC-3.3 |
| FU-8: emails in constraint messages (FU-6) | D10 (`logServerErrorDetail=false`), AC-3.3 |
| FU-8: U+0000 | US-5 |
| FU-8: enum DDL (`project_memberships.role`, `invitations.role`) | D8, E71, AC-2.4 |
| FU-8: project-name uniqueness under a race; organization lock for project writes | AC-3.4, AC-3.5, E72 |
| FU-8: `archived_at timestamp(6)` | E69 (timestamptz), AC-4.4 |
| FU-10 (P2): strict UUID path variables | D13, US-7, E77 |
| FU-11 (P2): reject non-string JSON for string fields | D12, AC-6.3, E76 |
| FU-12 (P2): name length in code points | D11, AC-6.2, E75 |
| FU-14 (P2): dev `show-sql`; uncorrelated start-up lines | D14, US-8, E78 |
| ERD defect: nullable business columns | D8, E70, AC-2.1 |
| ERD defect: `audit_log.metadata` json, singular table name | D9, E74 |
| ERD defect: `LocalDateTime` / `timestamp(6)` without zone | D6, E69, US-4 |
| ERD defect: enum DDL differs between H2 and PostgreSQL | D8, E63 (one DDL), E71 |
| ERD defect: no FK indexes, no ON DELETE rules | D8, E73, AC-2.6, AC-2.7 |
| ERD defect: `created_at` set by hand; `users.updated_at` never updated; no `organizations.updated_at` | D8 (kept application-set from the service clock, NOT NULL; no new columns) |
| ERD defect: case-sensitive `users.email` uniqueness | D8, E72, AC-2.2 |
| ERD defect: "one pending invitation per organization and email" only in the application | D8 (stays in the application; why a database rule can't express it) |
| Phase-2 D12: unique `(org_id, lower(name))`, NOT NULL `projects.name`, check on `invitations.role`, `archived_at timestamptz` | E72, E70, E71, E69 |
| ERD-D11 (approved): one database per service, PostgreSQL + Flyway | D3, D4, "Database pattern for every service" |

## Code facts verified (HEAD `6fd4434` plus the user's uncommitted edits, read on 2026-10-14)

The working tree has the user's own uncommitted edits in `configuration/JwtProperties.java`, `repository/OrgMembershipRepository.java`, `service/AcceptInvitationResult.java` and `service/impl/AuthServiceImpl.java` (qa ran phase 2's sign-off on them). They're the user's code like everything else. pm can't run git or shell commands, so the facts below come from reading files and the Gradle cache.

### Build, configuration, environment

| Item | Result |
|---|---|
| `build.gradle` | Boot plugin 4.1.0, Java 17. Line 32: `runtimeOnly 'org.postgresql:postgresql'` (**the PostgreSQL driver is already declared**; the BOM manages 42.7.11, which is in the Gradle cache). Line 37: `runtimeOnly 'com.h2database:h2'` (main runtime scope, not test-only). No Flyway, no Testcontainers. Line 26: `spring-boot-starter-aspectj` (E57). |
| Boot 4.1.0 BOM (`spring-boot-dependencies-4.1.0.pom`, Gradle cache) | `flyway.version` 12.4.0 (line 58). `org.flywaydb:flyway-core` (518) and **`org.flywaydb:flyway-database-postgresql`** (563). **`org.springframework.boot:spring-boot-starter-flyway`** (2359), **`spring-boot-starter-flyway-test`** (2364), `spring-boot-flyway` (1699). **`org.springframework.boot:spring-boot-testcontainers`** (2929). `testcontainers.version` 2.0.5 (210), imported as `testcontainers-bom` (3604). `h2.version` 2.4.240, `postgresql.version` 42.7.11. |
| Testcontainers 2.0.5 BOM (Gradle cache) | **`org.testcontainers:testcontainers-postgresql`** (line 243), `testcontainers-junit-jupiter` (133), `testcontainers` (38). Testcontainers 2.x renamed its modules; the 1.x name `org.testcontainers:postgresql` isn't in this BOM. |
| Not in the Gradle cache | The Flyway jars, the POMs of `spring-boot-starter-flyway`, `spring-boot-testcontainers` and `testcontainers-postgresql`. **So their transitive dependencies are unverified.** In particular, it's unknown whether the starter already brings `flyway-database-postgresql`. The first build downloads them, so network access is needed. |
| `application.yaml` | `spring.jpa.database: h2` (11); `ddl-auto: create` (14); `defer-datasource-initialization: true` (15); a commented PostgreSQL dialect line (19). `spring.datasource`: an H2 in-memory URL with `admin`/`admin` and `org.h2.Driver` (21-25). `spring.h2.console.enabled: false` (27-29). Phase-2 keys: `logging.pattern.correlation` (38), `logging.level.org.hibernate.orm.jdbc.error: ERROR` (40, E29), `spring.mvc.log-resolved-exception: false` (9), `app.logging` (47-49). `app.jwtSecret: ${JWT_SECRET}` (43). |
| `application-dev.yaml` | The labelled DEV ONLY JWT secret (2-4) and `spring.jpa.show-sql: true` (7). No datasource. |
| Docker | (main session) The CLI is installed; **the daemon isn't running**. |
| Local PostgreSQL | (main session, corrected 2026-10-14) **Port 5432 is Postgres.app 16**, which currently refuses connections ("trust" authentication dialog failure). All Homebrew PostgreSQL services are stopped (`postgresql@17` 17.11, `@16` 16.15 and `@14` 14.24 are installed). `psql` is available. Tests don't use a local server (Q1 = A); dev uses a `postgres:17` container (D5). |

### Entities, repositories, services

| Item | Result |
|---|---|
| Entities | 7 entities: `User`, `Organization`, `OrgMembership`, `Project`, `ProjectMembership`, `Invitation`, `AuditLog`. Every timestamp is `LocalDateTime` (13 fields; corrected at review point B: **10** timestamp fields and columns). Enums are `@Enumerated(STRING)`. Only `users.email`, `users.mfa_enabled` and the join columns are NOT NULL; every other business column is nullable (ERD). `User.email` has `@Column(unique = true, nullable = false)`. |
| `AuditLog` | `@Table(name = "audit_log")`; `metadata` is a `String` with `columnDefinition = "json"`. No getters, setters or constructors besides the implicit one, so nothing can write or read it. `AuditLogRepository` is used only by `support/AcmeFixture.reset()` (lines 9, 42, 49). |
| `InvitationRepository` | `LocalDateTime now` parameters at lines 26, 29 and 32. |
| Ordering in SQL | `OrgMembershipRepository:19` orders organizations by `organization.name`, then id. `:25` and `ProjectMembershipRepository:18` order members by email. Project lists are sorted in Java (`ProjectServiceImpl:47-49`). **On PostgreSQL, SQL ordering depends on the database collation**; H2 compares Java strings (D3). |
| Time sources | `LocalDateTime.now()` in `AuthServiceImpl:51-52`, `InvitationServiceImpl:80`, `OrganizationServiceImpl:64`, `:108`, `:140`, `ProjectServiceImpl:66`, `:151`, and `ProjectServiceImpl:107`, which truncates to microseconds (the deviation accepted in phase 2). |
| Lock order | Anonymous accept locks the invitation row (`InvitationServiceImpl:78`), then the organization row (`:140`, in `checkFreeSeat`), and then hashes the password while holding both (`:102`, `createAccount`). See RC-11. |
| `AuthServiceImpl.isUniqueViolation` (88-95) | Classifies through Hibernate's `ConstraintKind.UNIQUE` (RC-1). On PostgreSQL that depends on SQLState 23505, which FU-8 re-verifies. |
| `ApiExceptionHandler` | Any `DataIntegrityViolationException` becomes 409 `CONFLICT` plus one WARN line with the class name only (112-117). Lock failures (`LockTimeoutException`, `PessimisticLockException`, `PessimisticLockingFailureException`) become 409 `CONFLICT` plus one WARN line (119-124). A path-variable conversion failure is already 400 `MALFORMED_REQUEST` through `handleExceptionInternal`. |
| Name validation | `@NotBlank @Size(max = 100)` (UTF-16 units) on `CreateOrganizationRequest.name`, `CreateProjectRequest.name`, `RenameProjectRequest.name`, `RegisterRequest.fullName` and `AcceptInvitationRequest.fullName`. Emails: `@Email @Size(max = 254)` on register and invite; login has `@Email` only. Project names are stored trimmed (`ProjectServiceImpl:61`, `:94`); organization names (`OrganizationServiceImpl:66`) and full names (`AuthServiceImpl:49`) are stored as sent. A custom constraint, `dto/request/MaxUtf8Bytes`, already exists as a pattern. |
| Contract 1.2.1 | Timestamps are "ISO-8601 local date-times **without an offset** ... UTC instants come in phase 4" (`auth-service.openapi.yaml:237-239`; schema `LocalDateTime` at 2116-2120). |

### Tests (baseline: 1655 tests, 679 backend-swe and 976 qa, all green at phase-2 acceptance)

| Item | Result |
|---|---|
| H2 database URLs in backend-swe tests | `exception/LockFailureTest:58`, `aop/CommitTimeOutcomeTest:41`, `aop/SlowCallLoggingTest:35` and `:64`, `aop/UnexpectedFailureLoggingTest:41`, `aop/LoggingSwitchedOffTest:33`, `configuration/ResolvedExceptionLoggingTest:42` (all `@TestPropertySource`). `configuration/JwtSecretStartupTest:66` and `configuration/OrganizationPropertiesStartupTest:26` (start-up arguments). The phase-1 rule was "a test with its own context uses its own database URL", because `ddl-auto: create` drops the schema when a context closes. |
| H2 database URL in a qa test | `qa/QaProjectLoggingTest:74` (`@TestPropertySource`); its import is at line 31 and its Javadoc sentence at line 69. |
| `qa/QaSchemaTest` | Reads H2-only catalog views: `INFORMATION_SCHEMA.ENUM_VALUES` (82-83), `TABLE_SCHEMA = 'PUBLIC'` (64, 106, 113), `LISTAGG` (102). It expects H2 type names such as `ENUM` and `TIMESTAMP(6)`. **It can't run on PostgreSQL unchanged.** |
| `@DataJpaTest` (backend-swe) | `repository/ProjectMembershipRepositoryTest`, `InvitationRoleRepositoryTest`, `ProjectRepositoryTest`, `InvitationRepositoryTest`. These rely on Boot replacing the datasource with an embedded one. `InvitationRepositoryTest:76` and `ProjectRepositoryTest:78` query `INFORMATION_SCHEMA.COLUMNS` with `LOWER(...)`, which PostgreSQL also supports. |
| `AuthServiceApplicationTests` | `@SpringBootTest` without the shared meta-annotation. |
| qa tests that conflict with phase-4 changes | `QaProjectNamesTest:150-151`: the rows `"a\u0000b"` and `"\uD800"` expect 201 or 400, never 5xx. On PostgreSQL, U+0000 fails the insert, and a lone surrogate is stored changed. **So US-5 has to land before the switch.** `QaInputEdgeCasesTest:334-344` expects `{"email":123}` to be 400 `VALIDATION_FAILED` with an `email` error, because Jackson coerces 123 to "123" today (FU-11). Six qa files use `LocalDateTime` (the Instant change): `QaSupport` 19, 83, 91, 99; `QaInvitationSecurityTest` 30, 256; `QaFixRoundTest` 37, 144; `QaErrorShapeTest` 23, 155, 160, 161, 162, 172; `QaProjectRoleMatrixTest` 26, 146; `QaProjectIdorTest` 32, 258. |
| qa tests that stay valid | `QaProjectNamesTest:122-133`: leading and trailing control characters are trimmed, so the answer is 409. That's unchanged under D11's stored-value rule. `QaPublicEndpointBypassTest:79` and `configuration/PublicOperationsTest:47` probe `/h2-console`; it stays non-public. `QaProjectAccessCheckTest#upperCaseIdsAreTheSameProject`: upper-case ids stay accepted (D13). |
| Locks in tests | `LockFailureTest:133-156` and `QaFixRoundTest:514-519` hold `SELECT ... FOR UPDATE` from another connection and expect a 409. **On PostgreSQL that only ends if a lock timeout is configured; the default is to wait forever** (D10). `LockFailureTest:211` matches Hibernate's lock SQL on the text `"for update"`. |
| Unit tests built by Mockito `@InjectMocks` | `service/impl/ProjectServiceImplTest:72`, `InvitationServiceImplTest:56`. A new constructor parameter (`Clock`) arrives as `null` unless the test provides it. |
| `LocalDateTime` in backend-swe tests | `support/AcmeFixture` 19, 75, 92, 101; `support/ProjectFixture` 15, 67, 76, 83; `repository/ProjectMembershipRepositoryTest` 17, 28; `InvitationRoleRepositoryTest` 16, 26; `ProjectRepositoryTest` 12, 24, 25; `InvitationRepositoryTest` 16, 29; `controller/InvitationAcceptanceTest` 37, 182, 253, 386, 549; `RevokeCeilingTest` 20, 151; `InvitationsTest` 31, 99, 100 (`LocalDateTime.parse` of API values), 156, 217, 276, 314, 337, 448, 454; `service/impl/ProjectServiceImplTest` 35, 99, 154, 187, 238, 336, 338. |
| Rows deleted by tests | Only children before parents (`AcmeFixture.reset()`, `QaTokenAttackTest:187-204`, `QaProjectAccessCheckTest:139-143`). That's compatible with `ON DELETE RESTRICT`. |

## Decisions (recommended defaults)

Each decision has a recommended default and a reason. The items that change existing code are listed under "Changes to existing code" for the user's approval. Decision numbers are this spec's own. Phase 2's logging decision is cited as **phase-2 D17**.

### D1. Phase scope, slug, size
**Default:** phase 4 is the In scope list above, with slug `auth-service-persistence`.
- 8 stories, 36 ACs.
- Five batches (A to E), each ending at a review point. The riskiest batch, the database switch, comes first.

**Why:** it closes every P1 follow-up (FU-3, FU-8) and the defects routed to phase 4, and sets the pattern before scenario-service is built.
- FU-10, FU-11 and FU-12 are small input rules that share FU-3's regression sweep.
- FU-14 needs the dev configuration that this phase rewrites anyway.

### D2. How tests run against PostgreSQL (Q1)
**Default (option A): Testcontainers.**
- One PostgreSQL 17 container per Gradle test run, started once (a singleton) and shared by every Spring test context.
- It's wired through Boot's `@ServiceConnection`, so no test needs a URL or a password.
- H2 is removed from the build once the suite is green on PostgreSQL (E67).
- **Every** test runs on PostgreSQL; there's no fast H2 path.

| Option | For | Against |
|---|---|---|
| **A. Testcontainers (recommended)** | Hermetic: the same PostgreSQL major and collation on every machine and in future CI. No manual database setup. It's Boot 4.1's supported path (`spring-boot-testcontainers`, `@ServiceConnection`). Other services can copy it as is. | **Docker must be running for every `./gradlew build`** (the daemon isn't running today). The first build downloads the image. It adds a few seconds per build. Two test dependencies (E62). |
| B. The local Homebrew PostgreSQL, with a separate `auth_service_test` database | Works today without Docker. Fast. No new test dependency. | Not hermetic: the build depends on which server version runs on 5432 (three are installed) and on its locale. It needs a one-time manual setup of a role and a database. A misconfigured URL could point tests, which wipe tables, at the dev database. Future CI needs its own PostgreSQL service anyway. |
| C. H2 for fast tests plus PostgreSQL for integration tests (not recommended) | Fast feedback for most tests | Two schemas. Flyway's PostgreSQL migrations can't run on H2 (functional indexes, check constraints and `timestamptz` behave differently), so H2 would keep `ddl-auto: create`, and most tests would run against a schema production never has: the drift phase 4 removes. `QaSchemaTest` could only check one of them. |

**How the suite moves without being weakened** (binding, T3):
1. Record the per-class test counts before T3. After T3, every class has the same count, with 0 skipped tests.
2. No test is deleted or disabled. `qa/QaSchemaTest` keeps its three checks (columns, enum values, constraints), ported to PostgreSQL's catalog under the F1 policy.
3. Tests with their own context keep their own properties; only the H2 URL goes. The phase-1 rule "own context, own database URL" is **retired**. It existed because `ddl-auto: create` dropped the schema when a context closed. With `validate`, no context ever creates or drops schema objects, and fixtures already reset data in `@BeforeEach`.
4. Start-up tests (`JwtSecretStartupTest`, `OrganizationPropertiesStartupTest`, and the new AC-1.x tests) get the test container's connection settings. The new ones use a **scratch database**, so a broken schema never touches the shared one.
5. Concurrency classes (`RegistrationRaceTest`, `QaConcurrencyTest`, `QaFixRoundTest`, and the AC-1.7 and AC-3.x tests) are run 3 times, all green.
6. qa reviews every F1 edit at re-verification.

**Connection budget (risk, verify in T3).** Spring caches up to 32 test contexts, and each Hikari pool opens up to 10 connections. With about 15 distinct contexts, that's more than PostgreSQL's default `max_connections` of 100.
- backend-swe sets the test container's `max_connections` and/or the test pool sizes in the test support code, so no test fails with "too many clients".
- It reports the number of distinct contexts and the peak connection count.
- The pool must stay larger than the highest concurrency a test uses (10 simultaneous requests in the concurrency suites).

**Decided 2026-10-14: A**, so the rest of this paragraph doesn't apply. (Had the user picked B, E62 would have had no build change.) Test support then reads `AUTH_TEST_DB_URL`, `AUTH_TEST_DB_USERNAME` and `AUTH_TEST_DB_PASSWORD`, with labelled test defaults pointing at `auth_service_test`. The README gets the one-time setup, and T3 adds a guard that refuses to run if the database name doesn't end in `_test`.

### D3. PostgreSQL version and database settings (Q2)
**Default:**
- **PostgreSQL 17** in every environment: the Testcontainers image `postgres:17` (major pinned), the local dev server (a `postgres:17` container, see D5), and later production.
- One database per service: database `auth_service`, owned by role `auth_service`, schema `public`.
- Encoding UTF-8. **Locale provider `builtin` with locale `C.UTF-8`** (new in PostgreSQL 17) for the database's collation and character classification.

**Why:**
- The builtin `C.UTF-8` provider exists only from 17.
- `C.UTF-8` sorts by code point on every operating system. That's what H2 does today (Java string order), so no existing list order changes: organizations by name give `"Acme QA"`, `"Zeta QA"`, `"beta QA"` (AC-3.6). It also makes `lower()` use Unicode case mapping, not the OS's locale tables. **Corrected 2026-10-15 (T8):** the builtin `lower()` doesn't match Java's `toLowerCase(Locale.ROOT)` for U+0130 (`lower('İzmir')` is `'izmir'`; Java gives `'i̇zmir'`). ICU's `"und-x-icu"` collation does match Java, so the project-name index uses it (D8 item 6).
- The Debian-based Docker image defaults to `en_US.utf8`, and Homebrew's `initdb` uses the macOS locale. Either way, results and orders would differ between environments.

backend-swe verifies the PostgreSQL 17 syntax (`initdb --locale-provider=builtin --builtin-locale=C.UTF-8`, and `CREATE DATABASE ... LOCALE_PROVIDER builtin BUILTIN_LOCALE 'C.UTF-8' TEMPLATE template0`) and reports `SELECT datlocprovider, datlocale FROM pg_database` for both the test container and the dev database (P20).

**Local server fact (main session, 2026-10-14).** Port 5432 is held by **Postgres.app 16**, not Homebrew. It currently refuses connections (a "trust" authentication dialog failure), and every Homebrew PostgreSQL service is stopped. Tests don't use it (Q1 = A). For dev it can't be used as it is: version 16 has no builtin `C.UTF-8` provider. D5 says how to run the dev server.

### D4. Flyway baseline and migration rules
**Default:**
- **Location and naming:** `auth-service/src/main/resources/db/migration/`, files `V<n>__<snake_case_description>.sql`, with `n` = 1, 2, 3... No repeatable (`R__`) or Java migrations.
- **V1 baseline = the current entities, exactly** (Glossary "Baseline"):
  - `timestamp(6)` columns;
  - nullable business columns as today;
  - enum columns as Hibernate's PostgreSQL DDL makes them (expected: `varchar` plus a check constraint; verify);
  - `audit_log` with `metadata json`.

  backend-swe generates Hibernate's PostgreSQL DDL for the unchanged entities, writes V1 from it with readable constraint names, and puts the column-by-column comparison in the T3 handoff. V1 passes `ddl-auto: validate` against the unchanged entities.
- **One fix migration per E-item** (V2 onwards, in task order), so each one can be approved, reviewed and reverted on its own.
- **`spring.jpa.hibernate.ddl-auto: validate`** in every profile and every test. Hibernate never creates, updates or drops schema objects.
- **Flyway settings:** Boot's defaults (`validate-on-migrate` on, `clean-disabled` on, no `baseline-on-migrate`). Flyway runs at start-up as the service's own role.
- **Append-only (G11):** once a migration has passed a review point, it's never edited; fixes go forward in a new version. AC-1.4 proves Flyway refuses an edited migration.
- **Data:** every database starts empty (A1). The only data a migration could meet is in dev databases created during Batch A, and the user may drop and recreate those. V2's timestamp conversion still states its assumption (UTC) in a SQL comment.
- **Names:**
  - `pk_<table>`;
  - `fk_<table>_<column>`;
  - `uk_<table>_<columns>` for unique constraints and unique indexes;
  - `ix_<table>_<columns>`;
  - `ck_<table>_<column>_<rule>` (for example `ck_org_memberships_role_values`).

  `ddl-auto: validate` doesn't check constraint names. The names serve error messages, later migrations and the ERD.

**Why "V1 = as-built, then fixes" rather than one clean V1** (the user's instruction):
- V1 proves the migration toolchain reproduces exactly what runs today (validate passes before any change).
- Every later difference is an explicit, reviewable step with its own E-item and test.
- It rehearses the fix-forward process that real data will need later.

### D5. Configuration and secrets (Q11)
**Default:**
- **Default profile** (any non-dev environment):
  - `spring.datasource.url: ${AUTH_DB_URL}`, `username: ${AUTH_DB_USERNAME}`, `password: ${AUTH_DB_PASSWORD}`, with no defaults. As with `JWT_SECRET`, a missing value stops start-up with an error naming the setting (AC-1.5).
  - The URL never contains credentials (G12).
- **`dev` profile** (`application-dev.yaml`, E65), labelled `# DEV ONLY` like the JWT secret:
  - `jdbc:postgresql://localhost:5432/auth_service`;
  - user `auth_service`, password `auth_service_dev_only`.

  The dev values are committed, because they only open a database on the developer's own machine.
- **Local dev database (recommended: a Docker container; README, E79).** Docker Desktop has to run for the tests anyway, so the simplest dev server is a `postgres:17` container. One-time setup:
  1. **Quit Postgres.app** (menu bar, Quit) and turn off "Start automatically", so that port 5432 is free. Postgres.app 16 can't serve dev: there's no builtin `C.UTF-8` before 17.
  2. Run:

     ```
     docker run -d --name cucumber-postgres --restart unless-stopped -p 5432:5432 \
       -e POSTGRES_USER=auth_service -e POSTGRES_PASSWORD=auth_service_dev_only -e POSTGRES_DB=auth_service \
       -e POSTGRES_INITDB_ARGS="--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8" \
       -v cucumber-postgres-data:/var/lib/postgresql/data postgres:17
     ```

  3. Data survives restarts in the `cucumber-postgres-data` volume.
  4. Later services add their own role and database to the same server (pattern item 5), for example with `docker exec -it cucumber-postgres psql -U auth_service -d auth_service`.

  **Alternative:** Postgres.app with a PostgreSQL 17 server, after fixing its authentication (Postgres.app settings, or `pg_hba.conf`), then `CREATE ROLE` and `CREATE DATABASE` as in pattern item 3. backend-swe documents both in the README; the container comes first.
- **Tests:** the container supplies the connection (D2). No credentials appear in test code, except the existing test-only JWT secret.
- **Never logged:** the database password, at any level (AC-1.6). Flyway's start-up line shows the URL, which has no credentials.

**Why:** it's the same pattern as `JWT_SECRET` (phase 1), which the user approved. Failing fast beats starting with a wrong default. Environment variable names are prefixed by service, so one shell can hold several services' settings.

### D6. Timestamps: `Instant`, `timestamptz`, UTC in the API (Q4)
**Default:**
- Every entity timestamp becomes `java.time.Instant`, and every column `timestamptz` (V2, E69). Values are converted assuming UTC (D4).
- Response DTOs carry `Instant`. JSON shows a **UTC timestamp**, for example `2026-10-14T09:30:00.123456Z` (Jackson's ISO-8601 output; backend-swe verifies the exact Boot 4.1 / Jackson 3 format, P20).
- Contract **1.3.0**:
  - the `LocalDateTime` schema becomes a `Timestamp` schema (`type: string`, `format: date-time`, the pattern above);
  - C4's timestamp bullet is replaced;
  - a change note says: "Timestamps now carry a `Z` offset. Clients that parsed offset-less strings must accept an offset. No client existed at the time of the change."
- No request body accepts a timestamp. Mass-assigned timestamps stay ignored (qa's `QaProjectIdorTest:238-260` and `QaInputEdgeCasesTest:205-217`).

**Why:**
- A local date-time without an offset is ambiguous as soon as two machines disagree on the time zone.
- `timestamptz` plus `Instant` is the standard for PostgreSQL with JPA.
- The contract already announced "UTC instants come in phase 4".
- There's no frontend or consumer yet, so changing the format now costs nothing. Later it would be breaking.

**Alternatives:**
- **I-B:** `Instant` and `timestamptz` internally, but the API keeps offset-less local strings in the server's zone. That's extra mapping code and keeps the ambiguity forever.
- **I-C:** defer. `LocalDateTime` stays, and scenario-service copies the old pattern.

**Precision:** PostgreSQL stores microseconds. The service clock ticks in microseconds (D7), so a value returned before a commit equals the value read back. This replaces `ProjectServiceImpl:107`'s special-case truncation.

### D7. The service clock (FU-4, Q5)
**Default:**
- One `java.time.Clock` bean: UTC, ticking in microseconds (`Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000))`), defined in a new configuration class (E68).
- Every service that needs "now" injects it and calls `Instant.now(clock)`. `LocalDateTime.now()` disappears from `src/main`.
- Tests may replace the bean with a fixed or adjustable clock (AC-4.2, AC-4.3).
- Existing tests keep seeding past and future timestamps the way they do; they aren't rewritten (G4).

**Why:**
- Q4 rewrites every `now()` line anyway, so adding the clock costs one constructor field per service.
- It makes expiry testable to the microsecond (AC-4.3) and the time zone irrelevant (AC-4.2).
- It removes the truncation special case.
- FU-4 was "not approved" in phase 1 only because it was a refactor on its own. Here it rides on a change that already happens.

**If not approved:** E69 uses `Instant.now()`, AC-4.3 is dropped, and AC-4.2 checks "within 5 s of the test's UTC time" instead of an exact value. The truncation at `ProjectServiceImpl:107` stays, as `Instant.now().truncatedTo(MICROS)`.

### D8. Database rules (constraints, indexes, delete rules)
**Default:**

1. **NOT NULL (E70)** on every business column that the application always sets:
   - `users`: `full_name`, `status`, `created_at`, `updated_at`;
   - `organizations`: `name`, `plan`, `created_at`;
   - `org_memberships`: `role`, `created_at`;
   - `projects`: `name`, `created_at`;
   - `project_memberships`: `role`, `created_at`;
   - `invitations`: `email`, `role`, `token`, `status`, `expires_at`, `created_at`.

   These stay nullable, with the reason:
   - `users.password_hash`: explicitly nullable in the entity, kept for future accounts without a password, for example SSO;
   - `organizations.seat_limit`: null means unlimited;
   - `projects.archived_at`: null means active.
2. **Lengths match the API (E70):**
   - `users.email` and `invitations.email` become `varchar(254)`;
   - `users.full_name`, `organizations.name` and `projects.name` become `varchar(100)`. PostgreSQL counts characters (code points), like FU-12.
   - Other `varchar(255)` columns stay.
   - The entities mirror these with `@Column(nullable = false, length = ...)`, so a reader of the entity sees the rule. Hibernate's validation doesn't check them.
3. **Enum columns (E71): `varchar(32)` plus a named check constraint**, not PostgreSQL `ENUM` types:
   - `users.status`, `organizations.plan`, `org_memberships.role`, `project_memberships.role`, `invitations.role`, `invitations.status`;
   - values exactly as in the Java enums.

   **Why:** a value list in a check constraint is changed by an ordinary migration. A PostgreSQL enum can't drop a value without recreating the type, and it needs a dedicated Hibernate mapping on every field. `@Enumerated(STRING)` stays as it is. If V1 already contains Hibernate's checks, E71 only narrows the type to `varchar(32)` (and renames the checks if V1 couldn't name them).
4. **Positive seat limit (E70):** `ck_organizations_seat_limit_positive` (`seat_limit IS NULL OR seat_limit > 0`). It mirrors the `@Positive` setting `app.orgs.free-seat-limit`. No test seeds 0 (grep).
5. **Emails (E72): a check that stored emails are lower case** (`email = lower(email)`) on `users` and `invitations`. The existing unique constraint on `users.email` stays.
   - **Why:** the application stores normalised emails (phase-1 D12), and the check makes the database guarantee it. Exact uniqueness of lower-case values is then case-insensitive uniqueness, and lookups (`findByEmail`, `email = ?`) keep using the existing unique index.
   - **Alternative:** a unique index on `lower(email)`. That's a second index on the same column, and `email = ?` lookups can't use it.
   - **Known edge:** if PostgreSQL's `lower()` (Unicode simple case mapping) ever disagreed with Java's `toLowerCase(Locale.ROOT)` for an exotic character, that registration would get 409 `CONFLICT` instead of 201. The application rule is unchanged (A10).
6. **Project names (E72): unique index `uk_projects_org_id_name_lower` on `(org_id, lower(name COLLATE "und-x-icu"))`**, archived projects included (phase-2 D5). Decided 2026-10-15: ICU's `lower()` matches Java for U+0130, and the builtin one doesn't (D3). The main session verified it in `postgres:17` (`lower('İzmir' COLLATE "und-x-icu")` = `'i̇zmir'`, 6 characters; 871 ICU collations in the image).
   - The application check under the organization lock stays the primary rule and keeps answering `PROJECT_NAME_TAKEN`.
   - The index is the safety net, and its leading `org_id` also indexes the foreign key.
7. **"One pending invitation per organization and email" stays in the application only.**
   - **Why:** "pending" means `status = 'PENDING'` **and** `expires_at > now()`. An expired invitation keeps the stored status `PENDING`, and a re-invite is allowed (phase-1 D11).
   - A partial unique index can't use `now()` (not immutable), and one on `status = 'PENDING'` alone would block legitimate re-invites.
8. **Foreign-key indexes (E73):** each foreign-key column is the leading column of some index:
   - new: `ix_org_memberships_org_id`, `ix_project_memberships_project_id`, `ix_invitations_org_id_email` (also serves the pending-invitation lookups), `ix_invitations_invited_by`;
   - already covered by a unique constraint: `org_memberships.user_id`, `project_memberships.user_id`;
   - covered by item 6: `projects.org_id`.
9. **Delete rules (E73):** every foreign key is recreated with its D4 name and an explicit **`ON DELETE RESTRICT`**.
   - **Why:** no operation hard-deletes users, organizations or projects. Memberships and invitations are deleted directly, and the test fixtures delete children first.
   - A cascade would silently remove history once audit exists, so a deletion must be designed together with the feature that introduces it.
10. **`created_at` / `updated_at`:**
    - They stay **set by the application, from the service clock**, in the places that set them today. Plus NOT NULL (item 1).
    - **No** database default `now()`: that would be a second time source and would hide a missing value. **No** JPA auditing: it would overwrite the timestamps qa seeds on purpose (`QaSupport:91`).
    - `users.updated_at` keeps meaning "set at creation"; no operation changes a user yet (phase 3 will).
    - No `updated_at` columns are added. The ERD's convention line changes to "where an operation needs it".

### D9. `audit_log` (Q6)
**Default (option B): drop it.**
- V7 drops the table; `model/AuditLog.java` and `repository/AuditLogRepository.java` are deleted.
- `support/AcmeFixture` loses the three lines that use the repository (E74).
- The `auth-service-audit` spec creates `audit_logs` (plural) with `metadata jsonb` and a tested mapping, in its own migration, together with the code that writes and reads it.

**Why:**
- The entity has no accessors, no writer and no reader.
- Its `json` mapping fails on PostgreSQL, and fixing it properly depends on the Hibernate 7 / Jackson 3 JSON mapping, which only a real writer can prove.
- Keeping an unusable table means carrying a defect, a singular name and four foreign keys with no benefit.
- Flyway makes adding a table later trivial.

**Alternative (A):** V7 renames the table to `audit_logs`, makes `metadata` `jsonb`, and maps the field with `@JdbcTypeCode(SqlTypes.JSON)`. Only `validate` and a load test can prove it, because there are no accessors. The write path stays unproven until the audit spec.

### D10. Locks, deadlocks and database errors on PostgreSQL
**Default:**
- **Lock timeout:** every connection waits at most **5 s** for a row lock (PostgreSQL `lock_timeout`).
  - It's one configured value in `application.yaml` (E64); backend-swe verifies and reports the mechanism (P20).
  - It must cover Hibernate's `SELECT ... FOR UPDATE` (or `FOR NO KEY UPDATE`) issued by the repository locks.
  - Without it, a held lock blocks a request forever, and `LockFailureTest` and `QaFixRoundTest` would hang.
  - A timeout (SQLState 55P03) and a deadlock (40P01) must reach the existing handler as a lock failure: 409 `CONFLICT`, one WARN, no stack trace (AC-3.2).
- **No data in database error messages:** the PostgreSQL JDBC property **`logServerErrorDetail=false`** (E64; backend-swe verifies the property name in driver 42.7.11).
  - PostgreSQL's unique-violation `DETAIL` (`Key (email)=(...) already exists`) otherwise ends up in exception messages, and a logged message would leak the email (phase-2 D17 item 4, FU-6).
  - AC-3.3 proves it.
- **Read-only transactions are enforced on PostgreSQL** (H2 ignores them). The full suite on PostgreSQL detects a write inside `@Transactional(readOnly = true)`; any failure of this kind is reported, not worked around.
- **A failed statement aborts the transaction on PostgreSQL.** No service may catch a database exception and keep using the same transaction (PL). Today's code only re-throws (`AuthServiceImpl:57-61`).

**Tests only (E82, 2026-10-15):** the shared test database uses 1 s to shorten the build. Production and dev stay at 5 s, and a test checks the `application.yaml` value.

**Why 5 s:** the longest lock hold in the concurrency suites is a few hundred milliseconds of BCrypt inside the accept flow (RC-11). Ten queued requests fit within 5 s, and a stuck lock still answers quickly.
**Stop condition (T3):** if any concurrency test gets a 409 `CONFLICT` from a lock timeout, stop. Report the observed waits and ask whether to raise the value.

### D11. Text rules: what PostgreSQL can't store, and names as users see them (Q7)
**Default:**
- **Rule T1, unstorable text (US-5, E59, before the switch):** a **stored value** (Glossary) of a name or email that contains a **control character** or a **lone surrogate** is 400 `VALIDATION_FAILED`, with an `errors` entry for the field. It applies to:
  - organization name;
  - project name (create and rename);
  - full name (register, anonymous accept);
  - email (register, login, invite).

  Consequences:
  - Project names are checked after trimming: `"\u0000Checkout"` trims to `"Checkout"`, which is then 409 `PROJECT_NAME_TAKEN`, as `QaProjectNamesTest:122-133` already expects.
  - Organization names and full names are stored as sent, so a leading tab is 400.
- **Rule T2, Unicode-blank names (US-6, E75):** a **Unicode-blank** organization name, project name or full name is 400 `VALIDATION_FAILED`. `@NotBlank` already covers ASCII and Java whitespace.
- **Rule T3, length in code points (FU-12, E75):** the 1-100 name limits count **code points**. Project names are still counted on the name as sent, before trimming (phase-2 D5 Q3). Email limits (254) and the password byte rule are unchanged.
- **Messages never contain the value** (phase-2 D17 item 4), for example "Name must not contain control characters".
- **Emails:** T1 applies on top of `@Email`, because Hibernate Validator's `@Email` accepts the code points U+0080 to U+FFFF in the local part, which includes C1 controls and lone surrogates.

**Why this refines pm's 2026-10-14 wording** ("a control character ... anywhere in the name"):
- Checking the **stored value** gives one principle, "no stored name contains a control character", that matches phase-2 D5's trimming.
- It keeps `"\tCheckout"` a duplicate of "Checkout" (409), so qa's 5 rows at `QaProjectNamesTest:123` stay valid.
- C1 controls (U+0080-U+009F, including NEL) are added because they're as invisible as C0 controls, and `Character.isISOControl` is the standard definition.

**Alternative ("anywhere", Q7):** reject a control character anywhere in the name as sent. Then `"\tCheckout"`, `"Checkout\n"`, `"\r\nCHECKOUT\t "`, `"\u0000Checkout"` and `"Checkout\u001F"` become 400, and qa's lines 122-133 must change (one more F1 item).

### D12. Strict JSON types for strings (FU-11, Q8)
**Default:**
- A JSON number or boolean where the contract says `string` is 400 `MALFORMED_REQUEST`, as arrays and objects already are, and as numbers for enums are (phase-1 E24).
- `null` is unchanged: it reaches validation and gives `VALIDATION_FAILED` (AC-6.4).
- It's one global Jackson coercion setting (E76). backend-swe verifies which Boot 4.1 / Jackson 3 setting rejects number-to-string and boolean-to-string coercion, without changing anything else (P20).

**Why:** the type error is in the JSON itself, so it's the same class of error as `{"name":["a"]}`. A configuration change is the smallest fix, and it covers every string field, including future ones.
**Consequence:** `qa/QaInputEdgeCasesTest:334-344` (`{"email":123}` is 400 `VALIDATION_FAILED` with an `email` error) changes to `MALFORMED_REQUEST` (F1 item e).
**Alternative:** answer `VALIDATION_FAILED` with an `errors` entry for the field. That needs custom mapping code, but no qa change.

### D13. Strict UUIDs in path variables (FU-10)
**Default:**
- Every UUID path variable (`orgId`, `projectId`, `userId`, `invitationId`) must be a **canonical UUID**. Anything else is 400 `MALFORMED_REQUEST`, before authorization, as `not-a-uuid` already is.
- Upper-case hex digits stay accepted (RFC 9562; qa's `upperCaseIdsAreTheSameProject`).
- It's one strict converter registered in web configuration (E77); controllers and guards don't change.

**Why:** BUG-1 showed that `1-1-1-1-1` and short groups parse to other ids, so one project has many valid id strings. That has to be fixed before the first C5 consumer goes to production (FU-10 gate), and this phase touches input validation anyway.

### D14. Logging (phase-2 D17 carries over; FU-14)
**Default:**
- **Phase-2 D17 is binding for all phase-4 code:**
  - no personal data or secrets in any log line;
  - the aspect never logs arguments, return values or messages;
  - every line carries `requestId` and `userId`;
  - existing log statements and levels are unchanged.
- **New for PostgreSQL:**
  - the database password is never logged;
  - JDBC URLs carry no credentials;
  - `logServerErrorDetail=false` (D10);
  - `org.hibernate.orm.jdbc.bind` (bound values) is never enabled in any profile.
- **FU-14 (E78):** `application-dev.yaml` replaces `spring.jpa.show-sql: true` with `logging.level.org.hibernate.SQL: DEBUG`, so SQL lines go through the logging system with the pattern, `requestId` and `userId` (AC-8.1). They show statements with `?` placeholders only.
- **Start-up lines** (Flyway's migration lines, Hikari's pool lines) are expected at INFO. They must contain no password or secret (AC-8.2). The `DispatcherServlet` lazy-initialisation lines without a request ID stay accepted (phase-2 Q4).
- **The pattern is reused by other services** (phase-2 D17 item 9 plus "Database pattern for every service").

### D15. Testing approach
**Default:** phase-2 D15 applies:
- full-context MockMvc tests against the real security chain;
- `@Tag("AC-x.y")` on every AC test;
- API tests aren't `@Transactional`;
- shared fixtures in `support/`.

Additions:
- **Test first (P15)** for every behaviour change: E59, E60, E68-E73, E75-E78. Write the test, show it failing against the unchanged code, then change the code. For a migration item, the test is the direct-SQL check (US-2), which fails before the migration exists.
- **The switch (T3) is proven by the existing suite:** the same per-class counts before and after, 0 skipped (D2). New tests are added only for new behaviour.
- **Schema checks:**
  - qa's `QaSchemaTest` (ported, F1) keeps pinning columns, enum values and constraints against the as-built ERD;
  - backend-swe adds its own schema test for what the port doesn't cover: indexes, check constraints, delete rules, `timestamptz` (AC-2.7, AC-4.4).
- **Start-up tests** (AC-1.1 to AC-1.6) use scratch databases (D2 item 4).
- **Concurrency tests** run at least 5 times inside the test, and the class is green 3 runs in a row.
- **Manual checks** (AC-1.7, AC-8.2, and the gateway flow in T14) are recorded in the handoff.

### D16. Dependencies and build (Q3)
**Default:** these build changes, each with no explicit version (managed by the Boot 4.1.0 plugin and BOM, verified above):

| Item | Change | Why |
|---|---|---|
| E61 | `implementation 'org.springframework.boot:spring-boot-starter-flyway'`, and `runtimeOnly 'org.flywaydb:flyway-database-postgresql'` | Boot 4.1's Flyway starter. Since Flyway 10, PostgreSQL support is a separate module (`flyway-database-postgresql` 12.4.0 is in the BOM). **If the downloaded starter already brings it, the second line isn't added** (report `./gradlew dependencies`). |
| E61b (only if needed) | `testImplementation 'org.springframework.boot:spring-boot-starter-flyway-test'` | Boot 4.1 splits test support by module. Add it only if the four `@DataJpaTest` classes don't run Flyway without it (report what was verified). |
| E62 (only with Q1 = A) | `testImplementation 'org.springframework.boot:spring-boot-testcontainers'`, `testImplementation 'org.testcontainers:testcontainers-postgresql'` | `@ServiceConnection` support and the PostgreSQL container (Testcontainers 2.0.5 artifact name verified in its BOM). `testcontainers-junit-jupiter` isn't needed for a singleton container; add it only if verification shows otherwise, and report. |
| E67 | Delete `runtimeOnly 'com.h2database:h2'` (line 37) | Unused after the switch. |

No other dependency, no version change, no Gradle plugin change. The existing PostgreSQL driver line (32) stays.

### D17. Contract and docs
**Default:**
- Contract **1.3.0**:
  - UTC timestamps (D6);
  - the D11 name and email rules, and code-point lengths;
  - `MALFORMED_REQUEST` for non-string values (D12);
  - canonical UUIDs (D13);
  - no change to paths, statuses of existing rows, or error codes.
- It's updated contract-first in the task that changes the behaviour, and synced in T14 (E80).
- README (E79): "Running locally with PostgreSQL" (the one-time setup, environment variables, `bootRun` with `dev`, Docker for tests) and "Database and migrations" (D4's rules).

### D18. Gateway and other services
**Default:**
- **The gateway is unaffected.** It has no database, its routes don't change, and response bodies pass through unchanged. The only visible difference, a `Z` on timestamps, is in auth-service's body. No file under `api-gateway/` changes (G10).
- **scenario-service and report-service:** no work in this spec. Their first specs adopt "Database pattern for every service" by reference, including the test approach chosen in Q1.

**Coordination with the scenario-service pm, who is writing in parallel** (handoff). Since 2026-10-14, the exact shared rules are in "Database pattern for every service" (items 1–19), and scenario-service aligns with them:
- enum storage (D8 item 3) versus the ERD's DBML enums for proposed tables;
- `jsonb` rather than `json` for `steps.data_table`, `examples.header` and the other JSON columns;
- `timestamptz`, already in the ERD;
- collation (D3).

## Database pattern for every service (reusable)

**Approved by the user on 2026-10-14** (with Q1 to Q11, and ERD-D12 and ERD-D13). scenario-service and report-service adopt it by reference in their first specs, and auth-service phase 4 is the reference implementation. Each service keeps its own copy of the configuration and test support, because there's no shared library (phase-2 G9). A service spec may deviate only through a decision that names the item below.

In the table, `<svc>` is the service name in snake case (`auth_service`, `scenario_service`, `report_service`) and `<SVC>` is the same in upper case (`AUTH`, `SCENARIO`, `REPORT`).

| # | Convention | Exact rule |
|---|---|---|
| 1 | Server | PostgreSQL **17** everywhere: the Testcontainers image `postgres:17`, local dev, and later production. |
| 2 | Database per service | Database `<svc>`, owned by role `<svc>`, schema `public`. No cross-service foreign keys: another service's ids are plain `uuid` columns with a note "references `<service>.<table>.id` (by ID, no FK)". |
| 3 | Encoding and collation | UTF-8; locale provider `builtin`, locale **`C.UTF-8`** (ERD-D13). In a container: `POSTGRES_INITDB_ARGS="--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8"`. In SQL: `CREATE DATABASE <svc> OWNER <svc> TEMPLATE template0 ENCODING 'UTF8' LOCALE_PROVIDER builtin BUILTIN_LOCALE 'C.UTF-8'`. Lists then sort by code point on every OS. |
| 4 | Environment variables | `<SVC>_DB_URL`, `<SVC>_DB_USERNAME`, `<SVC>_DB_PASSWORD` (for example `AUTH_DB_URL`, `SCENARIO_DB_URL`), bound in `application.yaml` as `${<SVC>_DB_URL}` and so on, with **no default** outside `dev`. A missing value stops start-up with an error naming it. URLs never contain credentials. |
| 5 | Dev profile | `application-dev.yaml` has `jdbc:postgresql://localhost:5432/<svc>`, user `<svc>`, password `<svc>_dev_only`, under a `# DEV ONLY` comment. All services' dev databases live on the one local dev server (section "Local dev database" in D5). |
| 6 | Migrations | Flyway only. Location `src/main/resources/db/migration/`; files `V<n>__<snake_case_description>.sql`, with `n` = 1, 2, 3...; no `R__` or Java migrations. One file per approved change. Append-only once reviewed (G11). Boot's Flyway defaults (validate-on-migrate, clean disabled, no baseline-on-migrate). No `IF [NOT] EXISTS`. |
| 7 | Hibernate | `spring.jpa.hibernate.ddl-auto: validate` in every profile and test. Entities mirror NOT NULL and lengths with `@Column(nullable = false, length = n)`. |
| 8 | Names | `pk_<table>`, `fk_<table>_<column>`, `uk_<table>_<columns>` (unique constraints and unique indexes), `ix_<table>_<columns>`, `ck_<table>_<column>_<rule>`. |
| 9 | Types | Ids: `uuid`, generated by the application. Timestamps: `timestamptz` mapped to `java.time.Instant`. Text: `varchar(n)` with `n` = the API limit (characters), or `text`. **Enums: `varchar(32)` + CHECK `ck_<table>_<column>_values`** listing the Java constants, mapped with `@Enumerated(EnumType.STRING)`, never PostgreSQL `ENUM` types (ERD-D12). **JSON: `jsonb`**, never `json`. |
| 10 | Constraints | NOT NULL for everything the application always sets. Every foreign-key column leads some index. Foreign keys are `ON DELETE RESTRICT` unless the owning spec designs a cascade. Case-insensitive uniqueness: either stored lower-case plus `CHECK (x = lower(x))`, or a `lower()` unique index when the stored value keeps its case. |
| 11 | Time | One `java.time.Clock` bean: UTC, microsecond tick (`Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000))`). Services call `Instant.now(clock)`; there's no `LocalDateTime` in main code. `created_at` and `updated_at` are set by the application from the clock (no database default, no JPA auditing). The API shows UTC timestamps with `Z`. |
| 12 | Locks and errors | Every connection has PostgreSQL `lock_timeout` = **5 s**. A timeout (55P03) or deadlock (40P01) becomes 409 `CONFLICT`. The JDBC property **`logServerErrorDetail=false`** is set. No code catches a database exception and continues in the same transaction. |
| 13 | Logging | Phase-2 D17 plus D14: in dev, SQL only through `org.hibernate.SQL` at DEBUG. `org.hibernate.orm.jdbc.bind` is never enabled, and no password is logged. |
| 14 | Tests: database | Every test runs on PostgreSQL, with no H2 on the classpath. One **Testcontainers singleton** `postgres:17` container per Gradle test JVM, with the initdb arguments from item 3, wired by Boot's `@ServiceConnection` (`spring-boot-testcontainers`, `org.testcontainers:testcontainers-postgresql`, versions from the Boot 4.1 BOM) through the service's shared test meta-annotation. A missing Docker daemon fails the build; it's never skipped. |
| 15 | Tests: isolation | All Spring contexts share the one test database. Fixtures reset data in `@BeforeEach`, children first. No test creates or drops schema objects there. Start-up and drift tests use **scratch databases** they create and drop. |
| 16 | Tests: connection budget | The container's `max_connections` and/or each test context's Hikari pool must keep the sum over all cached contexts below the server limit. Each pool must stay larger than the highest concurrency a test uses. Report distinct contexts and peak connections. Never use `@DirtiesContext` as a workaround. Starting values (auth-service T3 confirms or adjusts them): container `max_connections=300`, test pool `maximum-pool-size=12`, `minimum-idle=1`. |
| 17 | Tests: schema | One schema test pinned to the ERD (columns, check values, unique constraints and indexes, foreign keys and their delete rules, `timestamptz`), plus direct-SQL tests for every constraint (P17). |
| 18 | Text input | D11 (no control characters or lone surrogates in stored text, Unicode-blank names rejected, code-point lengths) and D12 (numbers and booleans aren't strings). |
| 19 | Later (not now) | Separate migration and runtime roles; backups; pooling outside the app; statement timeouts. |

**As proven by the reference implementation (accepted 2026-10-15).** These refinements apply to every service that adopts the pattern. They take precedence over the rows above where the two differ:
- **Item 4:**
  - the settings check runs after binding and before the `DataSource` is created (E81, E83: a plain properties record, a separate settings check, and an `AbstractDependsOnBeanFactoryPostProcessor`);
  - the check rejects a blank value, or a value that is entirely one unresolved `${NAME}`;
  - no value is ever printed;
  - **database passwords must not contain `${`** (FU-24).
- **Item 10:** when the database lower-cases for a uniqueness rule, it must agree with Java's `toLowerCase(Locale.ROOT)`. Use `lower(x COLLATE "und-x-icu")`, because the builtin `C.UTF-8` `lower()` differs for U+0130. Unicode 14+ characters still differ (FU-17).
- **Item 12:** tests may use a shorter `lock_timeout` (auth-service uses 1 s, E82), as long as a test proves that the production value in `application.yaml` is 5 s.
- **Items 14 and 16:**
  - the container from `@ServiceConnection` stops when the importing context closes, so no `@DirtiesContext` and keep cached contexts well under 32 (P21a);
  - the test support's `@DynamicPropertySource` also sets `<SVC>_DB_URL`, `<SVC>_DB_USERNAME` and `<SVC>_DB_PASSWORD` from the container, so the settings check runs in tests;
  - the starting values in item 16 (`max_connections=300`, pool 12, minimum idle 1) were confirmed.

## User stories

**Test setup used throughout:**
- Unless a scenario says otherwise, the service runs against the test database at the latest migration, with the Projects fixture (phase 2) seeded in `@BeforeEach`.
- Bodies show JSON. `\u0000` and similar are **JSON escapes in the request body**, which Jackson decodes into the code point.
- "Nothing was stored" means: no user, organization, membership, project, project membership or invitation was created or changed.

### US-1: auth-service runs on PostgreSQL with a Flyway-managed schema (P0)
As the operator of auth-service, I want its data in PostgreSQL under a versioned schema, so that data survives restarts and every schema change is reviewed and reproducible.

```gherkin
Feature: PostgreSQL with a Flyway-managed schema

  @US-1 @AC-1.1 @P0
  Scenario: A fresh database is migrated at start-up
    Given a scratch database
    When auth-service starts with that database's settings
    Then start-up succeeds
    And the migration history lists every migration from version 1 to the latest, in version order, each marked successful
    And the database's tables are exactly those in "Proposed auth_service ERD after phase 4", plus "flyway_schema_history"
    And registering "fay@example.com" with full name "Fay Lowe" and password "Secret123!" returns 201

  @US-1 @AC-1.2 @P0
  Scenario: Data survives a restart
    Given auth-service has started on a scratch database
    And Fay has registered with "fay@example.com" and "Secret123!" and created the organization "Fay Org QA"
    When auth-service stops and starts again on the same database
    Then the migration history is unchanged
    And Fay's login with "Secret123!" returns 200
    And "GET /api/orgs" by Fay lists exactly "Fay Org QA"

  @US-1 @AC-1.3 @P1
  Scenario Outline: The service refuses to start on a schema that doesn't match its entities
    Given a scratch database migrated to the latest version
    And <drift>
    When auth-service starts
    Then start-up fails before any request is accepted, and the failure names "<object>"
    And the migration history and every table are unchanged by the attempt

    Examples:
      | drift                                              | object              |
      | the column "projects.archived_at" has been dropped | archived_at         |
      | the table "project_memberships" has been dropped   | project_memberships |

  @US-1 @AC-1.4 @P1
  Scenario: A migration edited after it was applied stops the start-up
    Given a scratch database migrated to the latest version
    And its migration history records a checksum for version 1 that differs from the V1 file
    When auth-service starts
    Then start-up fails with a migration validation error that names version 1
    And no table and no migration history row has changed

  @US-1 @AC-1.5 @P1
  Scenario Outline: Without database settings, the service refuses to start
    Given the "dev" profile is not active
    And the setting "<setting>" is not set
    When auth-service starts
    Then start-up fails, and the failure names "<setting>"

    Examples:
      | setting          |
      | AUTH_DB_URL      |
      | AUTH_DB_USERNAME |
      | AUTH_DB_PASSWORD |

  @US-1 @AC-1.6 @P1
  Scenario: A wrong database password stops the start-up without being printed
    Given the "dev" profile is not active
    And AUTH_DB_URL and AUTH_DB_USERNAME point at a scratch database
    And AUTH_DB_PASSWORD is "Wrong-Pw-QA-61"
    When auth-service starts
    Then start-up fails
    And no captured log line and no message in the failure's cause chain contains "Wrong-Pw-QA-61"

  @US-1 @AC-1.7 @P1
  Scenario: The dev profile runs on the local PostgreSQL (manual)
    Given the local database "auth_service" exists, set up as the README describes
    And no AUTH_DB_* variable is set
    When auth-service starts with the "dev" profile
    Then the log shows Flyway applying the migrations (first start) or none (later starts)
    And the phase-2 manual flow (register, log in, create an organization and a project) succeeds
    And after a restart with the "dev" profile, the same user can log in and sees the same organization and project
```

**Notes for testers.**
- AC-1.1's table list is the final one. While Batch A to C are in progress, the test asserts the tables that exist after the latest migration at that point (V1 still has `audit_log`). T10 updates the list in backend-swe's own test.
- AC-1.3 to AC-1.6 start auth-service the way `JwtSecretStartupTest` does (`SpringApplicationBuilder`, port 0). AC-1.5 uses unresolvable placeholders, so a developer's real environment variables can't make it pass by accident.

### US-2: The database enforces the data rules (P1)
As the code owner, I want the database to reject data that breaks auth-service's rules, so that a bug or a manual fix can't store inconsistent data.

**Setup:** the Projects fixture. Every "direct SQL" statement runs in its own transaction, which is rolled back after the check. The expected SQLStates are PostgreSQL's: 23502 not-null violation, 23505 unique violation, 23514 check violation, 23503 foreign-key violation.

```gherkin
Feature: Database rules

  Background:
    Given the Projects fixture

  @US-2 @AC-2.1 @P1
  Scenario Outline: Required columns reject a missing value
    When direct SQL sets "<column>" to NULL on <row>
    Then the database rejects it with SQLState 23502

    Examples:
      | column                         | row                                    |
      | users.full_name                | Ana's row                              |
      | users.status                   | Ana's row                              |
      | users.created_at               | Ana's row                              |
      | users.updated_at               | Ana's row                              |
      | organizations.name             | Acme's row                             |
      | organizations.plan             | Acme's row                             |
      | organizations.created_at       | Acme's row                             |
      | org_memberships.role           | Cai's Acme membership                  |
      | org_memberships.created_at     | Cai's Acme membership                  |
      | projects.name                  | Checkout's row                         |
      | projects.created_at            | Checkout's row                         |
      | project_memberships.role       | Hal's Checkout membership              |
      | project_memberships.created_at | Hal's Checkout membership              |
      | invitations.email              | Ben's pending invitation of Fay to Acme |
      | invitations.role               | Ben's pending invitation of Fay to Acme |
      | invitations.token              | Ben's pending invitation of Fay to Acme |
      | invitations.status             | Ben's pending invitation of Fay to Acme |
      | invitations.expires_at         | Ben's pending invitation of Fay to Acme |
      | invitations.created_at         | Ben's pending invitation of Fay to Acme |

  @US-2 @AC-2.2 @P1
  Scenario Outline: Stored emails are lower case, so uniqueness ignores letter case
    When direct SQL <statement>
    Then the database rejects it with SQLState <state>

    Examples:
      | statement                                                               | state |
      | sets Ana's email to "Ana@example.com"                                   | 23514 |
      | inserts a user with email "ANA@EXAMPLE.COM" and every other column valid | 23514 |
      | inserts a user with email "ana@example.com" and every other column valid | 23505 |
      | sets the email of Ben's pending invitation of Fay to "Fay@Example.com"  | 23514 |

  @US-2 @AC-2.3 @P1
  Scenario Outline: Project names are unique per organization, ignoring letter case, archived projects included
    Given <setup>
    When direct SQL inserts a project named "<name>" in <organization>
    Then <outcome>

    Examples:
      | setup                    | name     | organization | outcome                                        |
      | nothing else             | CHECKOUT | Acme         | the database rejects it with SQLState 23505    |
      | Ana has archived Billing | billing  | Acme         | the database rejects it with SQLState 23505    |
      | nothing else             | Checkout | Initech QA   | the insert succeeds                            |

  @US-2 @AC-2.4 @P1
  Scenario Outline: Enum columns accept only the documented values
    When direct SQL sets "<column>" to "<value>" on <row>
    Then the database rejects it with SQLState 23514

    Examples:
      | column                   | value   | row                                     |
      | users.status             | DELETED | Ana's row                               |
      | organizations.plan       | GOLD    | Acme's row                              |
      | org_memberships.role     | TESTER  | Cai's Acme membership                   |
      | org_memberships.role     | admin   | Cai's Acme membership                   |
      | project_memberships.role | OWNER   | Hal's Checkout membership               |
      | invitations.role         | TESTER  | Ben's pending invitation of Fay to Acme |
      | invitations.status       | LOST    | Ben's pending invitation of Fay to Acme |

  @US-2 @AC-2.5 @P1
  Scenario Outline: A seat limit is positive or unlimited
    When direct SQL sets Acme's "seat_limit" to <value>
    Then <outcome>

    Examples:
      | value | outcome                                     |
      | 0     | the database rejects it with SQLState 23514 |
      | -3    | the database rejects it with SQLState 23514 |
      | NULL  | the update succeeds                         |

  @US-2 @AC-2.6 @P1
  Scenario Outline: A row that others depend on can't be deleted
    When direct SQL deletes <row>
    Then the database rejects it with SQLState 23503
    And the row and everything that depends on it still exist

    Examples:
      | row                     |
      | Acme's organization row |
      | Ana's user row          |
      | Checkout's project row  |
      | Ivy's user row          |

  @US-2 @AC-2.7 @P2
  Scenario: Every foreign key is indexed and restricts deletes
    When the test reads the catalog of the auth-service database
    Then every foreign-key column is the first column of at least one index
    And every foreign key's delete rule is RESTRICT
```

### US-3: Existing behaviour holds on PostgreSQL (P0)
As the code owner, I want everything phases 1 and 2 proved on H2 to be proved again on PostgreSQL, so that moving the database changes no behaviour (FU-8).

```gherkin
Feature: Existing behaviour on PostgreSQL

  @US-3 @AC-3.1 @P0
  Scenario: The existing suite passes on PostgreSQL
    Given every test runs against the PostgreSQL test database
    When "cd auth-service && ./gradlew clean build" runs
    Then it succeeds with 0 failed and 0 skipped tests
    And every test class of the 1655-test baseline has the same number of tests as before the switch
    And the only changed lines in pre-existing test files are those listed in "Changes to existing tests" and the F1 policy

  @US-3 @AC-3.2 @P0
  Scenario Outline: A row lock held by someone else ends in a 409, not a hang or a 500
    Given the Acme fixture
    And Ben has invited "gus@example.com" to Acme as "MEMBER"
    And another database connection holds a row lock on <row>
    When <request>
    Then within 7 seconds of the request the response is a Problem 409 / CONFLICT
    And the log of the request has exactly one WARN line "Request rejected because a lock could not be acquired", no ERROR line and no stack trace line
    # Accepted at review point A (2026-10-15): the request also logs the aspect's "Slow call" WARN, because it waits for the lock timeout.
    And nothing was stored

    Examples:
      | row                                        | request                                                                                     |
      | Acme's organization row                    | Ben invites "fay@example.com" to Acme as "MEMBER"                                           |
      | the row of Ben's invitation of Gus         | Gus accepts that invitation anonymously with full name "Gus Hale" and password "Secret123!" |

  @US-3 @AC-3.3 @P0
  Scenario: A registration race on PostgreSQL has one winner and leaks no email
    # Repeated 5 times. Re-verifies phase-1 AC-2.11 and phase-2 AC-9.10 on PostgreSQL.
    Given the default logging levels
    When registrations for "Race-QA@Example.com" and "race-qa@example.com" are sent at the same moment
    Then one response has status 201 and the other is a Problem 409 / EMAIL_ALREADY_REGISTERED
    And exactly one user row has the email "race-qa@example.com"
    And neither the Problem body nor any captured log line contains "race-qa@example.com" in any letter case

  @US-3 @AC-3.4 @P1
  Scenario: Simultaneous project creations with one name create exactly one project
    # Repeated 5 times. Re-verifies phase-2 AC-1.7 on PostgreSQL.
    When Ana and Ben together send 5 requests "POST {acme-projects}" with body {"name":"Mobile App"} at the same moment
    Then exactly one response has status 201 and each of the other 4 is a Problem 409 / PROJECT_NAME_TAKEN
    And Acme has exactly one project named "Mobile App"

  @US-3 @AC-3.5 @P1
  Scenario: Simultaneous adds of one user create exactly one membership
    # Repeated 5 times.
    When Ana and Cai each send "POST {acme-projects}/{checkout}/members" with body {"userId":"{Ivy.id}","role":"READER"} at the same moment
    Then one response has status 201 and the other is a Problem 409 / ALREADY_MEMBER
    And no response has a 5xx status
    And Ivy has exactly one membership row in Checkout

  @US-3 @AC-3.6 @P1
  Scenario Outline: Lists are ordered by code point, as on H2
    Given <setup>
    When <actor> sends "<request>"
    Then the response status is 200 and <order>

    Examples:
      | setup                                                                     | actor | request                     | order                                                                    |
      | Fay is seeded as a member of the organizations "beta QA", "Acme QA" and "Zeta QA" | Fay   | GET /api/orgs               | the body lists exactly "Acme QA", "Zeta QA", "beta QA", in that order    |
      | users "ab@example.com" and "a-z@example.com" are seeded as Acme members   | Ana   | GET /api/orgs/{acme}/members | "a-z@example.com" comes before "ab@example.com", which comes before "ana@example.com" |
```

### US-4: Timestamps are UTC instants (P1)
As a frontend developer, I want every timestamp in UTC with an explicit offset, so that the UI shows the right local time whatever the server's time zone.

```gherkin
Feature: UTC timestamps

  Background:
    Given the Projects fixture
    And Ben has invited "kit@example.com" to Acme as "MEMBER"

  @US-4 @AC-4.1 @P1
  Scenario Outline: Every timestamp in a response is a UTC timestamp
    When <request>
    Then the response is successful
    And each of <fields> matches ^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?Z$

    Examples:
      | request                                                                   | fields                                       |
      | Fay registers with "fay@example.com"                                      | "createdAt"                                  |
      | Ana creates the organization "Time Org QA"                                | "createdAt"                                  |
      | Ben invites "fay@example.com" to Acme as "MEMBER"                         | "createdAt", "expiresAt"                     |
      | Ben lists Acme's pending invitations                                      | "[0].createdAt", "[0].expiresAt"             |
      | Ana lists Acme's members                                                  | "[0].joinedAt"                               |
      | Ana creates the project "Mobile App" in Acme                              | "createdAt"                                  |
      | Cai archives Checkout                                                     | "createdAt", "archivedAt"                    |
      | Cai adds Ivy to Checkout as "READER"                                      | "joinedAt"                                   |
      | Kit accepts Ben's invitation anonymously with "Kit Hale" and "Secret123!" | "user.createdAt", "organization.createdAt"   |

  @US-4 @AC-4.2 @P1
  Scenario Outline: The server's time zone changes nothing
    Given the service clock reads 2026-10-14T09:30:00.123456Z
    And the JVM's default time zone is "<zone>"
    When Ana creates the organization "Zone Org QA"
    Then the body's "createdAt" is "2026-10-14T09:30:00.123456Z"
    And the stored "created_at" of "Zone Org QA" is that same instant

    Examples:
      | zone             |
      | UTC              |
      | America/New_York |
      | Asia/Tashkent    |

  @US-4 @AC-4.3 @P2
  Scenario Outline: An invitation expires exactly 7 days after it was created, by the service clock
    Given the service clock read 2026-10-14T09:00:00Z when Ben invited "fay@example.com" to Acme as "MEMBER"
    And the service clock now reads <now>
    When Fay accepts that invitation anonymously with full name "Fay Lowe" and password "Secret123!"
    Then <outcome>

    Examples:
      | now                         | outcome                                         |
      | 2026-10-21T08:59:59.999999Z | the response status is 201                      |
      | 2026-10-21T09:00:00Z        | the response is a Problem 410 / INVITATION_EXPIRED |

  @US-4 @AC-4.4 @P1
  Scenario: Every timestamp column stores its time zone
    When the test reads the catalog of the auth-service database
    Then every column named "created_at", "updated_at", "expires_at" or "archived_at" has the type "timestamp with time zone"
```

**If D7 (the service clock) isn't approved:** AC-4.2 becomes "the body's `createdAt` ends in `Z` and is within 5 s of the test's UTC time, in every zone", and AC-4.3 is dropped.

### US-5: Text PostgreSQL can't store never reaches the database (P0)
As the code owner, I want text that PostgreSQL can't store to be rejected as a validation error, so that bad input gives a clear 400 instead of a 500 or silently changed data (FU-3, FU-8).

```gherkin
Feature: Unstorable text is a validation error

  Background:
    Given the Projects fixture
    And Ben has invited "kit@example.com" to Acme as "MEMBER"

  @US-5 @AC-5.1 @P0
  Scenario Outline: A control character or a lone surrogate in a stored name or email is rejected
    When <actor> sends "<request>" with a valid body in which "<field>" is <value>
    Then the response is a Problem 400 / VALIDATION_FAILED with an "errors" entry for "<field>"
    And no "errors" entry and no log line contains the sent value
    And nothing was stored

    Examples:
      | actor              | request                                         | field    | value                    |
      | an anonymous caller | POST /api/auth/register                        | fullName | "Fay\u0000Lowe"          |
      | an anonymous caller | POST /api/auth/register                        | fullName | "Fay \uD800"             |
      | an anonymous caller | POST /api/auth/register                        | email    | "fay\uDC00@example.com"  |
      | an anonymous caller | POST /api/auth/login                           | email    | "ana\uD800@example.com"  |
      | Ana                | POST /api/orgs                                  | name     | "Org\u0000QA"            |
      | Ana                | POST /api/orgs                                  | name     | "\tOrg QA"               |
      | Ana                | POST /api/orgs                                  | name     | "Org QA\u0085"           |
      | Ana                | POST {acme-projects}                            | name     | "Mobile\u0007App"        |
      | Ana                | POST {acme-projects}                            | name     | "\uDC00"                 |
      | Cai                | PATCH {acme-projects}/{checkout}                | name     | "Check\u007Fout"         |
      | Ben                | POST /api/orgs/{acme}/invitations               | email    | "fay\uD800@example.com"  |
      | an anonymous caller | POST /api/auth/invitations/{Kit's token}/accept | fullName | "Kit\u0000Hale"          |

  @US-5 @AC-5.2 @P1
  Scenario Outline: Characters that trimming removes from a project name don't count
    When Ana sends "POST {acme-projects}" with body {"name":<value>}
    Then <outcome>

    Examples:
      | value              | outcome                                                          |
      | "\u0000Checkout"   | the response is a Problem 409 / PROJECT_NAME_TAKEN               |
      | "Mobile App\n"     | the response status is 201 and the body has "name" "Mobile App" |

  @US-5 @AC-5.3 @P1
  Scenario Outline: An invitation token that can't be stored is never a server error
    When an anonymous caller sends "POST /api/auth/invitations/<token>/accept" with body {"password":"Secret123!","fullName":"Fay Lowe"}
    Then the response status is 404 with code INVITATION_NOT_FOUND, or 400
    And the response status is not 5xx
    And nothing was stored

    Examples:
      | token          |
      | abc%00def      |
      | abc%C0%80def   |
      | abc%ED%A0%80de |

  @US-5 @AC-5.4 @P1
  Scenario Outline: Ordinary non-ASCII names are accepted and stored exactly
    When Ana sends "POST /api/orgs" with body {"name":<value>}
    Then the response status is 201
    And the stored organization name equals the decoded <value> exactly

    Examples:
      | value              |
      | "Café Ünïcödé"     |
      | "日本語チーム"        |
      | "Équipe 😀"        |
      | DECOMPOSED_ECLAIR  |
```

In AC-5.4, `DECOMPOSED_ECLAIR` stands for the JSON string made of `e`, U+0301 COMBINING ACUTE ACCENT, then `clair` (the NFD spelling of "éclair"). It must be stored with those exact code points: no normalisation (FU-2 is phase 3).

**Notes for testers.**
- AC-5.1's login row expects `VALIDATION_FAILED` because login already validates its body before authenticating (phase-1 behaviour for invalid emails).
- AC-5.3's rows are percent-encodings in the request path. The firewall may reject some of them with its own 400 (phase-1 FU-1). The AC accepts that, but never a 5xx and never a different 4xx.
- **Invitee "kit" (2026-10-15, review point A).** The Projects fixture already makes Gus an Acme member (`support/ProjectFixture:42-45`), so the US-4, US-5 and US-6 Backgrounds invite `kit@example.com` instead of `gus@example.com`. The user decided this for US-5; pm applied the same fix to US-4 and US-6, which share the Background. AC-3.2 keeps Gus, because it uses the Acme fixture only.
- **As built on PostgreSQL (review point A, 2026-10-15):** AC-5.3's `abc%00def` gets the firewall's 400 with an empty body, which the AC allows; the other two tokens get 404 `INVITATION_NOT_FOUND`. E60 wasn't needed.

### US-6: Names count what users see, and JSON types are strict (P1)
As a frontend developer, I want names validated by what users see, and wrongly typed JSON rejected, so that invisible names and coerced values can't be created (FU-3, FU-11, FU-12).

```gherkin
Feature: Visible names and strict JSON types

  Background:
    Given the Projects fixture
    And Ben has invited "kit@example.com" to Acme as "MEMBER"

  @US-6 @AC-6.1 @P1
  Scenario Outline: A name made only of invisible characters is blank
    When <actor> sends "<request>" with a valid body in which "<field>" is a string made of exactly the code points <code points>
    Then the response is a Problem 400 / VALIDATION_FAILED with an "errors" entry for "<field>"
    And nothing was stored

    Examples:
      | actor               | request                                         | field    | code points          |
      | Ana                 | POST /api/orgs                                  | name     | U+00A0               |
      | Ana                 | POST {acme-projects}                            | name     | U+200B               |
      | Cai                 | PATCH {acme-projects}/{checkout}                | name     | U+FEFF               |
      | an anonymous caller | POST /api/auth/register                         | fullName | U+2060, U+00A0       |
      | an anonymous caller | POST /api/auth/invitations/{Kit's token}/accept | fullName | U+202F, U+0020, U+200D |

  @US-6 @AC-6.2 @P1
  Scenario Outline: Name limits count code points
    When <actor> sends "<request>" with a valid body in which "<field>" is <value>
    Then <outcome>

    Examples:
      | actor               | request                 | field    | value                     | outcome                                                              |
      | Ana                 | POST /api/orgs          | name     | 100 "😀"                  | the response status is 201                                           |
      | Ana                 | POST /api/orgs          | name     | 101 "😀"                  | the response is a Problem 400 / VALIDATION_FAILED for "name"         |
      | Ana                 | POST {acme-projects}    | name     | 100 "😀"                  | the response status is 201                                           |
      | Ana                 | POST {acme-projects}    | name     | one space, then 100 "😀"  | the response is a Problem 400 / VALIDATION_FAILED for "name"         |
      | an anonymous caller | POST /api/auth/register | fullName | 100 "é" (U+00E9)          | the response status is 201                                           |
      | an anonymous caller | POST /api/auth/register | fullName | 101 "é" (U+00E9)          | the response is a Problem 400 / VALIDATION_FAILED for "fullName"     |

  @US-6 @AC-6.3 @P1
  Scenario Outline: A number or a boolean where a string belongs is malformed
    When <actor> sends "<request>" with a valid body in which "<field>" is the JSON value <value>
    Then the response is a Problem 400 / MALFORMED_REQUEST
    And nothing was stored

    Examples:
      | actor               | request                                         | field    | value    |
      | an anonymous caller | POST /api/auth/register                         | email    | 123      |
      | an anonymous caller | POST /api/auth/register                         | fullName | true     |
      | an anonymous caller | POST /api/auth/login                            | password | 12345678 |
      | Ana                 | POST /api/orgs                                  | name     | 42       |
      | Ana                 | POST {acme-projects}                            | name     | 123      |
      | Cai                 | PATCH {acme-projects}/{checkout}                | name     | false    |
      | Ben                 | POST /api/orgs/{acme}/invitations               | email    | 7        |
      | an anonymous caller | POST /api/auth/invitations/{Kit's token}/accept | password | 12345678 |

  @US-6 @AC-6.4 @P2
  Scenario: A null for a string field is still a validation error
    When Ana sends "POST /api/orgs" with body {"name":null}
    Then the response is a Problem 400 / VALIDATION_FAILED with an "errors" entry for "name"
```

### US-7: Only canonical UUIDs identify resources (P2)
As a developer of a service that calls auth-service, I want every resource to have exactly one id string, so that caches and records keyed on ids never see two ids for one project (FU-10, BUG-1).

```gherkin
Feature: Canonical UUIDs in paths

  Background:
    Given the Projects fixture

  @US-7 @AC-7.1 @P2
  Scenario Outline: A UUID that isn't canonical is malformed
    When <actor> sends "<request>"
    Then the response is a Problem 400 / MALFORMED_REQUEST
    And nothing was stored

    Examples:
      | actor | request                                                       |
      | Hal   | GET /internal/projects/1-1-1-1-1/access                       |
      | Ana   | GET /api/orgs/3b9d5c1e-000-4000-8000-0000000000ff             |
      | Ana   | GET /api/orgs/3b9d5c1e-0000-4000-8000-0000000000f             |
      | Ana   | GET {acme-projects}/ab3b9d5c1e-0-0-0-0                        |
      | Ana   | DELETE /api/orgs/{acme}/members/1-1-1-1-1                     |
      | Ana   | DELETE /api/orgs/{acme}/invitations/1-1-1-1-1                 |

  @US-7 @AC-7.2 @P2
  Scenario: A canonical UUID in upper case still identifies the resource
    When Ana sends "GET {acme-projects}/<Checkout's id in upper case>"
    Then the response status is 200 and the body's "id" is "{checkout}" in lower case
```

**Note for testers:** rows 2 and 3 are `{unknown id}` (`3b9d5c1e-0000-4000-8000-0000000000ff`) with one zero removed. Today Java parses both, giving 403 for row 2 and an unrelated id for row 3 (BUG-1).

### US-8: Local development shows correlated SQL without data (P2)
As a developer, I want SQL statements in the dev log to carry request IDs and no values, so that I can trace a request's queries without exposing personal data (FU-14).

```gherkin
Feature: Dev profile SQL logging

  @US-8 @AC-8.1 @P2
  Scenario: SQL lines go through the logging system, with correlation and without bound values
    Given the Acme fixture
    And the logging settings of the "dev" profile
    When Ana sends "GET /api/orgs" with "X-Request-Id: req-81"
    Then the log of the request has at least one line from the logger "org.hibernate.SQL"
    And every such line contains "req-81" and Ana's user id
    And no output line of the request lacks the log pattern
    And no line of the request contains "ana@example.com", "Ana Lima" or Ana's password hash

  @US-8 @AC-8.2 @P2
  Scenario: The dev start-up log contains no secret (manual)
    When auth-service starts with the "dev" profile on the local database
    Then the start-up log shows the applied or current migration version
    And no line contains the database password "auth_service_dev_only" or the dev JWT secret
```

**AC count:** 36 acceptance criteria across 8 stories: US-1 7, US-2 7, US-3 6, US-4 4, US-5 4, US-6 4, US-7 2, US-8 2.
- P0: 6 (AC-1.1, 1.2, 3.1, 3.2, 3.3, 5.1).
- P1: 23.
- P2: 7 (AC-2.7, 4.3, 6.4, 7.1, 7.2, 8.1, 8.2).
- Manual: AC-1.7 and AC-8.2.

## Proposed auth_service ERD after phase 4

pm applies this to `docs/erd/cucumber.dbml` and `docs/erd/README.md` as task X4, after the scenario-service pm has finished editing them (main session's instruction). Until then, the ERD stays as-built.
- New and changed items are marked `proposed (auth-service-persistence)`.
- backend-swe turns each mark into as-built when its E-item lands (README "Maintenance").
- Enum columns become `varchar(32)` with a note listing the check values. The five auth-service DBML `Enum`s are removed, because dbml2sql would turn them into PostgreSQL `ENUM` types, which D8 rejects.

| Table | Column or object | As-built today (H2) | Proposed after phase 4 | E-item |
|---|---|---|---|---|
| users | email | varchar(255), not null, unique | varchar(254), not null, unique `uk_users_email`, check `ck_users_email_lower_case` | E70, E72 |
| users | password_hash | varchar(255), null | unchanged (null allowed) | — |
| users | full_name | varchar(255), null | varchar(100), not null | E70 |
| users | status | enum, null | varchar(32), not null, check `ck_users_status_values` (`ACTIVE`, `SUSPENDED`, `PENDING_VERIFICATION`) | E70, E71 |
| users | mfa_enabled | boolean, not null | unchanged | — |
| users | created_at, updated_at | timestamp(6), null | timestamptz, not null (set by the application from the service clock) | E69, E70 |
| organizations | name | varchar(255), null | varchar(100), not null (stored as sent) | E70 |
| organizations | plan | enum, null | varchar(32), not null, check `ck_organizations_plan_values` (`FREE`, `TEAM`, `ENTERPRISE`) | E70, E71 |
| organizations | seat_limit | int, null | int, null, check `ck_organizations_seat_limit_positive` (`IS NULL OR > 0`) | E70 |
| organizations | created_at | timestamp(6), null | timestamptz, not null | E69, E70 |
| org_memberships | role | enum, null | varchar(32), not null, check `ck_org_memberships_role_values` (`OWNER`, `ADMIN`, `MEMBER`, `READER`) | E70, E71 |
| org_memberships | created_at | timestamp(6), null | timestamptz, not null | E69, E70 |
| org_memberships | indexes | unique (user_id, org_id) | `uk_org_memberships_user_id_org_id` (user_id, org_id); new `ix_org_memberships_org_id` | E73 |
| projects | name | varchar(255), null | varchar(100), not null; unique index `uk_projects_org_id_name_lower` on (org_id, lower(name COLLATE "und-x-icu")), archived included | E70, E72 |
| projects | created_at | timestamp(6), null | timestamptz, not null | E69, E70 |
| projects | archived_at | timestamp(6), null | timestamptz, null | E69 |
| project_memberships | role | enum, null | varchar(32), not null, check `ck_project_memberships_role_values` (`ADMIN`, `MEMBER`, `TESTER`, `READER`) | E70, E71 |
| project_memberships | created_at | timestamp(6), null | timestamptz, not null | E69, E70 |
| project_memberships | indexes | unique (user_id, project_id) | `uk_project_memberships_user_id_project_id`; new `ix_project_memberships_project_id` | E73 |
| invitations | email | varchar(255), null | varchar(254), not null, check `ck_invitations_email_lower_case` | E70, E72 |
| invitations | role | enum, null | varchar(32), not null, check `ck_invitations_role_values` (`OWNER`, `ADMIN`, `MEMBER`, `READER`) | E70, E71 |
| invitations | token | varchar(255), unique, null | varchar(255), not null, unique `uk_invitations_token` | E70 |
| invitations | status | enum, null | varchar(32), not null, check `ck_invitations_status_values` (`PENDING`, `ACCEPTED`, `REVOKED`, `EXPIRED`) | E70, E71 |
| invitations | expires_at, created_at | timestamp(6), null | timestamptz, not null | E69, E70 |
| invitations | indexes | unique (token) | plus `ix_invitations_org_id_email` (org_id, email), `ix_invitations_invited_by` | E73 |
| audit_log | whole table | 8 columns, `metadata json`, 4 FKs | **dropped** (Q6 option B). Option A: renamed `audit_logs`, `metadata jsonb`, otherwise unchanged | E74 |
| all foreign keys | 7 Refs (without audit_log) | unnamed, default delete rule | named `fk_<table>_<column>`, `ON DELETE RESTRICT` (DBML `[delete: restrict]`) | E73 |
| flyway_schema_history | table | — | managed by Flyway; noted in the Project note, not modelled as a table | E63 |
| Project note | "Runtime database today" | H2, `ddl-auto: create` | PostgreSQL 17, one database `auth_service`, builtin `C.UTF-8`, Flyway, `ddl-auto: validate` | X4 |
| Project note | Conventions: timestamps | `LocalDateTime` / `timestamp(6)` as-built | `timestamptz` + `Instant` for all services; `updated_at` only where an operation needs it (D8) | X4 |
| ERD README | decisions | ERD-D1 to ERD-D11 | add ERD-D12 (enums are varchar plus a named check) and ERD-D13 (collation builtin `C.UTF-8`), both "approved with auth-service-persistence" once Q2 and the E-list are approved | X4 |

## Changes to existing code (approved by the user on 2026-10-14)

**User decision, 2026-10-14:** every item below is approved as recommended:
- E59–E80 and X4;
- the conditional E60 and E61b;
- E62, because Q1 = A;
- E67 (remove H2);
- E74 option B (drop `audit_log`);
- test groups A–C and the F1-a to F1-e policy;
- the "will not be touched" list.

**User decision, 2026-10-15 (review point A):** E81 is approved (Batch B, T5b). E60 and E61b weren't needed and lapse.
**User decision, 2026-10-15 (review point D):** E82 is approved (Batch E, T14a).

**Build order:** phase 4 first, then scenario-service code. scenario-service's T1 (contract, docs only) may run in parallel.

Every file in the working tree is the user's code: HEAD `6fd4434` plus the 4 uncommitted edits. That includes all phase-1 and phase-2 work and its tests. Only the items below may change it. Numbering continues from phase 2 (E1-E58, X1-X3, EG1). Each item can be approved on its own. "Conditional" items apply only if their condition holds, and the task reports which way it went.

### Batch A: switch to PostgreSQL

| ID | Change | Files | Why | Risk | Depends on | Task |
|---|---|---|---|---|---|---|
| E59 | **Unstorable text (D11 rule T1).** One new constraint annotation with its validator (pattern: `MaxUtf8Bytes`), checking the stored value for control characters and lone surrogates. It has a "trimmed" mode for project names. It's applied to `CreateOrganizationRequest.name`, `CreateProjectRequest.name` and `RenameProjectRequest.name` (trimmed), `RegisterRequest.fullName` and `.email`, `LoginRequest.email`, `InviteMemberRequest.email`, and `AcceptInvitationRequest.fullName`. Messages don't contain the value. Contract wording in the same task. | the 7 request records named (one annotation line plus import each); new files in `dto/request/`; `docs/api/auth-service.openapi.yaml` (descriptions only) | FU-3 gate; qa's `QaProjectNamesTest:150-151` would fail on PostgreSQL without it | Medium: public validation. Every existing validation test must pass unchanged. | — | T1 |
| E60 | **Conditional (only if AC-5.3 gives a status other than 404 or 400 on PostgreSQL in T3).** `InvitationServiceImpl.accept` answers 404 `INVITATION_NOT_FOUND` without a database lookup when the token contains characters outside the token alphabet (URL-safe Base64, `[A-Za-z0-9_-]`). This runs after anonymous body validation (phase-1 Q6 order). | `service/impl/InvitationServiceImpl.java` | PostgreSQL rejects NUL in a query parameter (SQLState 22021). That would be 409 `CONFLICT` through the generic handler. | Low | E63 | T3 |
| E61 | `build.gradle`: Flyway starter, plus `flyway-database-postgresql` unless the starter brings it. Conditional E61b: `spring-boot-starter-flyway-test` (D16). | `auth-service/build.gradle` | D4 | Low | — | T3 |
| E62 | `build.gradle` (only with Q1 = A): `spring-boot-testcontainers` and `testcontainers-postgresql` as `testImplementation` (D16) | `auth-service/build.gradle` | D2 | Low | — | T2 |
| E63 | **V1 baseline migration** (new file), equal to the unchanged entities on PostgreSQL (D4) | create `src/main/resources/db/migration/V1__baseline.sql` | D4 | Medium: proven by `validate` and the whole suite | E61 | T3 |
| E64 | `application.yaml`: datasource from `${AUTH_DB_URL}`, `${AUTH_DB_USERNAME}`, `${AUTH_DB_PASSWORD}`; delete `spring.jpa.database: h2` (line 11), `defer-datasource-initialization` (15), the commented dialect line (19), `driver-class-name` (25) and the `spring.h2` block (27-29); `ddl-auto: create` becomes `validate` (14); add the 5 s lock timeout and `logServerErrorDetail=false` (D10; the exact keys are backend-swe's, P20). Nothing else in the file changes. | `auth-service/src/main/resources/application.yaml` | D4, D5, D10 | Medium (every start) | E63 | T3 |
| E65 | `application-dev.yaml`: the dev datasource (D5), labelled `# DEV ONLY`. The JWT lines and `show-sql` stay until E78. | `auth-service/src/main/resources/application-dev.yaml` | D5 | Low | E64 | T3 |
| E66 | **Test infrastructure on PostgreSQL** (D2): wire the test database into `support/AuthServiceIntegrationTest`; new support class or classes for the container (or local test database) and connection budget; the backend-swe test lines listed under "Changes to existing tests", group A; the F1 lines in group F1-a and F1-b. | as listed | D2 | **High: every test.** Proven by AC-3.1 with per-class counts. | E62, E63, E64 | T3 |
| E67 | `build.gradle`: delete `runtimeOnly 'com.h2database:h2'` (line 37) | `auth-service/build.gradle` | Unused after T3 | Low | E66 | T4 |

### Batch B: UTC time

| ID | Change | Files | Why | Risk | Depends on | Task |
|---|---|---|---|---|---|---|
| E68 | **Service clock (D7, FU-4).** A new configuration class with the `Clock` bean. `AuthServiceImpl`, `InvitationServiceImpl`, `OrganizationServiceImpl` and `ProjectServiceImpl` get a constructor-injected `Clock` and use `Instant.now(clock)` at the `now()` lines listed in Code facts. The truncation at `ProjectServiceImpl:107` is removed. | the 4 service implementations (the `now()` lines, one field and import each); a new class in `configuration/`; `ProjectServiceImplTest` and `InvitationServiceImplTest` (one clock field each, group B) | D7 | Low-medium | E69 (same lines; do both in one task) | T5 |
| E81 | **Named failure for missing database settings (AC-1.5; approved by the user on 2026-10-15).** Problem: an unset `${AUTH_DB_*}` stays literal text, so start-up fails with messages that don't name the variable. Change: a validated `@ConfigurationProperties` record for the datasource URL, username and password, in the style of `configuration/JwtProperties`, registered in `PropertiesConfiguration`. Start-up fails with a message naming `AUTH_DB_URL`, `AUTH_DB_USERNAME` or `AUTH_DB_PASSWORD` when that value is blank or still an unresolved `${…}`. Messages never contain the values. | a new record in `configuration/`; `configuration/PropertiesConfiguration.java` (registration and import only); `application.yaml` only if binding needs it (reported) | D5, AC-1.5 | Low-medium (every start) | E64 | T5b |
| E69 | **`Instant` and `timestamptz` (D6).** Migration V2 converts the 10 timestamp columns (originally written as 13; corrected 2026-10-15) to `timestamptz` (values assumed UTC). Every entity timestamp becomes `Instant`, and so do the 7 response DTO timestamp fields and `InvitationRepository`'s 3 parameters. Services use `Instant`. Contract 1.3.0 for the timestamp schema and C4. Test fallout: group B and F1-d. | `model/` (7 entities: the timestamp fields and imports only), `dto/response/UserResponse`, `OrganizationResponse`, `MemberResponse`, `InvitationResponse`, `CreatedInvitationResponse`, `ProjectResponse`, `ProjectMemberResponse` (timestamp types and imports only), `repository/InvitationRepository.java` (3 parameter types and import), the 4 service implementations; new `V2__timestamps_with_time_zone.sql`; `docs/api/auth-service.openapi.yaml` | D6 | **Medium-high:** wide, and the wire format changes | E66 | T5 |

### Batch C: database rules

| ID | Change | Files | Why | Risk | Depends on | Task |
|---|---|---|---|---|---|---|
| E70 | V3: NOT NULL (D8 item 1), lengths (item 2) and the seat-limit check (item 4). The entities mirror them with `@Column(nullable = false, length = ...)`, on exactly those fields. | new `V3__required_columns_and_lengths.sql`; the 6 remaining entities (annotations only); group C test lines | D8 | Medium (test builders that omit a now-required value) | E69 | T6 |
| E71 | V4: enum columns become `varchar(32)` with named checks. If V1 already has Hibernate's checks, only the type narrowing, plus renames where V1 couldn't name them. | new `V4__enum_value_checks.sql` | D8 item 3 | Low | E70 | T7 |
| E72 | V5: `ck_users_email_lower_case`, `ck_invitations_email_lower_case`, and the unique index `uk_projects_org_id_name_lower` | new `V5__case_insensitive_uniqueness.sql` | D8 items 5 and 6 | Low-medium (AC-3.3 and AC-3.4 re-run) | E70 | T8 |
| E73 | V6: FK indexes, plus every FK recreated with its D4 name and `ON DELETE RESTRICT` | new `V6__foreign_key_indexes_and_delete_rules.sql` | D8 items 8 and 9 | Low | E70 | T9 |
| E74 | **audit_log (D9).** Option B (recommended): V7 drops `audit_log`; delete `model/AuditLog.java` and `repository/AuditLogRepository.java`; `support/AcmeFixture.java` lines 9, 42 and 49 removed (group C). Option A: V7 renames the table to `audit_logs` with `metadata jsonb`; `AuditLog` gets `@Table(name = "audit_logs")` and `@JdbcTypeCode(SqlTypes.JSON)` on `metadata`; nothing else. | per option | D9 | Low | E73 | T10 |

### Batch D: input strictness

| ID | Change | Files | Why | Risk | Depends on | Task |
|---|---|---|---|---|---|---|
| E75 | **Unicode-blank names and code-point lengths (D11 rules T2, T3).** New constraint annotation or annotations with validators in `dto/request/`. On the 5 name fields, the new code-point maximum replaces `@Size(max = 100)`. `@NotBlank` stays. Messages unchanged where the rule is unchanged ("... must be at most 100 characters long"). Contract wording. | `CreateOrganizationRequest`, `CreateProjectRequest`, `RenameProjectRequest`, `RegisterRequest` (`fullName` only), `AcceptInvitationRequest` (`fullName` only); new files in `dto/request/`; contract descriptions | FU-3, FU-12 | Medium: phase-1 and phase-2 length tests must pass unchanged | E59 | T11 |
| E76 | **Strict JSON strings (D12).** One global Jackson coercion setting: `application.yaml` keys if Boot 4.1 has them, else one small `configuration/` class. `qa/QaInputEdgeCasesTest` lines per F1-e. Contract wording. | `application.yaml` (the keys only) or a new `configuration/` class; F1-e lines | FU-11 | Medium: affects every string field | — | T12 |
| E77 | **Canonical UUID path variables (D13).** One strict String-to-UUID converter registered for MVC in `configuration/`. Controllers, guards and the advice are unchanged. Contract wording. | a new `configuration/` class | FU-10 | Low-medium (every path with an id) | — | T13 |

### Batch E: developer experience and docs

| ID | Change | Files | Why | Risk | Depends on | Task |
|---|---|---|---|---|---|---|
| E82 | **Faster tests: 1 s lock timeout in tests only (approved by the user on 2026-10-15).** Every test context that uses `support/TestDatabase` gets `lock_timeout` 1 s through one added `@DynamicPropertySource` line (`spring.datasource.hikari.data-source-properties.options`). `application.yaml` (5 s for production) and `application-dev.yaml` are unchanged. `exception/RowLockTimeoutTest` lines 45, 95, 96 and 101 assert the test value. A new test proves that `application.yaml` still sets `-c lock_timeout=5s`. | `support/TestDatabase.java` (one registry line, and one Javadoc sentence); `exception/RowLockTimeoutTest.java` 45, 95, 96, 101; a new test | Build time (4m03s at review point D, about 3× H2) | Low-medium: every lock wait in tests | E64, E81 | T14a |
| E78 | `application-dev.yaml`: replace `spring.jpa.show-sql: true` with `logging.level.org.hibernate.SQL: DEBUG` | `application-dev.yaml` | FU-14, D14 | Low | E65 | T14 |
| E79 | README: "Running locally with PostgreSQL" and "Database and migrations" (D17). Existing sections stay. | `auth-service/README.md` | D17 | Low (docs) | — | T14 |
| E80 | Contract 1.3.0 final sync | `docs/api/auth-service.openapi.yaml` | D17 | Low (docs) | — | T14 |
| X4 | (pm, docs) Apply "Proposed auth_service ERD after phase 4" to `docs/erd/cucumber.dbml` and the ERD README, after the scenario-service pm is done | `docs/erd/cucumber.dbml`, `docs/erd/README.md` | DoD | Low | the other pm | pm task P1 |

### Changes to existing tests (backend-swe's files; G3 exceptions requested with the E-items)

Line numbers are at HEAD `6fd4434`. Every change is mechanical, keeps every assertion and is listed line by line in the handoff.

- **Group A (E66, T3):**
  - Delete the H2 `spring.datasource.url` entry and the Javadoc sentence saying "its own H2 database":
    - `exception/LockFailureTest` 55, 58;
    - `aop/CommitTimeOutcomeTest` 38, 41;
    - `aop/SlowCallLoggingTest` 24, 35, 64 (only the URL entry inside each property list);
    - `aop/UnexpectedFailureLoggingTest` 38, 41;
    - `aop/LoggingSwitchedOffTest` 28, 33 (only the URL entry);
    - `configuration/ResolvedExceptionLoggingTest` 38, 42.

    Also delete a `TestPropertySource` import that becomes unused.
  - Start-up arguments: `configuration/JwtSecretStartupTest` 62-66 and `configuration/OrganizationPropertiesStartupTest` 23, 26 pass the test database's connection settings instead of an H2 URL.
  - Test-database wiring, at most one annotation line plus import per class: the 4 `@DataJpaTest` classes and `AuthServiceApplicationTests`.
  - `InvitationRepositoryTest:76` and `ProjectRepositoryTest:78`, only if their `INFORMATION_SCHEMA` SQL doesn't run on PostgreSQL: the SQL dialect only, with the same assertion.
  - **`LockFailureTest:211`, only if Hibernate's PostgreSQL lock SQL doesn't contain `"for update"`:** match the lock clause PostgreSQL actually uses (report the SQL).
- **Group B (E68, E69, T5):**
  - `LocalDateTime` becomes `Instant`, with the same offsets (`plus(7, ChronoUnit.DAYS)` and so on), plus at most one added import per file, at the lines in Code facts: `support/AcmeFixture`, `support/ProjectFixture`, the 4 repository tests, `InvitationAcceptanceTest`, `RevokeCeilingTest`, `InvitationsTest` (99-100: `Instant.parse`), `ProjectServiceImplTest`.
  - One added `Clock` field in `ProjectServiceImplTest` and `InvitationServiceImplTest`.
- **Group C (E70, E74, T6, T10):**
  - A test builder that omits a value that becomes required gets that value (for example `InvitationRepositoryTest:85-87` has no `updatedAt`). One line per builder, listed in the handoff.
  - E74 option B: `support/AcmeFixture` 9, 42, 49.

## F1 policy for qa's tests (one approval question, Q9)

qa's tests under `auth-service/src/test/java/com/example/authservice/qa/` stay read-only for backend-swe (phase-1 F1). This phase needs exactly the one-time exceptions below. **The user approves them as one set before Batch A.**

In every case:
- the expected values must equal the as-built ERD that backend-swe updates in the same task;
- qa reviews every F1 edit at re-verification;
- **any other qa change, or a qa failure these items don't explain, is a stop:** report the test, the line and the evidence, and don't edit.

| # | Task | File and exact lines | Allowed change |
|---|---|---|---|
| F1-a | T3 (E66) | `qa/QaSchemaTest.java`: Javadoc 15-19 and the bodies of the three test methods, lines 26-125 | **Port to PostgreSQL's catalog** (`information_schema` with `table_schema = 'public'`, `pg_constraint` for checks, `string_agg` instead of `LISTAGG`). Same class name, method names and `@DisplayName`s. The same three properties are checked: every column with type, length and nullability; every enum column's value set (now read from its check constraint); every unique constraint (and unique index) and foreign key. Expected values are V1's (= today's ERD in PostgreSQL type names). Imports may change. No check is dropped. |
| F1-b | T3 (E66) | `qa/QaProjectLoggingTest.java`: lines 31 (`TestPropertySource` import), 69 (Javadoc sentence), 74 (`@TestPropertySource` with the H2 URL) | Delete line 74 and the import. Line 69 becomes "This class has its own context and shares the PostgreSQL test database." |
| F1-c | T5, T6, T7, T8, T9, T10 | `qa/QaSchemaTest.java`: only the expected-value lines of the tables, columns and constraints that the task's migration changes | Update the expected values to the as-built ERD after the migration. |
| F1-d | T5 (E69; only if Q4 is approved) | `QaSupport.java` 19, 83, 91, 99; `QaInvitationSecurityTest.java` 30, 256; `QaFixRoundTest.java` 37, 144; `QaErrorShapeTest.java` 23, 155, 160, 161, 162, 172; `QaProjectRoleMatrixTest.java` 26, 146; `QaProjectIdorTest.java` 32, 258; plus at most one added import line per file | `LocalDateTime` becomes `Instant`; `plusDays(n)` / `minusDays(n)` / `minusMinutes(n)` become `plus(n, ChronoUnit.DAYS)` and so on, with **the same offsets**. Nothing else. |
| F1-e | T12 (E76; only if Q8 is approved as recommended) | `qa/QaInputEdgeCasesTest.java` 336-337 (`@DisplayName`, method name) and 341-343 (assertions), plus imports that become unused | The `{"email":123}` row expects 400 `MALFORMED_REQUEST` (no `errors` assertion). The name and `DisplayName` say "malformed". |

**Alternative (lockstep):** qa makes F1-a, F1-c, F1-d and F1-e itself. The main session routes qa immediately before each named task, and qa's change and backend-swe's change are reviewed together. That's slower (one more hand-off per task). The user chose F1 exceptions in phase 2 every time, so they're the recommended default.

**Will not be touched in phase 4** (any change needs a new approval):
- `AuthServiceApplication.java`.
- `controller/` (all four controllers): D12 and D13 work through configuration only.
- `security/` (guards, `UserPrincipal`, Problem handlers).
- `aop/Logging.java`.
- In `configuration/`: `SecurityConfig`, `JwtAuthenticationFilter`, `RequestIdMdcFilter`, `UserIdMdcFilter`, `LoggingProperties`, `OrganizationProperties`, `JwtProperties` (user-edited), `OpenApiConfiguration`, `PropertiesConfiguration` (except E81's registration entry and import). New configuration classes are allowed (E68, E76, E77, E81).
- `exception/` (all classes, including `ApiExceptionHandler`, `ErrorCode` and `Problems`). No new error code.
- `enums/`, `util/TokenGenerator`, `util/EmailNormalizer`.
- `service/` interfaces and `service/AcceptInvitationResult` (user-edited). `CustomUserDetailsService`, `JwtTokenProvider`, `ProjectRoleService`, `ProjectAccessServiceImpl`.
- Repositories other than `InvitationRepository` (E69 parameter types) and `AuditLogRepository` (deleted under E74 option B). `OrgMembershipRepository` (user-edited) is untouched.
- In the 4 service implementations, everything except the `now()` lines, the clock field (E68, E69) and E60 (conditional).
- Request DTOs other than the annotation lines in E59 and E75. Response DTOs other than the timestamp types (E69).
- Entities other than the timestamp types (E69), the `@Column` mirrors (E70) and E74.
- `settings.gradle`, the Gradle wrapper, `gradlew*`, `.gitattributes`, every `.gitignore`.
- Every existing log statement and level.
- Every existing test file, except the lines in groups A to C and F1-a to F1-e.
- Everything under `api-gateway/`, `scenario-service/` and `report-service/`.

### Refactoring candidates (not scheduled)

RC-1 to RC-10 (phase 2) stay open and unscheduled. New ones found while specifying phase 4:

| # | Where | Finding |
|---|---|---|
| RC-11 | `service/impl/InvitationServiceImpl.java:100-102` | An anonymous accept takes the organization row lock (`checkFreeSeat`), then hashes the password with BCrypt (`createAccount`) while holding it. Every concurrent invite, accept or membership change in that organization waits roughly 100 ms or more per acceptance. Hashing before locking would shorten the hold. With D10's 5 s lock timeout, it matters under load. |
| RC-12 | `model/User.java:30` | `@Column(nullable = true)` repeats the default. Harmless, but it reads as a decision; D8 keeps the column nullable deliberately. |

## Engineering guardrails (binding for every task)

Phase-2 guardrails carry over as adapted below. A task isn't done while any rule is broken. If a rule blocks you, stop and ask in your handoff; don't work around it.

### Scope control
- **G1. Allowed files only.** A task creates, modifies, moves or deletes only the files on its **Allowed files** line. New files may only go under:
  - `auth-service/src/main/java/com/example/authservice/<existing package>/` (no new main packages);
  - `auth-service/src/main/resources/db/migration/` (new; migrations only);
  - `auth-service/src/test/java/com/example/authservice/**`, mirroring main, with `support/` for shared helpers;
  - `docs/api/auth-service.openapi.yaml` and `auth-service/README.md`.

  `auth-service/src/test/resources/` stays disallowed. If Testcontainers or Flyway seems to need a resource file there, stop and ask.
- **G2. Existing code changes only through approved E-items.** "Existing" means every file in the working tree when Batch A starts. That includes the user's 4 uncommitted edits, which must survive every task unchanged. **Recommended:** the user commits them before Batch A.
- **G3. Existing tests** change only as groups A to C and the F1 policy list, and are only strengthened otherwise.
  - **New rule (replaces phase 1's "own database URL"):** every Spring test context uses the one test database. No test creates, alters or drops schema objects in it. Start-up and schema-drift tests use scratch databases they create and drop.
- **G4. No drive-by changes.** No refactors, reformatting, import reshuffles, renames, comment edits or "while I'm here" fixes. Report findings as refactoring candidates.
- **G5. No dependency or version changes** except E61 (with E61b if needed), E62 and E67.
- **G6. Tests stay honest:**
  - no `@Disabled`, no skipping assumptions (including "skip if Docker is missing"), no weakened or deleted tests;
  - `cd auth-service && ./gradlew build` is green at the end of every task;
  - report total, failed and skipped counts, separately for backend-swe and qa;
  - in T3, report the per-class table.
- **G7. No git operations.** No `git add`, `commit`, `stash`, `restore`, `checkout`, `reset`, `mv` or `rm`; the user commits. Each handoff includes `git status --short`, `git diff --stat`, and every new or deleted file with a one-line summary.
- **G8. Self-review the diff** before handing off. Check for:
  - files outside the Allowed files line;
  - unrelated whitespace;
  - debug output;
  - commented-out code;
  - unused imports in touched files;
  - secrets other than the labelled dev values and the existing test-only JWT secret.
- **G9. Service boundaries.** auth-service uses only its own database. No shared library, and no other service's code or configuration.
- **G10. Gateway untouched.** No file under `api-gateway/` changes.
- **G11. Migrations are append-only:**
  - a migration may be edited only within the task that creates it, before its handoff;
  - after a review point, fixes are new versions;
  - never call Flyway `clean`, `repair` or `baseline` from code or configuration.
- **G12. Credentials:**
  - only the labelled `# DEV ONLY` values in `application-dev.yaml` (E65) and the existing test-only JWT secret are committed;
  - no credentials in JDBC URLs;
  - nothing logs a password, at any level.
- **G13. No test activates the `dev` profile or loads `.env` (added 2026-10-18, user decision; E85).** Since `7be734a`, `application-dev.yaml` imports the gitignored repository-root `.env`, which holds the developer's real secrets. No test may use `@ActiveProfiles("dev")`, `--spring.profiles.active=dev` or any other way of loading a `.env` file, and the build must pass with no `.env` present. Tests that check dev settings read `application-dev.yaml` as a resource (`YamlPropertySourceLoader`). Agents never read, print, copy or change `.env`.

### PL. Layering (phase 2's table applies unchanged; additions for persistence)

| Layer | Owns (additions) | Never (additions) |
|---|---|---|
| **Migrations** (`db/migration`) | Every DDL statement: tables, columns, constraints, indexes, delete rules | Business logic; data-dependent statements without a SQL comment stating the assumption |
| **Repository** | Unchanged: derived and annotated queries, locks | Native SQL or DDL in phase 4 |
| **Service** | Business time from the injected `Clock` (E68) | Catching a database exception and continuing in the same transaction (PostgreSQL has aborted it, D10); `LocalDateTime.now()` / `Instant.now()` without the clock |
| **Request DTOs** | Input rules as constraint annotations (E59, E75), with messages that never contain the value | Normalising or changing values |
| **Configuration** | The `Clock` bean (E68), Jackson coercion (E76), the UUID converter (E77), datasource and Flyway settings | Business rules |
| **Controller, guard, advice** | Unchanged | Any phase-4 change (see "Will not be touched") |

### Patterns and practices
Phase 1's P1-P12 and phase 2's P13-P16 apply: layering, transactions, DTOs, validation, errors, configuration, injection, Java hygiene, HTTP, tests, "don't guess framework APIs", one lock for organization-scoped writes, effective role in one place, test first, refactor discipline. Additions:

- **P17. Migration rules (D4):**
  - one migration file per E-item, named `V<n>__<description>.sql`;
  - readable names for every constraint and index;
  - each file runs in a single transaction (PostgreSQL DDL is transactional);
  - no `IF EXISTS` / `IF NOT EXISTS` in versioned migrations (they must be deterministic);
  - every migration item comes with a direct-SQL or catalog test that fails before the migration exists.
- **P18. Time:**
  - main code reads time only from the service clock (E68);
  - no `LocalDateTime`, `LocalDate` or `ZoneId.systemDefault()` in `src/main` after T5;
  - timestamps in JSON are UTC timestamps.
- **P19. One place per text rule:** each D11 and D12 rule lives in one validator or one configuration setting. Messages never echo the value (phase-2 D17 item 4).
- **P20. Verify, don't guess (P12, extended).** Verify and report, with the file, class or log line as evidence:
  - the downloaded starters' transitive Flyway modules;
  - the Testcontainers 2.0.5 container class and package;
  - `@ServiceConnection` wiring in Boot 4.1;
  - whether `@DataJpaTest` replaces the datasource or runs Flyway;
  - Hibernate 7's DDL for `@Enumerated(STRING)` on PostgreSQL;
  - Hibernate's PostgreSQL lock SQL;
  - the lock-timeout mechanism and its key;
  - `logServerErrorDetail`;
  - PostgreSQL 17's builtin locale syntax;
  - Flyway 12.4's support for PostgreSQL 17 (no "unsupported version" warning);
  - Jackson 3's output for `Instant`;
  - Jackson 3's coercion setting.
- **P21. Test-database discipline (D2):**
  - one shared test database;
  - fixtures reset data;
  - scratch databases only for start-up tests, always dropped in `finally`;
  - report distinct contexts and peak connections in T3;
  - never add `@DirtiesContext` to work around connections; ask instead.
- **P21a. Test-context lifecycle (added at review point A, 2026-10-15).** In Boot 4.1, a container imported through `@ServiceConnection` stops when the importing context closes. So, for every task after T4:
  - no `@DirtiesContext`, for any reason;
  - keep the number of cached test contexts well under Spring's limit of 32, so no context that imported the container is evicted and closed mid-run. A new test reuses an existing context configuration (`support/UsesTestDatabase`, `support/UsesEmptyTestDatabase`, `AuthServiceIntegrationTest`) unless it needs different properties;
  - report the distinct context count in every handoff that adds a test class with its own configuration.

### Logging rules (phase-2 D17, binding)
- No passwords, password hashes, JWTs or `Authorization` values, invitation tokens, emails, full names or database passwords in any log line, at any level.
- Ids are allowed.
- The aspect, MDC filters, log pattern and the existing statements and levels are unchanged.
- `org.hibernate.orm.jdbc.bind` is never enabled. `logServerErrorDetail=false` stays (D10). Dev SQL goes only through `org.hibernate.SQL` (E78).
- New code adds no log statements, except where a task says so. Phase 4 adds none.

### Fix rounds after QA
F1-F4 from phase 1 apply, with the one-time exceptions of this spec's F1 policy (once approved).

## Tasks for backend-swe

Do the tasks in order, batch by batch. **Stop at each review point** and hand off; the user reviews the diff before the next batch. A task starts only once its E-items are approved; skip an unapproved item and say so. Run `cd auth-service && ./gradlew build` at the end of every task. Every task follows PL, P17-P21 and the logging rules.

**Before Batch A:**
- Q1 to Q11 are answered.
- With Q1 = A, Docker's daemon is running (`docker info` succeeds).
- For the dev profile only (AC-1.7): the user has quit Postgres.app, and the `cucumber-postgres` container from D5 runs on 5432. The tests don't need it. If it isn't there yet, AC-1.7 moves to T14.
- Record the baseline: full build counts per class.

### Batch A: switch to PostgreSQL (review point A)

**T1. Unstorable text (on H2, before the switch).** E-items: E59.
- **Test first (P15):** AC-5.1's 12 rows and AC-5.2. Run them against the unchanged code and report how each failed. Expected: rows with U+0000, U+0007, U+007F, U+0085, a leading tab, or a lone surrogate give 201 (stored) on H2. The `"\uD800"` rows may give 201.
- **ACs:** AC-5.1, AC-5.2, AC-5.4 (on H2; T3 re-runs them on PostgreSQL).
- **Done when:**
  - those tests pass;
  - every existing test passes unchanged, including `QaProjectNamesTest` (all rows) and every phase-1 and phase-2 validation test;
  - the contract describes the rule;
  - the build is green.
- **Stop conditions:**
  - an existing test fails or needs a change;
  - Hibernate Validator reports a field path other than the record component name for the new constraint.
- **Allowed files:**
  - modify the 7 request records named in E59 (annotation lines and imports only) and `docs/api/auth-service.openapi.yaml` (descriptions of those fields only);
  - create the annotation and validator in `dto/request/`;
  - create tests.
- **Must not:** trim or normalise values in the validator; change any existing message or constraint; touch services.

**T2. PostgreSQL test container, smoke test only.** E-items: E62 (with Q1 = A). With Q1 = B, there's no build change and the smoke test targets the local test database.
- **Change:** add the test dependencies. Create the support class that starts the singleton PostgreSQL 17 container with D3's locale settings. Add one smoke test that connects and checks the server major version (17) and the database's locale provider and locale (D3).
- **The application and every other test stay on H2 in this task.** Don't add Flyway, and don't wire the container into any existing test.
- **Done when:** the smoke test passes, every existing test passes unchanged, and the build is green.
- **Stop conditions:** Docker isn't reachable; the image can't be pulled; the builtin locale can't be set. Report the error.
- **Allowed files:** modify `auth-service/build.gradle` (E62 lines only); create the support class or classes and the smoke test.
- **Must not:** change `application*.yaml`, the existing support classes or any existing test.

**T3. Flyway baseline and the switch.** E-items: E61 (and E61b if needed), E63, E64, E65, E66; E60 only if needed.
- **Before you start:** record per-class test counts.
- **Test first:** a migration test that starts the application on a scratch database with `validate` (AC-1.1). It fails with no V1, because the tables are missing.
- **Change, in this order:**
  1. Write V1 from Hibernate's PostgreSQL DDL for the unchanged entities (D4). Put the column-by-column comparison in the handoff.
  2. Make the build and configuration changes.
  3. Wire every test to the test database (D2).
  4. Make the group A lines and the F1-a and F1-b lines.
- **ACs:**
  - AC-1.1 to AC-1.6;
  - AC-1.7 (manual, if the dev database exists; otherwise in T14);
  - AC-3.1 to AC-3.6;
  - AC-5.3;
  - and the US-5 tests from T1, now on PostgreSQL.
- **Verify and report (P20):**
  - Flyway modules;
  - container wiring;
  - `@DataJpaTest` behaviour;
  - enum DDL in V1;
  - lock SQL;
  - lock-timeout mechanism and key;
  - `logServerErrorDetail`;
  - distinct contexts and peak connections;
  - build time before and after (informational).
- **Done when:**
  - `./gradlew clean build` is green, with every per-class count equal to the baseline and 0 skipped;
  - the concurrency classes are green 3 runs in a row;
  - grep finds no `jdbc:h2` and no `org.h2` in `src/` (the `/h2-console` path strings in `PublicOperationsTest` and `QaPublicEndpointBypassTest` stay);
  - the handoff lists every changed test line.
- **Stop conditions:**
  - any qa test fails other than through F1-a or F1-b; report it and don't edit;
  - a lock-timeout 409 appears in a concurrency test (D10);
  - "too many clients", or any connection error that needs more than test-support settings;
  - `validate` fails against V1 and the cause isn't in V1;
  - a read-only transaction writes;
  - any existing assertion would need a different expected value;
  - E60 would be needed (AC-5.3 gives a different 4xx or a 5xx). Report it, then apply E60 only if approved.
- **Allowed files:**
  - modify `auth-service/build.gradle` (E61 lines), `application.yaml` (E64 keys), `application-dev.yaml` (E65 keys), `support/AuthServiceIntegrationTest.java`;
  - create `src/main/resources/db/migration/V1__baseline.sql` and test-support classes in `support/`;
  - group A test lines; F1-a and F1-b lines;
  - with approval only, `service/impl/InvitationServiceImpl.java` (E60);
  - create tests.
- **Must not:**
  - change an entity, repository, service or controller (E60 aside);
  - keep H2 as a fallback;
  - add `@Disabled`, assumptions or `@DirtiesContext`;
  - create files in `src/test/resources`.

**T4. Remove H2.** E-items: E67.
- **Done when:**
  - the build is green with the same counts as after T3;
  - `./gradlew dependencies --configuration runtimeClasspath` and `--configuration testRuntimeClasspath` list no `com.h2database`;
  - grep finds no H2 class or URL in `src/`.
- **Stop condition:** something still needs H2. Report what.
- **Allowed files:** modify `auth-service/build.gradle` (delete line 37 only).

**Review point A.** The user reviews the switch: V1, configuration, test wiring, F1 edits and the per-class table. qa may start the test plan.

**Review point A: passed (user, 2026-10-15).** The main session verified a clean build: 1735 tests (759 backend-swe, 976 qa), 0 failed, on PostgreSQL 17 through Testcontainers. H2 is removed. V1 is identical to Hibernate's PostgreSQL DDL (catalog comparison, 84 lines). E60 and E61b weren't needed. AC-1.5 isn't done yet; it moves to T5b (E81). backend-swe's deviations, accepted as they are:
- **Container lifecycle (risk).** In Boot 4.1, a container imported through `@ServiceConnection` stops when the importing context closes. New guardrail P21a below.
- **Two support annotations:** `support/UsesTestDatabase`, and `support/UsesEmptyTestDatabase`, whose `@Sql` deletes all rows inside each `@DataJpaTest`'s rolled-back transaction.
- **AC-3.2 log shape:** each lock-timeout request also logs the aspect's "Slow call" WARN (noted in AC-3.2).
- **US-5 Background** invites `kit@example.com`, because Gus is already an Acme member in the Projects fixture (see US-5 "Notes for testers").
- **Stale comments, left under G4:** `exception/LockFailureTest` near line 185 still says "for update", but the actual SQL is "for no key update". `repository/ProjectGuardTest:30` still mentions H2. They aren't fixed in phase 4 unless a later task's Allowed files name them.
- **AC-5.3:** `%00` gets the firewall's 400 with an empty body (the AC allows it); the other two tokens get 404 (US-5 "Notes for testers").

### Batch B: UTC time (review point B)

**T5. Service clock, `Instant` and `timestamptz`.** E-items: E68 (if approved), E69.
- **Test first:** AC-4.1 (fails: no `Z`), AC-4.4 (fails: `timestamp without time zone`), AC-4.2 and AC-4.3 (they need the clock bean, so they fail by not compiling or by asserting).
- **Change:** per E68, E69, D6 and D7. V2 converts the columns (D4). Contract 1.3.0 for the timestamp schema and C4, with the change note. Group B and F1-c/F1-d lines.
- **ACs:** AC-4.1 to AC-4.4. All of AC-3.x still passes.
- **Done when:**
  - the above are green;
  - every existing test passes with only group B and F1-c/F1-d changes;
  - grep finds no `LocalDateTime` in `src/main`;
  - the contract parses as OpenAPI 3.1 (name the validator, or say none was available);
  - the build is green.
- **Stop conditions:**
  - an existing assertion needs a different expected value (other than a type change);
  - Jackson writes `Instant` other than as a UTC timestamp;
  - `QaArchivedProjectTest`'s equality checks between responses fail (precision).
- **Allowed files:**
  - the files named in E68 and E69;
  - new `V2__timestamps_with_time_zone.sql`;
  - a new `configuration/` clock class;
  - group B test lines; F1-c (timestamp lines) and F1-d lines;
  - `docs/erd/cucumber.dbml`: the type and note of each timestamp column V2 converts, turning their E69 mark into as-built (pm task P1, README "Maintenance"); nothing else in that file;
  - create tests.
- **Must not:** change any other DTO field or JSON name; change expiry rules (7 days, exclusive at `expiresAt`).

**T5b. Named failure for missing database settings.** E-items: E81 (approved by the user on 2026-10-15).
- **Test first:** backend-swe's AC-1.5 test (written in T3 and held out of the build so far) joins the build. It ignores the process environment: the three settings are blank or unresolvable placeholders that the test controls. Run it against the unchanged code and report the failure: today the failure doesn't name the variable.
- **Change:** per E81.
- **ACs:** AC-1.5 (3 rows), plus one row per setting for a blank value. AC-1.6 and every other start-up test still pass.
- **Done when:**
  - the failure for each of the 3 settings names `AUTH_DB_URL`, `AUTH_DB_USERNAME` or `AUTH_DB_PASSWORD`;
  - no captured log line and no message in the failure's cause chain contains a real value (the AC-1.6 password, for a set value). **Accepted 2026-10-15:** Boot's failure report prints the rejected value, which is only ever a blank or the `${…}` placeholder;
  - the `dev` profile still starts without any `AUTH_DB_*` variable; tests get them from the container through `support/TestDatabase`;
  - the build is green.
- **Stop conditions:**
  - the datasource or Flyway fails before the new properties are validated, and fixing the order needs a file outside Allowed files;
  - an existing start-up test (`JwtSecretStartupTest`, `OrganizationPropertiesStartupTest`, the AC-1.x tests) needs a changed expected value.
- **Allowed files:**
  - create one record in `configuration/` (pattern: `configuration/JwtProperties`);
  - modify `configuration/PropertiesConfiguration.java` (the registration entry and its import only);
  - modify `application.yaml` only if the record can't bind the existing `spring.datasource` keys as they are; report why;
  - **added 2026-10-15 (E81 extension, after T5b's stop condition: the `DataSource` was created before the record bound, and `@ServiceConnection` left the `${AUTH_DB_*}` placeholders literal):**
    - (a) create one class in `configuration/` extending Boot 4.1's `AbstractDependsOnBeanFactoryPostProcessor`, so the `DataSource` depends on the validated datasource-properties record;
    - (b) modify `support/TestDatabase`: three lines in its `@DynamicPropertySource` that set `AUTH_DB_URL`, `AUTH_DB_USERNAME` and `AUTH_DB_PASSWORD` from the container; `@ServiceConnection` stays;
  - create tests (the AC-1.5 test included).
- **Must not:** add a default value for any `AUTH_DB_*` setting; log or echo a value; change `JwtProperties` (user-edited) or any other configuration class.

**Review point B.** frontend-swe can rely on contract 1.3.0's timestamps.

**Review point B (T5): passed (user, 2026-10-15).** The main session verified a clean build: 1750 tests, 0 failed. V2 converts the **10** timestamp columns to `timestamptz` (E69's "13" was wrong). Contract 1.3.0 is in place. **Batch C follows T5b with no new review stop.**

### Batch C: database rules (review point C)

Each task: **test first** with direct SQL (P17), then the migration, then the F1-c expected-value lines for the tables it changes.

**Progress (2026-10-15).** T5b, T6 (V3) and T7 (V4) are done. The main session verified a clean build: 1786 tests, 0 failed. In T7, backend-swe removed the 5 auth-service DBML `Enum` blocks, and in T6 and T7 it edited the per-column as-built lines of `docs/erd/cucumber.dbml`. **Both are accepted.** The expected `dbml2sql` result is now 22 tables, 26 foreign keys and 8 enums. **T8 to T10 may edit the per-column as-built lines of `docs/erd/cucumber.dbml`** for the objects their migration changes, and nothing else in that file.

**T6. Required columns, lengths, positive seat limit.** E-items: E70.
- **ACs:** AC-2.1, AC-2.5.
- **Allowed files:** new `V3__...sql`; the 6 entities (`@Column` mirrors only); group C builder lines; F1-c lines; create tests.
- **Stop condition:** a qa test saves an entity without a now-required value. Report it.

**T7. Enum value checks.** E-items: E71.
- **ACs:** AC-2.4.
- **Allowed files:** new `V4__...sql`; F1-c lines; create tests.

**T8. Lower-case emails and case-insensitive project names.** E-items: E72.
- **ACs:** AC-2.2, AC-2.3; re-run AC-3.3 and AC-3.4 (5 times each, class green 3 runs in a row).
- **Allowed files:** new `V5__...sql`; F1-c lines; create tests; **G3 exception (user, 2026-10-15):** `controller/InvitationAcceptanceTest:445-447` seeds the invitation email in lower case and logs in with mixed case. That still proves the case-insensitive match.
- **Index (user, 2026-10-15):** `uk_projects_org_id_name_lower` on `(org_id, lower(name COLLATE "und-x-icu"))` (D8 item 6). The first attempt stopped on the A10 edge: with the builtin `lower()`, qa's `QaProjectNamesTest` row "İzmir / izmir are different" got 409.
- **Stop condition:** a phase-1 or phase-2 test gets 409 `CONFLICT` where it expected success (the A10 edge). Report the value.

**T9. Foreign-key indexes and delete rules.** E-items: E73.
- **ACs:** AC-2.6, AC-2.7.
- **Allowed files:** new `V6__...sql`; F1-c lines; create tests.

**T10. `audit_log`.** E-items: E74 (the approved option).
- **ACs:** AC-1.1 (the table list); the full suite.
- **Allowed files:**
  - option B: new `V7__drop_audit_log.sql`; delete `model/AuditLog.java` and `repository/AuditLogRepository.java`; group C lines in `support/AcmeFixture`; F1-c lines;
  - option A: new `V7__...sql`; `model/AuditLog.java` (two annotations); F1-c lines.
- **Stop condition:** anything besides the named lines references the class or table. Report it.

**Review point C.** The user reviews migrations V3 to V7 and the ERD's as-built marks. pm then applies X4 (if the other pm is done).

**Review point C: passed (user, 2026-10-15).** The main session verified a clean build: 1801 tests, 0 failed, migrations V1 to V7. `audit_log` is dropped. The ERD has 21 tables, 22 foreign keys and 8 enums. Notes:
- **For qa (`QaSchemaTest` weakness):** the uniques list shows the expression index `uk_projects_org_id_name_lower` only as `"PROJECTS(ORG_ID)"`. It doesn't prove the `lower(name COLLATE "und-x-icu")` part. AC-2.3 covers that behaviour; qa may strengthen the check in its own files.
- **G4 leftover:** the Javadoc at `support/UsesEmptyTestDatabase:16` is stale. It stays unless a later task's Allowed files name it.
- **Build time:** about 3.0× H2. The NFR raises a question only for "more than 3×", so pm records this as acceptable at the edge. If a later batch pushes it above 3×, backend-swe reports it as a question; it doesn't work around it.
- **For the future `auth-service-audit` spec:** it creates `audit_logs` in a new migration, V8 or later. V7 is applied and append-only (G11).

### Batch D: input strictness (review point D)

**T11. Unicode-blank names and code-point lengths.** E-items: E75.
- **Test first:** AC-6.1 and AC-6.2. They fail today: blanks give 201; 100 emoji give 400.
- **ACs:** AC-6.1, AC-6.2. Every existing length and blank test passes unchanged.
- **Allowed files:** the 5 records named in E75 (annotation lines and imports); new files in `dto/request/`; contract descriptions; create tests.
- **Must not:** change email or password rules, or any existing message for an unchanged rule.

**T12. Strict JSON strings.** E-items: E76.
- **Test first:** AC-6.3 fails today (201 or `VALIDATION_FAILED`).
- **ACs:** AC-6.3, AC-6.4.
- **Allowed files:** `application.yaml` (the coercion keys only) or one new `configuration/` class; F1-e lines; contract descriptions; create tests.
- **Stop condition:** any other existing test changes outcome. Report it.

**T13. Canonical UUIDs.** E-items: E77.
- **Test first:** AC-7.1's rows give 403 or 200 today.
- **ACs:** AC-7.1, AC-7.2. Every phase-2 malformed-id test passes unchanged.
- **Allowed files:** one new `configuration/` class; contract descriptions; create tests.
- **Stop condition:** a non-canonical id is used as a valid id in any existing test.

**Review point D.**

**Review point D: passed (user, 2026-10-15).** The main session verified a clean build: 1849 tests, 0 failed, 4m03s. The user accepted two mechanism deviations:
- **T12 (E76):** Boot 4.1 has no `application.yaml` key for strict string coercion, so it's one `JsonMapperBuilderCustomizer` class, `configuration/StrictJsonStringsConfiguration` (E76's fallback).
- **T13 (E77):** a binder-only `@ControllerAdvice`, `configuration/CanonicalUuidBinding`, with a strict UUID `PropertyEditor`. A registered converter can't work, because Spring's `TypeConverterDelegate` falls back to the lenient `UUIDEditor`. Controllers, guards and `ApiExceptionHandler` are unchanged.

### Batch E: developer experience, docs, end to end (review point E)

**T14a. 1 s lock timeout in tests.** E-items: E82 (approved by the user on 2026-10-15). Runs before T14, so T14's final build includes it.
- **Before you start:** record the `./gradlew clean build` time (review point D: 4m03s).
- **Test first:** the new production-value test (it reads the `spring.datasource.hikari.data-source-properties.options` value from `application.yaml` and expects `-c lock_timeout=5s`). Then change `RowLockTimeoutTest:101` to `"1s"` and show it fails before the `TestDatabase` line exists.
- **Change:**
  - `support/TestDatabase`: one `registry.add("spring.datasource.hikari.data-source-properties.options", () -> "-c lock_timeout=1s")` line in `connectionPool`, and one Javadoc sentence saying tests use 1 s and production 5 s;
  - `exception/RowLockTimeoutTest`: line 45 (Javadoc: "within the test lock timeout (1 s; production 5 s)"), 95 (`@DisplayName` names 1 s in tests), 96 (method name), 101 (`"1s"`). Lines 54 and 159 (7 s bound) stay, because AC-3.2's "within 7 seconds" still holds.
- **qa lines (F1):** none expected. `QaFixRoundTest` (`:515-520`) and `LockFailureTest` (`:131-156`) hold locks but don't assert the timeout value or the wait time. If any qa test turns out to need a change, stop and report it; don't edit.
- **ACs:** AC-3.2 (both rows, in about 1 s), the D10 value test (1 s in tests), and the new production-value test (5 s). All AC-3.x tests and the concurrency classes still pass.
- **Done when:**
  - the build is green;
  - the concurrency classes (`RegistrationRaceTest`, `QaConcurrencyTest`, `QaFixRoundTest`, `ConcurrentWritesTest`, `RowLockTimeoutTest`, `LockFailureTest`) are green 3 runs in a row;
  - the handoff reports the build time before and after.
- **Stop conditions:**
  - a concurrency test fails or flakes at 1 s, for example a lock-timeout 409 where it expected success (D10's stop condition). Report the waits; don't raise the value yourself;
  - a start-up test (scratch database) needs a change.
- **Allowed files:** `support/TestDatabase.java` (the line and the sentence above); `exception/RowLockTimeoutTest.java` lines 45, 95, 96 and 101; create the production-value test.
- **Must not:** change `application.yaml` or `application-dev.yaml`; change any other test line; add `@DirtiesContext` (P21a).

**T14. Dev SQL logging, README, contract sync, manual flow.** E-items: E78, E79, E80.
- **ACs:** AC-8.1 (automated), AC-8.2 (manual), AC-1.7 (manual, if not done in T3).
- **Manual check** through the gateway, both services on `dev`, auth-service on the local PostgreSQL. Record every status code:
  1. Run the phase-2 T16 flow, steps 1 to 7.
  2. Restart auth-service.
  3. Repeat step 4's view: the data is still there.
- **Done when:**
  - `./gradlew clean build` is green;
  - the contract parses and says 1.3.0;
  - the README sections exist;
  - the manual results are in the handoff;
  - a p95 for `GET {acme-projects}/{checkout}` and for the access check on PostgreSQL is reported (informational; target 100 ms locally).
- **Allowed files:** `application-dev.yaml` (E78 line only), `auth-service/README.md`, `docs/api/auth-service.openapi.yaml`; create the AC-8.1 test.
- **Must not:** change code or configuration to make a manual check pass. Report instead.

**Review point E.** Then qa verifies.

### QA verification and fix round 1 (2026-10-15)

**qa's verdict** (`docs/qa/auth-service-persistence-report.md`): signed off with known issues, conditional on pm.
- 34 of 36 ACs pass, including all 6 P0 ACs. The two failures are variants outside the spec's rows: AC-1.6 (BUG-1) and AC-7.1 (BUG-2).
- `clean build`: 1988 tests, 0 failed. The concurrency classes were green 3 runs in a row, and so was the gateway. `dbml2sql` gives 21 tables, 22 foreign keys and 8 enums. The manual run passed.

**User decision, 2026-10-15:** fix BUG-1 and BUG-2 now in a short fix round. E83 and E84 are approved. F1-F4 apply (test first, the smallest change, no other files). The acceptance decision waits until qa re-verifies.

**pm severity call:** **BUG-1 is S2.** A real database password printed in the start-up log breaks G12 and D5 ("never logged, at any level"). That's a security rule, even though it needs a password containing `${` and only shows on a failed start-up. Generated managed-database passwords can contain both characters. BUG-2 stays S3.

| ID | Change | Files | Why | Task |
|---|---|---|---|---|
| E83 | **BUG-1.** Two changes: (1) the settings check rejects a value only when it's blank, or when the whole value is one unresolved placeholder `${NAME}`. A value that merely contains `${`, `$` or `{` is accepted. (2) No configured value ever appears in any log line, the failure report or the cause chain, whatever the reason for the rejection. If Boot's bind-validation failure report prints the rejected value (it does today), the check must fail in a way that report can't print. backend-swe verifies the mechanism and reports it (P20). | `configuration/DatabaseProperties.java`; `configuration/DataSourceDependsOnDatabaseProperties.java` and the `PropertiesConfiguration` registration line, only if the mechanism needs them; at most one new class in `configuration/`; tests | G12, D5, AC-1.6 | FR1 |
| E84 | **BUG-2.** `CanonicalUuidEditor` matches the text exactly as received, with no trimming. A value with leading or trailing whitespace is 400 `MALFORMED_REQUEST`. The contract stays "36 characters" and isn't changed. | `configuration/CanonicalUuidBinding.java` (the `setAsText` lines only); tests | D13, AC-7.1 | FR1 |

**FR1. Fix BUG-1 and BUG-2, and cheap test weaknesses.** E-items: E83, E84.
- **Test first:** each new row must fail against the current code; report how it fails.
  - BUG-1 (`configuration/DatabaseStartupTest` or a new start-up test, on a scratch database with a wrong password):
    - the passwords `Qa${Pw-61`, `Pw${QA}61` and `Pw$QA{61}` pass the settings check. Start-up then fails only on the connection, and none of these values appears in the captured log or in the cause chain;
    - each setting set to exactly `${AUTH_DB_PASSWORD}` (and likewise for the URL and username) is still rejected with the variable's name, as in AC-1.5, and the failure report doesn't contain the value;
    - a blank value is still rejected with the variable's name.
  - BUG-2 (`controller/CanonicalUuidTest`): `GET /api/orgs/%20{acme}`, `/api/orgs/{acme}%20`, `/api/orgs/%09{acme}` and `GET /internal/projects/%20{checkout}/access` all answer 400 `MALFORMED_REQUEST`.
- **Test weaknesses (backend-swe's own phase-4 tests; cheap fixes only):**
  - **W2 (required):** `CanonicalUuidTest` gets the BUG-2 rows above, plus a 35-character id with a 7-digit first group (this kills mutant M9).
  - **W3 (required):** `DevSqlLoggingTest` must not depend on class order. Before the request, re-apply the dev profile's `org.hibernate.SQL` level from the context's environment, and restore the previous level afterwards (qa's `QaDevLoggingTest` does this). Show it passing when run after another context has started, for example `--tests` with a class that starts later.
  - **W4 (cheap):** `TimestampColumnsTest` pins the 10 expected `table.column` names instead of `isNotEmpty()`.
  - **W7 (cheap):** add `@DisplayName` to the AC tests that have none in `DatabaseStartupTest`, `ConcurrentWritesTest`, `ListOrderTest`, `CanonicalUuidTest`, `UtcTimestampsTest` and `TimestampColumnsTest`. This changes annotations only.
  - **W1, W5, W6:** no change. qa's tests cover them (`#theServerErrorDetailNeverReachesAMessage`, `QaTimeZoneTest`, `#everyForeignKeyLeadsAValidFullIndex`).
- **Done when:**
  - `./gradlew clean build` is green, with counts reported for backend-swe and for qa;
  - qa's `QaDatabaseSettingsTest` and `QaStrictInputTest` pass unchanged, or a changed outcome is reported, not edited;
  - the handoff names the BUG-1 mechanism, with evidence: the captured failure output for `Qa${Pw-61` and for `${AUTH_DB_PASSWORD}`.
- **Stop conditions:**
  - keeping values out of Boot's failure report needs a file outside Allowed files, for example a `META-INF/spring.factories` entry for a failure analyzer (`src/main/resources/META-INF` is a new location under G1). Report the options;
  - any existing assertion would need a different expected value;
  - a qa test fails for a reason other than BUG-1 or BUG-2.
- **Allowed files:**
  - the files named in E83 and E84;
  - `configuration/DatabaseStartupTest.java`, `controller/CanonicalUuidTest.java`, `configuration/DevSqlLoggingTest.java`, `repository/TimestampColumnsTest.java`, plus the `@DisplayName` lines in the W7 classes;
  - create tests.
- **Must not:** change the contract; touch qa's files; add a default for any `AUTH_DB_*` setting; trim or normalise anything else.

**Then qa re-verifies:** BUG-1, BUG-2, AC-1.5, AC-1.6, AC-7.1 and the full build. While doing so, qa trims its own slowest additions where that's cheap (FU-21).

**Triage of qa's other findings (pm, 2026-10-15).** Each line gives the default and the reason. None of them blocks acceptance.

| FU | From | Priority | Default decision and reason | Owner |
|---|---|---|---|---|
| FU-17 | BUG-3 (S4), Q3 | P2, gate: phase 3 (with FU-2) | **Accept the generic 409 for now.** Java 17 has Unicode 13 tables, while ICU and the builtin provider have 15.x, so characters added in Unicode 14 or later lower-case differently. The result is a 409, never a 5xx or bad data, and only for very rare characters. Fix it with phase 3's text normalisation (FU-2): either reject code points unassigned in the runtime's Unicode version, or move to Java 21 or later. A10 is reworded below. | pm (phase 3), backend-swe |
| FU-18 | BUG-4 (S4), Q4 | P2 | **Yes, the 254-character limit applies to the stored (trimmed, lower-cased) value**, as D8 item 2 says lengths match the API. Validate the length of the normalised email, so that this case is 400 `VALIDATION_FAILED` and not 409. Do it together with FU-2, which touches the same normalisation. | backend-swe (phase 3) |
| FU-19 | BUG-5 (S4), Q6 | P2, with FU-1 | **Pool exhaustion should be a Problem 503**, never 401 and never Boot's 500 body. That needs a new error code and handling outside Spring MVC (in the JWT filter), which is FU-1's scope. It's recorded there and not built now. | pm (spec FU-1), backend-swe |
| FU-20 | Q1, R3 | P2, with FU-2 | **Names made only of U+3164, U+2800, U+115F, U+FFA0, U+00AD, U+180E, U+200E/F, U+2061 or U+034F, and a project name prefixed with ZWSP, stay accepted until FU-2.** D11's closed set was approved, and widening it is a public validation change that belongs with normalisation. | pm (phase 3) |
| FU-21 | Q5 | P2 | **The build is about 3.6× H2 (4m51s), so trim it.** qa trims its own phase-4 additions during re-verification (about 55 s, mostly `QaStrictInputTest` 24 s and `QaLockTimeoutTest` 13 s), for example by merging scratch-app starts or making rows parameterized in the shared context, and keeps every check. Target: at most about 3× (about 4m05s). If that isn't cheap, report the time and the build is accepted as it is. | qa |
| FU-22 | R1 | P2 | `ddl-auto: validate` misses some drift (`timestamptz` to `timestamp`, a dropped NOT NULL, shorter lengths). Accepted for phase 4; `QaSchemaTest` pins the test database. A start-up schema check is a candidate for the database pattern later. | pm |
| FU-23 | R6 | P2 | The entities' enum fields still say `varchar(255)` by default, but V4 has `varchar(32)`. Add `@Column(length = 32)` mirrors (pattern item 7) the next time those entities change. | backend-swe |

**R7 (ERD):** done by pm on 2026-10-15. The stale `proposed (auth-service-persistence)` markers on `users.password_hash` and in the `invitations` note are removed.

### pm task
**P1. ERD (X4): done 2026-10-14.** `docs/erd/cucumber.dbml` carries the `proposed (auth-service-persistence)` marks. The ERD README has the legend row and ERD-D12 and ERD-D13. The scenario_service group is unchanged. The main session still has to run `dbml2sql` (the README states the expected result). Original task text: pm applies "Proposed auth_service ERD after phase 4" once the scenario-service pm has finished with `docs/erd/`. This happens before Batch C starts, so backend-swe can turn the marks into as-built in T5 to T10. If that isn't possible in time, backend-swe updates the as-built lines directly in each task, under the README's maintenance rule. pm also records ERD-D12 and ERD-D13.

**P2. As-built ERD after review point A: done 2026-10-15.** Decision: yes, the `auth_service` as-built lines describe V1 on PostgreSQL now, because H2 is gone and the "Runtime database today: H2" note had become false. Notes only (DBML stays valid; no table, column type, enum or Ref changed):
- the Project note: as-built date 2026-10-15, runtime database PostgreSQL 17 + Flyway V1, `ddl-auto: validate`; the conventions say as-built enums are `varchar(255)` plus `CHECK (col IN (...))` and timestamps `timestamp(6) without time zone`;
- the 6 enum columns: "As-built (V1): varchar(255) plus CHECK ck_<table>_<column>_values"; the proposed part of E71 is now only the narrowing to `varchar(32)` (plus NOT NULL from E70);
- the two unique constraints carry their V1 names; the foreign-key comment says V1 already names them `fk_<table>_<column>` with the default delete rule, so E73 only adds `ON DELETE RESTRICT`;
- H2 remarks removed (`users.status`, `invitations.role`, `audit_log.metadata`);
- the ERD README: a "not yet validated" entry (expected `dbml2sql` result: exit 0, 22 tables, 26 foreign keys, 13 enums) and the as-built legend row.

The `scenario_service` group is unchanged. The "As-built today (H2)" column of "Proposed auth_service ERD after phase 4" is the pre-V1 record; for the actual V1 types, read the DBML. From T5 on, backend-swe turns each `proposed (auth-service-persistence)` mark into as-built in the task whose migration lands (README "Maintenance").

## Breakdown

| Area | Work | Depends on |
|---|---|---|
| auth-service | T1-T4 (Batch A, switch; done 2026-10-15), T5 and T5b (B, time and settings validation), T6-T10 (C, rules), T11-T13 (D, input), T14 (E, dev and docs) | Docker Desktop running (tests); the D5 dev container for dev and manual checks |
| api-gateway | **None.** No code, configuration or contract change (D18). The T14 manual flow runs through it. | — |
| scenario-service | No work here. Its first spec adopts "Database pattern for every service" and the Q1 test approach. The coordination points (D18) go to the other pm now. | This spec's approval |
| report-service | As scenario-service. | This spec's approval |
| docs/api | `auth-service.openapi.yaml` 1.3.0 (T1, T5, T11-T13, T14) | — |
| docs/erd | X4 (pm), then backend-swe's as-built marks in T5-T10 | The other pm finishing |
| qa | Test plan `docs/qa/auth-service-persistence-test-plan.md` once the spec is `Ready` (FU-8 matrix on PostgreSQL, US-1 to US-8, a regression strategy for the switch); review of the F1 edits; verification after review point E | Spec `Ready`; review point E |
| Frontend (not in the repo) | Parse timestamps with an offset (UTC `Z`) and show local time. Show 400 `MALFORMED_REQUEST` and `VALIDATION_FAILED` for the new input rules like the existing ones. No new screens. | Contract 1.3.0 (T5) |

## Contracts needed before parallel work

1. **C1. auth-service contract 1.3.0** (`docs/api/auth-service.openapi.yaml`):
   - UTC timestamps (T5);
   - name and email rules (T1, T11);
   - strict string types (T12);
   - canonical UUIDs (T13).

   No path, operation or error code is added or removed. frontend-swe can rely on it after review point B.
2. **C2. Configuration contract** (for deployment later): `AUTH_DB_URL`, `AUTH_DB_USERNAME`, `AUTH_DB_PASSWORD`, `JWT_SECRET`; PostgreSQL 17, UTF-8, builtin `C.UTF-8`; the role owns its database (Flyway runs at start-up). Written in the README (E79). **Rule added 2026-10-15 (BUG-6, FU-24):** no `AUTH_DB_*` value, and in particular no password, may contain `${`. Spring resolves placeholders inside environment values, so such a value is substituted or printed in a start-up failure.
3. **C3. Database pattern** (the reusable section): the contract between this phase and the next services' specs.
4. **C5 (phase 2, unchanged):** the access check keeps its fields and rules. Its ids are canonical (FU-10) once T13 lands. FU-16 stays with the first consumer.

## Cross-service dependencies

| Provider | Provides | Consumer | Blocks parallel work? |
|---|---|---|---|
| auth-service (this spec) | The database pattern and the test approach | scenario-service and report-service specs | No. Their specs can reference it now; the decisions are final once Q1-Q3 are answered. |
| auth-service | Contract 1.3.0: UTC timestamps, strict ids | Frontend; future C5 consumers | No (review point B) |
| scenario-service pm | Finishes editing `docs/erd/` | pm X4 | Blocks only X4, not backend-swe (fallback in P1) |
| gateway | Unchanged routing for the T14 manual flow | qa, T14 | No |

### Known issues that affect verification
1. **Docker must be running** for every build with Q1 = A. A build without it fails at the first database test; it isn't skipped (G6).
2. **Local PostgreSQL.** Port 5432 is Postgres.app 16, which refuses connections; dev needs 17. The user quits Postgres.app and runs the D5 container, or the dev profile can't start (tests are unaffected).
3. **The first build downloads** Flyway, the Testcontainers modules and the `postgres:17` image, so it needs network access.
4. **Through the gateway, the gateway answers 401 first** and its own errors have no `code` (phase-2 Known issues 1 and 2). Unchanged.
5. **Start-up lines before the first request have no request ID**: Flyway, Hikari, `DispatcherServlet` (phase-2 Q4, D14). Accepted.
6. **"One pending invitation per organization and email" stays application-only** (D8 item 7).
7. **`JwtAuthenticationFilter` is registered twice** (phase-2 Known issue 9, RC-10). Unchanged.

## Non-functional requirements

- **Security:**
  - database credentials only from the environment (dev values labelled) and never logged (AC-1.6, AC-8.2);
  - `logServerErrorDetail=false` keeps stored values out of exception messages (AC-3.3);
  - no authorization rule changes, and every phase-2 permission matrix passes on PostgreSQL (AC-3.1);
  - input that the database can't store is a 400, never a 500 (US-5);
  - ids are canonical (US-7).
- **Data integrity:** the database enforces NOT NULL, enum values, lower-case emails, project-name uniqueness, a positive seat limit and restricted deletes (US-2). The application rules stay as the first line of defence.
- **Reliability:**
  - lock waits are bounded (5 s) and answered with 409 (AC-3.2);
  - the schema is validated at every start (AC-1.3, AC-1.4);
  - data survives restarts (AC-1.2).
- **Performance (informational):**
  - p95 under 100 ms locally for project views and the access check (T14);
  - every foreign key indexed (AC-2.7);
  - build time on PostgreSQL reported in T3. A slowdown of more than 3× is raised as a question, not worked around.
- **Observability:** phase-2 D17 unchanged. Dev SQL lines are correlated (AC-8.1).
- **Compatibility:**
  - no path, status or error code changes, except the input rules of US-5 to US-7;
  - timestamps gain a `Z` (contract 1.3.0, change note);
  - list orders stay as on H2 (AC-3.6).
- **Accessibility:** not applicable (no UI in this repo).

## Blocking questions

**None open. Resolved by the user on 2026-10-14, every answer as recommended:**
- Q1 = A: Testcontainers with PostgreSQL 17, for both services. The user starts Docker Desktop.
- Q2: PostgreSQL 17 with builtin `C.UTF-8`. Port 5432 turned out to be Postgres.app 16 (D3), so dev uses a `postgres:17` container (D5).
- Q3: E61, E61b (if needed), E62 and E67 approved.
- Q4 and Q5: `Instant`, `timestamptz`, UTC `Z` in the API, and the service clock.
- Q6: drop `audit_log` (option B).
- Q7: the stored-value text rule.
- Q8: `MALFORMED_REQUEST` for non-strings.
- Q9: the F1-a to F1-e policy.
- Q10: the E-list, with E60 and E61b conditional.
- Q11: the `AUTH_DB_*` variables and dev credentials.
- Build order: phase 4 before scenario-service code.

The original questions are kept below for the record.

1. **Q1. Test database (D2).** A: Testcontainers PostgreSQL 17; **Docker's daemon must run for every build**. B: the local Homebrew PostgreSQL with a separate `auth_service_test` database. C: H2 plus PostgreSQL. **Recommended: A.** Who answers: the user.
2. **Q2. PostgreSQL 17 with the builtin `C.UTF-8` locale everywhere (D3).** Which server runs on 5432 (17.11, 16.15 and 14.24 are installed)? **Recommended:** 17. If 5432 isn't 17, switch the Homebrew service. Who answers: the user.
3. **Q3. Build changes (D16).**
   - E61: `spring-boot-starter-flyway`, plus `flyway-database-postgresql` if not transitive, and `spring-boot-starter-flyway-test` only if needed.
   - E62: `spring-boot-testcontainers` and `testcontainers-postgresql`, only with Q1 = A.
   - E67: remove H2.
   - All versions come from the Boot 4.1.0 BOM.

   **Recommended: approve.** Who answers: the user.
4. **Q4. Timestamps (D6).** `Instant` plus `timestamptz`; the API shows UTC with `Z` (contract 1.3.0, a format change; no client exists yet). **Recommended: approve.** Alternatives: keep the offset-less API format (I-B), or defer (I-C). Who answers: the user.
5. **Q5. FU-4, an injectable service clock (D7).** **Recommended: approve.** The same lines change for Q4 anyway, and it makes expiry and time zones testable. Who answers: the user.
6. **Q6. `audit_log` (D9).** Drop the unused table, entity and repository now; `auth-service-audit` creates `audit_logs` with `jsonb`. **Recommended: drop (B).** Alternative: rename and use `jsonb` now (A). Who answers: the user.
7. **Q7. Text rules (D11).**
   - Control characters (C0 and C1) and lone surrogates are rejected in the **stored** value, so project names are checked after trimming and qa's `"\tCheckout"` rows stay 409.
   - Unicode-blank names are rejected.
   - Name lengths are counted in code points.

   **Recommended: approve.** Alternative: "anywhere in the name as sent", which changes 5 qa rows. Who answers: the user (it refines pm's 2026-10-14 FU-3 wording).
8. **Q8. FU-11 (D12).** A number or boolean in a string field is 400 `MALFORMED_REQUEST`, which changes one qa test that expects `VALIDATION_FAILED`. **Recommended: approve.** Alternative: `VALIDATION_FAILED` per field, which needs more code. Who answers: the user.
9. **Q9. F1 policy (one approval).** backend-swe may edit exactly F1-a to F1-e (qa's `QaSchemaTest` port and expected values, 3 lines of `QaProjectLoggingTest`, the `Instant` lines in 6 qa files, and 5 lines of `QaInputEdgeCasesTest`); qa reviews them at re-verification. **Recommended: approve.** Alternative: qa makes them in lockstep. Who answers: the user.
10. **Q10. The E-list.**
    - Approve E59-E80 (E60 and E61b conditional), X4, test groups A-C and the "will not be touched" list.
    - E72 uses a lower-case check for emails, not a `lower()` index (D8 item 5).
    - **Also recommended:** commit your 4 uncommitted files before Batch A.

    Who answers: the user.
11. **Q11. Environment variables and dev credentials (D5).**
    - `AUTH_DB_URL`, `AUTH_DB_USERNAME`, `AUTH_DB_PASSWORD`, with no defaults outside `dev`.
    - Dev: database and role `auth_service`, password `auth_service_dev_only`, committed in `application-dev.yaml` and labelled `# DEV ONLY`.

    **Recommended: approve.** Who answers: the user.

## Assumptions

- A1. No environment holds data worth keeping. H2 never persisted anything, and dev databases created during Batch A may be dropped and recreated (D4).
- A2. The local machine can run Docker Desktop (or another Docker engine Testcontainers detects) if Q1 = A. Colima or remote engines may need `DOCKER_HOST`; backend-swe reports what was used.
- A3. PostgreSQL 17 will be the production major version. A different managed version later is a deployment decision; the pattern needs ≥ 17 for the builtin locale.
- A4. The 5 s lock timeout (D10) is acceptable for users. A request blocked longer gets 409 `CONFLICT` with "Try again".
- A5. Passwords may contain any character, including control characters. They're hashed, never stored as text; phase 3 decides password rules.
- A6. Organization names and full names keep being stored as sent (not trimmed); phase 4 only rejects unstorable or blank values (D11).
- A7. Existing tests that seed past or future timestamps relative to the system clock stay correct with the service clock, because the default clock is the system UTC clock. Only AC-4.2 and AC-4.3 replace it.
- A8. Ordering by code point (D3) is the intended product order for organization lists and member lists, as on H2 today. A linguistic order would be a later UI decision.
- A9. UUIDs inside JSON bodies (`userId` in add-member) keep Jackson's parsing. FU-10 covers path variables only; qa may report body-level leniency as an observation.
- A10. PostgreSQL's `lower()` under builtin `C.UTF-8` agrees with Java's `toLowerCase(Locale.ROOT)` for every email the API accepts in practice. A disagreement would give 409 `CONFLICT` (D8 item 5) and would be recorded with FU-2. **Corrected 2026-10-15 (T8):** the two disagree for U+0130 (`'İzmir'`). Project names therefore use `lower(name COLLATE "und-x-icu")`, which matches Java (D8 item 6). **Corrected again 2026-10-15 (qa BUG-3):** "Emails stay safe" was wrong. Java 17 uses Unicode 13 case tables, while ICU and the builtin provider use 15.x, so characters added in Unicode 14 or later (for example U+A7C0, U+2C2F, U+10570) lower-case differently. A project name or email containing them can get a generic 409 `CONFLICT` from the database instead of the application's answer. Every Unicode 13 pair qa tried agrees. This is accepted as FU-17.
- A11. The phase-2 baseline of 1655 tests (679 backend-swe, 976 qa) is the reference for AC-3.1. Tests the user may add before Batch A join the baseline, and T3 reports them.

## Acceptance
(Filled in by pm after qa sign-off.)

**2026-10-15: pending.** qa's first report is in, and pm rates BUG-1 as S2, so the feature can't be accepted yet. The decision waits for fix round 1 (FR1: E83, E84) and qa's re-verification. Follow-ups FU-17 to FU-23 are triaged under "QA verification and fix round 1". (Superseded by the decision below.)

### Decision: Accepted with follow-ups (pm, 2026-10-15)

Based on qa's "Re-verification (fix round 1), 2026-10-15" in `docs/qa/auth-service-persistence-report.md` (verdict: signed off with known issues):
- **Every AC has a result and passes:** 36 of 36 (P0 6/6, P1 23/23, P2 7/7).
- **No S1, S2 or S3 bug is open.**
  - BUG-1 (S2) is fixed by E83. qa verified it, including a real start-up with a role whose password contains `$`, `{` and `${`.
  - BUG-2 (S3) is fixed by E84.
  - Open bugs are S4 only: BUG-3 (FU-17), BUG-4 (FU-18), BUG-5 (FU-19) and BUG-6 (FU-24).
- **Build:** `./gradlew clean build` passes 2021 tests (889 backend-swe, 1132 qa), 0 failed, 0 skipped, in 4m03s. The concurrency classes were green 3 runs in a row, and the gateway build is green.
- **Fix round 1 is done:** E83, E84, and the weakness fixes W2, W3, W4 and W7. W1, W5 and W6 are covered by qa's tests.
- **FU-21 is met and closed.** qa trimmed its own tests from 4m30s to 4m03s (target about 4m05s, about 3× H2) without dropping a check.
- **Timestamp count correction:** V2 converted 10 columns. V7 dropped `audit_log.created_at`, so **9 timestamp columns** remain. This is the as-built count that `TimestampColumnsTest` and `QaSchemaTest` pin, and it replaces both the "13" in E69 and Code facts and the "10" in AC-4.4-related notes.

**BUG-6 (S4), decided by pm: accept as a documented rule (FU-24).** Database passwords, and any `AUTH_DB_*` value, must not contain `${`. Reasons:
- the failure comes from Spring's own placeholder resolution (`PlaceholderResolutionException`), before any auth-service code runs;
- it needs a value that literally contains a placeholder;
- operators choose the passwords;
- a code fix needs a failure analyzer registered under `src/main/resources/META-INF`, which is a new location under G1 and out of this phase's scope.

The rule is now in C2 and in the database pattern (item 4). backend-swe adds a README "Settings" note in the next task that touches `auth-service/README.md`. pm can't edit the README.

### auth-service follow-up backlog (current after phase 4, 2026-10-15)

This single table replaces the phase-1 (`docs/specs/auth-service.md`) and phase-2 (`docs/specs/auth-service-projects.md`) lists for current status.

| ID | Priority / gate | Item | Status | Owner |
|---|---|---|---|---|
| FU-1 | P2 | Problems for errors outside Spring MVC (firewall rejections, `TRACE`, direct `/error`), with the gateway's FU-3. Now also includes FU-19. | Open | pm (spec), backend-swe, qa |
| FU-2 | P2, phase 3 | NFC normalisation of emails, and NFC for name comparison. Carries FU-17, FU-18 and FU-20. | Open | pm (phase 3 spec) |
| FU-3 | — | Blank and control characters, lone surrogates, extended to project names | **Closed** in phase 4 (E59, E75; AC-5.1, AC-6.1) | — |
| FU-4 | — | Injectable `Clock` | **Closed** in phase 4 (E68; AC-4.2, AC-4.3) | — |
| FU-5 | P2, not approved | Remaining tidy-ups: 3 stale test texts; tests off the deprecated jjwt API | Open | backend-swe |
| FU-6 | — | No email in logs | Closed (phase 2) | — |
| FU-7 | — | Contract wording for padded role strings | Done (phase 2) | — |
| FU-8 | — | Re-verify the H2-specific behaviour on PostgreSQL | **Closed** in phase 4 (US-3, AC-2.4, AC-4.4) | — |
| FU-9 | — | Gateway spec "Known issues" catch-up | Done (phase 2) | — |
| FU-10 | — | Canonical UUID path variables | **Closed** in phase 4 (E77, E84; AC-7.1, AC-7.2) | — |
| FU-11 | — | Reject non-string JSON for string fields | **Closed** in phase 4 (E76; AC-6.3) | — |
| FU-12 | — | Name lengths in code points | **Closed** in phase 4 (E75; AC-6.2) | — |
| FU-13 | P2, revisit in `auth-service-audit` | Re-check the actor's role under the organization lock in rename, archive, restore and add | Recorded, not approved | pm |
| FU-14 | — | Dev SQL through `org.hibernate.SQL` with correlation | **Closed** in phase 4 (E78; AC-8.1, AC-8.2) | — |
| FU-15 | P2, not approved (G4) | Test hygiene from phase 2 (strict stubs, partial state assertions, AC-7.3 401 body) | Open | backend-swe |
| FU-16 | — | Verify the C5 consumer rules (fail closed, no caching) in the consuming service | **Closed** 2026-10-26 for scenario-service (its X3; AC-7.3 fail closed, AC-7.5 no caching, both pass). Each future C5 consumer verifies the same rules in its own spec (standards §3). The C5 retry is scenario-service's SFU-1. | — |
| FU-17 | P2, with FU-2 | BUG-3: Unicode 14+ case pairs differ between Java 17 and PostgreSQL (generic 409) | Open, accepted | pm (phase 3), backend-swe |
| FU-18 | P2, with FU-2 | BUG-4: the 254 limit applies to the stored, lower-cased email (400, not 409) | Open | backend-swe (phase 3) |
| FU-19 | P2, with FU-1 | BUG-5: pool exhaustion should give a 503 Problem, not 401 or Boot's 500 body | Open | pm (spec FU-1), backend-swe |
| FU-20 | P2, with FU-2 | Other invisible code points in names (qa Q1, R3) | Open, accepted until FU-2 | pm (phase 3) |
| FU-21 | — | Build time back to about 3× H2 | **Closed** 2026-10-15 (4m03s) | — |
| FU-22 | P2 | `ddl-auto: validate` misses some drift; candidate start-up schema check | Open, accepted | pm |
| FU-23 | P2 | Entity enum fields: `@Column(length = 32)` mirrors of V4 | Open, next time the entities change | backend-swe |
| FU-24 | P2, docs | BUG-6: README "Settings" note that `AUTH_DB_*` values must not contain `${` (rule in C2) | Open | backend-swe (next README task) |
| FU-25 | — | Two tests activated `dev` and so loaded the developer's real `.env` (since `7be734a`) | **Closed** 2026-10-18 (FR2, E85; 2023 tests green; qa reviews the `QaDevLoggingTest` edit at its next verification) | — |
| RC-1 | P2 | Hibernate violation inspection in `AuthServiceImpl` | Candidate, not approved | backend-swe |
| RC-2 | P2 | Spring Security exceptions thrown from services | Candidate, not approved | backend-swe |
| RC-3 | P2 | Duplicated `UserResponse` / `OrganizationResponse` mapping | Candidate, not approved | backend-swe |
| RC-4 | P2 | Security infrastructure in `service/impl` | Candidate, not approved | backend-swe |
| RC-5 | P2 | No-op Lombok `@NonNull` in `OrganizationResponse` | Candidate, not approved | backend-swe |
| RC-6 | P2 | Stale lock texts in `handleLockFailure` Javadoc and `LockFailureTest`. Now also the "for update" comment near `LockFailureTest:185` (the SQL is "for no key update"). | Candidate, not approved | backend-swe |
| RC-7 | P2 | JPA lock exception types no longer reached | Candidate, not approved | backend-swe |
| RC-8 | P2, before production | `EmailAlreadyExistsException`: not an `ApiException`, with an email in its message | Candidate, not approved | backend-swe |
| RC-9 | P2, before production | Email in the `UsernameNotFoundException` message | Candidate, not approved | backend-swe |
| RC-10 | P2 | `JwtAuthenticationFilter` registered twice | Candidate, not approved | backend-swe |
| RC-11 | P2 | Anonymous accept hashes the password with BCrypt while holding the organization lock | Candidate, not approved | backend-swe |
| RC-12 | P2 | `User.passwordHash` has a redundant `@Column(nullable = true)` | Candidate, not approved | backend-swe |
| RC-13 | P2 | Phase-4 G4 leftovers: H2 mention at `ProjectGuardTest:30`; stale Javadoc at `support/UsesEmptyTestDatabase:16` | Candidate, not approved | backend-swe |

### Post-acceptance fix round 2 (FR2): tests never load the developer's `.env` (approved by the user on 2026-10-18)

**Problem.** The user's commit `7be734a` (2026-10-18) moved the dev secrets out of the repository. `application-dev.yaml` now has `spring.config.import: "optional:file:.env[.properties],optional:file:../.env[.properties]"`. Two test classes activate the `dev` profile, so when Gradle runs from `auth-service/`, their context imports `../.env`, which is the developer's real repository-root file with `JWT_SECRET` and `AUTH_DB_PASSWORD`. The main session confirmed this on 2026-10-18. The classes are:
- `configuration/DevSqlLoggingTest:42`;
- `qa/QaDevLoggingTest:51`.

The tests still pass, because test properties take precedence, but the test JVM loads real secrets, and test behaviour depends on a file outside the build. pm rates it **S2** (a G12 credentials rule), and it's fixed before scenario-service T2.

| ID | Change | Files | Why | Task |
|---|---|---|---|---|
| E85 | **Tests never load `.env`.** The two classes stop activating the `dev` profile. They still prove E78 (dev SQL goes through `org.hibernate.SQL` at the dev level, correlated, with `?` placeholders and no personal data or secrets) by applying **only the dev profile's `logging.level.org.hibernate.SQL` value, read from `application-dev.yaml` as a resource** (`YamlPropertySourceLoader`, as `LockTimeoutSettingTest` does). They set it before the flow and restore it afterwards (W3), and they use the shared test context's throwaway values (`TestJwt.SECRET`, the container's settings). **Recommended mechanism: remove `@ActiveProfiles("dev")`.** Overriding `spring.config.import` while keeping the profile is allowed only if backend-swe proves that the import is then never processed (evidence in the handoff); otherwise use the recommended mechanism. | the two classes (lines below); `support/` files only if needed | G12, G13 | FR2 |

**FR2. E85** (backend-swe; before scenario-service T1c and T2).
- **Test first:** add an assertion that **no property source of the test context comes from a `.env` file**. For example, no `environment.getPropertySources()` name contains `.env`, and no `spring.config.import` from `application-dev.yaml` was processed. Show it failing against the current code without opening `.env`: the property source's *name* is enough evidence, and its values are never printed.
- **Change:**
  - `configuration/DevSqlLoggingTest` (backend-swe's file):
    - line 21 (the `ActiveProfiles` import) and line 42 (`@ActiveProfiles("dev")`) are removed;
    - lines 32–40 (Javadoc: "with the dev profile's logging settings", "The dev profile is active ... so this class has its own context") are reworded to "with the dev profile's `org.hibernate.SQL` level, read from `application-dev.yaml`; no profile is activated (G13)";
    - lines 70–74: the level is read from the YAML resource instead of the context's `Environment`;
    - the field and import for `Environment` (19, 61–62) go only if they become unused.
    - **Added:**
      - the `.env` property-source assertion;
      - one test method that loads `application-dev.yaml` and checks it sets `logging.level.org.hibernate.SQL: DEBUG`, has no `spring.datasource.password` and no `app.jwtSecret` key, and doesn't set `spring.jpa.show-sql` to true (the "no secrets in the dev file" check).
  - **`qa/QaDevLoggingTest` (qa's file; one-time F1-style exception, approved by the user on 2026-10-18).** Only these lines may change:
    - line 22 (the `ActiveProfiles` import), removed;
    - line 51 (`@ActiveProfiles("dev")`), removed;
    - Javadoc lines 38, 42–43 and 46–47 ("with the dev profile's logging settings", "the JWT secret this context signs with", "The context is the one of `configuration/DevSqlLoggingTest` (the dev profile on the shared test database)", "the level that this context's dev profile configures ... read from the context's environment"), reworded to the new mechanism with the same meaning;
    - lines 79–80 (the level read from `environment`), changed to read the level from `application-dev.yaml` as a resource;
    - at most 3 added lines for that (a helper call and its imports).

    **Every assertion (lines 88–142) and line 95's use of the context's `app.jwtSecret` stay unchanged.** qa reviews this edit at re-verification. Any other qa change is a stop.
- **Prove `.env` independence without touching the real file:**
  1. Copy `auth-service/` to a scratch directory outside the repository whose parent holds no `.env`. Run the two classes there, then the full `./gradlew build`; both must be green.
  2. In a second scratch copy, put a **throwaway** `.env` in the scratch parent that holds only a canary key (for example `ENV_CANARY_QA=1`), and show that the new assertion still passes (no `.env` source).
  3. Remove the scratch directories afterwards. **Never read, print, copy, move or change the real `/Users/uDevs/IdeaProjects/cucumber/.env`.**
- **Done when:**
  - `./gradlew clean build` in `auth-service/` is green, with counts for backend-swe and qa. Only the new methods add tests; no test is removed;
  - both classes keep `@Tag("AC-8.1")`;
  - AC-8.1 is still proven: correlated `org.hibernate.SQL` entries, and no values or secrets;
  - the handoff reports the distinct context count before and after (it may drop by one, because `DevSqlLoggingTest` no longer needs its own context), and the scratch-run results.
- **Stop conditions:**
  - a `@BeforeEach` level change doesn't reach `org.hibernate.SQL` without the profile;
  - any other test needs a change;
  - the fix needs `src/test/resources` or a build change.

  Report; don't work around it.
- **Allowed files:** `configuration/DevSqlLoggingTest.java` (the lines above, plus the added methods); `qa/QaDevLoggingTest.java` (exactly the lines above); `support/` files in `auth-service/src/test/java/com/example/authservice/support/`, only if a shared YAML-reading helper is needed (create one, or add one method to an existing class).
- **Must not:** change `application-dev.yaml`, `application.yaml`, `.env.example`, any `.gitignore` or any main code; activate `dev` anywhere; open `.env`; use `@DirtiesContext`.

**Then:** qa reviews the F1-style edit and re-runs `QaDevLoggingTest` and the full build. pm records the result here. No new AC: the fix keeps AC-8.1 and adds a guard (G13).

**FR2 result: done (2026-10-18).**
- The main session verified a clean build: 2023 tests, 0 failed.
- No test uses `dev`, and the distinct test contexts went from 13 to 12.
- The build passes with no `.env`, and with a canary `.env`, both in scratch copies. The real `.env` was not touched.
- New test helper: `support/DevProfileFile`, which reads `application-dev.yaml` as a resource.
- `QaDevLoggingTest` changed only on the permitted lines, plus one import (`support/DevProfileFile`); no assertion changed. qa reviews it at its next verification.
- FU-25 is closed.

### Next

- **scenario-service T2 onward is unblocked by its gate** (it also waited for FR2 above, which was done on 2026-10-18). That gate was auth-service phase 4 passing its last review point (E), which is now passed, and phase 4 is accepted.
- **One alignment step comes first, under scenario-service's X2:** "If phase 4 changes a pattern item, pm updates this spec before T2". Phase 4 refined pattern items 4, 10, 12, 14 and 16 (see "As proven by the reference implementation" under the database pattern). One of them is substantive: scenario-service's `uk_features_project_id_path_lower` uses `lower(path)`, and under item 10 it needs `COLLATE "und-x-icu"`. The scenario-service pm makes that update before T2.
- Phase 3 (`auth-service-accounts`) picks up FU-2 together with FU-17, FU-18 and FU-20.
