# QA report: auth-service phase 4 (auth-service-persistence)

## Verdict

**Signed off with known issues, pending pm's explicit acceptance of one P1 failure.** Tested on HEAD `79212be` plus the
uncommitted phase-4 working tree, on 2026-10-15.

- **Every P0 AC passes** (6 of 6). There are no S1 or S2 bugs, and every AC has a result.
- **34 of 36 ACs pass and 2 fail on variants outside the spec's rows:**
    - **AC-1.6 (P1), BUG-1:** a database password that contains `${` is rejected as "not set", and Boot's failure report
      prints it in the start-up log.
    - **AC-7.1 (P2), BUG-2:** a path id padded with whitespace (`%20<id>`, `<id>%20`, `%09<id>`) is accepted as the
      canonical id.

  Under the sign-off rule, the AC-1.6 failure needs pm's explicit acceptance, or BUG-1 goes to backend-swe. **If pm
  rates BUG-1 as S2 (a secret in a log), the verdict becomes Not signed off.**
- **The clean build is green:** 1988 tests (875 backend-swe, 1113 qa), 0 failed, 0 skipped. The concurrency classes are
  green 3 runs in a row, and the gateway build is green. `dbml2sql` gives 21 tables, 22 FKs and 8 enums.
- **Mutation check:** backend-swe's tests kill 18 of 20 mutants. The two survivors are killed by qa's new tests (
  section "Mutation check").
- **Manual run through the gateway (`dev`, own `postgres:17`):** every status is as expected, the data persists across a
  restart, and no secret or personal data appears in any log.

| Reference     | Value                                                                                                                            |
|---------------|----------------------------------------------------------------------------------------------------------------------------------|
| Spec          | `docs/specs/auth-service-persistence.md` (Ready), 36 ACs (P0 6, P1 23, P2 7)                                                     |
| Contract      | `docs/api/auth-service.openapi.yaml` 1.3.0                                                                                       |
| ERD           | `docs/erd/cucumber.dbml`, `auth_service` group (as-built, V1 to V7)                                                              |
| Test plan     | `docs/qa/auth-service-persistence-test-plan.md`                                                                                  |
| Commit tested | `79212be` + uncommitted tree (`git status` at hand-off: phase-4 files plus qa's 7 new classes and the `QaSchemaTest` edit)       |
| Environment   | macOS, Docker 27.4.0, Corretto 17.0.18 (Gradle toolchain), Gradle 9.5.1, `postgres:17` (17.11), build machine zone Asia/Tashkent |

## Acceptance-criteria verification matrix (36 ACs)

"BE" is backend-swe's test and "QA" is qa's (package `qa/`). Every listed automated test passed in the final
`clean build`.

| AC     | Priority | Test(s)                                                                                                                                                                         | Result             | Bug or note                                                                    |
|--------|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------|--------------------------------------------------------------------------------|
| AC-1.1 | P0       | BE `DatabaseStartupTest#aFreshDatabaseIsMigratedAtStartUp`; QA `QaMigrationsTest` (rules, one version at a time with the V1 snapshot, libc locale, Hibernate DDL vs migrations) | Pass               |                                                                                |
| AC-1.2 | P0       | BE `#dataSurvivesARestart`; manual R1 to R7                                                                                                                                     | Pass               |                                                                                |
| AC-1.3 | P1       | BE `#theServiceRefusesToStartOnASchemaThatDoesNotMatchItsEntities` (2); QA `#startUpRefusesDriftThatFlywayOrValidateCanSee` (boolean to text, renamed table)                    | Pass               | Risk R1: `validate` misses some drift                                          |
| AC-1.4 | P1       | BE `#aMigrationEditedAfterItWasAppliedStopsTheStartUp`; QA drift rows (V5 checksum, failed latest version)                                                                      | Pass               |                                                                                |
| AC-1.5 | P1       | BE `#withoutDatabaseSettingsTheServiceRefusesToStart` (6); QA `QaDatabaseSettingsTest` (5)                                                                                      | Pass               | BUG-1: a set value containing `${` is treated as unset                         |
| AC-1.6 | P1       | BE `#aWrongDatabasePasswordStopsTheStartUpWithoutBeingPrinted`; manual reproduction                                                                                             | **Fail (variant)** | **BUG-1**                                                                      |
| AC-1.7 | P1       | Manual run (section "Manual run")                                                                                                                                               | Pass               | Port 15432 through `--spring.datasource.url`, as instructed                    |
| AC-2.1 | P1       | BE `DatabaseRulesTest#requiredColumnsRejectAMissingValue` (19); QA `QaDatabaseRulesTest#lengthsAreCountedInCodePoints` (5)                                                      | Pass               |                                                                                |
| AC-2.2 | P1       | BE (4); QA `#unicodeEmailsAreStoredInALowerCaseTheDatabaseAccepts` (8)                                                                                                          | Pass               | BUG-3 (Unicode 14+ emails)                                                     |
| AC-2.3 | P1       | BE (4) + `#izmirAndIzmirCanCoexist` + `#icuLowerCasingMatchesJava`; QA `#theProjectNameIndexAgreesWithTheApplicationsRule` (9)                                                  | Pass               | BUG-3 (Unicode 14+ names)                                                      |
| AC-2.4 | P1       | BE (7) + `#everyEnumColumnIsAShortVarcharWithANamedCheck`; QA `#enumChecksAreExactAndCaseSensitive` (4)                                                                         | Pass               |                                                                                |
| AC-2.5 | P1       | BE (3); QA `#positiveSeatLimitsAreAccepted` (2)                                                                                                                                 | Pass               |                                                                                |
| AC-2.6 | P1       | BE (4); QA `#everyForeignKeyRestrictsDeletesOnItsOwn` (7, each FK named in the error)                                                                                           | Pass               |                                                                                |
| AC-2.7 | P2       | BE `#everyForeignKeyIsIndexedAndRestrictsDeletes`; QA `#everyForeignKeyLeadsAValidFullIndex`, `#everyConstraintHasItsReadableName`, `QaSchemaTest`                              | Pass               |                                                                                |
| AC-3.1 | P0       | `./gradlew clean build`                                                                                                                                                         | Pass               | 1988 tests, 0 failed, 0 skipped; pre-existing qa classes 976 tests (unchanged) |
| AC-3.2 | P0       | BE `RowLockTimeoutTest` (2 + value); QA `QaLockTimeoutTest` (6 writes, burst of 20, deadlock)                                                                                   | Pass               | Deadlock 40P01 is a 409 in 0.24 s                                              |
| AC-3.3 | P0       | BE `ConcurrentWritesTest` AC-3.3 ×5; QA `QaDatabaseRulesTest#theServerErrorDetailNeverReachesAMessage`                                                                          | Pass               | Test weakness W1                                                               |
| AC-3.4 | P1       | BE AC-3.4 ×5, 3 reruns                                                                                                                                                          | Pass               |                                                                                |
| AC-3.5 | P1       | BE AC-3.5 ×5, 3 reruns                                                                                                                                                          | Pass               |                                                                                |
| AC-3.6 | P1       | BE `ListOrderTest` (2)                                                                                                                                                          | Pass               |                                                                                |
| AC-4.1 | P1       | BE `UtcTimestampsTest` (9); QA `QaTimeZoneTest` (every response timestamp in a 9-call flow)                                                                                     | Pass               |                                                                                |
| AC-4.2 | P1       | BE `ServiceClockTest` (3); QA `QaTimeZoneTest` (JVM and session zone from start-up: America/St_Johns, Pacific/Kiritimati)                                                       | Pass               | W5                                                                             |
| AC-4.3 | P2       | BE `ServiceClockTest` (2)                                                                                                                                                       | Pass               |                                                                                |
| AC-4.4 | P1       | BE `TimestampColumnsTest`; `QaSchemaTest#columnsMatchTheErd`                                                                                                                    | Pass               | W4                                                                             |
| AC-5.1 | P0       | BE `UnstorableTextTest` (12); QA all 65 controls, 13 surrogate rows, 6 invalid UTF-8 bodies, `#postgresCantStoreNulAndChangesALoneSurrogate`                                    | Pass               |                                                                                |
| AC-5.2 | P1       | BE (2); QA `#onlyWhatTrimRemovesIsIgnoredInProjectNames` (5)                                                                                                                    | Pass               |                                                                                |
| AC-5.3 | P1       | BE `UnstorableTokenTest` (3)                                                                                                                                                    | Pass               |                                                                                |
| AC-5.4 | P1       | BE (4)                                                                                                                                                                          | Pass               |                                                                                |
| AC-6.1 | P1       | BE `NameRulesTest` (5), `NameConstraintsTest`; QA `#everyUnicodeBlankCodePointIsBlank`                                                                                          | Pass               | Spec gap Q1 (other invisible code points)                                      |
| AC-6.2 | P1       | BE (6); QA `#nameLimitsCountCodePointsInEveryNameField` (8)                                                                                                                     | Pass               | BUG-4 (email length after lower-casing)                                        |
| AC-6.3 | P1       | BE `StrictJsonTypesTest` (8); QA (12)                                                                                                                                           | Pass               |                                                                                |
| AC-6.4 | P2       | BE (1); QA (10)                                                                                                                                                                 | Pass               |                                                                                |
| AC-7.1 | P2       | BE `CanonicalUuidTest` (6); QA `#nonCanonicalUuidFormsAreMalformed` (16)                                                                                                        | **Fail (variant)** | **BUG-2**; W2                                                                  |
| AC-7.2 | P2       | BE (1); QA `#mixedCaseCanonicalIdsIdentifyTheResource`                                                                                                                          | Pass               |                                                                                |
| AC-8.1 | P2       | BE `DevSqlLoggingTest`; QA `QaDevLoggingTest`                                                                                                                                   | Pass               | W3                                                                             |
| AC-8.2 | P2       | Manual log scan                                                                                                                                                                 | Pass               |                                                                                |

**Counts:** Pass 34, Fail 2 (AC-1.6 P1, AC-7.1 P2), Blocked 0, Not tested 0. By priority: P0 6 of 6 pass; P1 22 of 23
pass; P2 6 of 7 pass.

## Suites run

| #        | Command                                                                                                                                                                                  | Result                                                                                                                               |
|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| R1       | `cd auth-service && ./gradlew clean build` (first run)                                                                                                                                   | FAILED: 1988 tests, 1 failed: qa's new `QaDevLoggingTest`, a test-order issue in that test (see W3). Fixed in the test, not in code. |
| R2       | `cd auth-service && ./gradlew clean build` (final)                                                                                                                                       | **BUILD SUCCESSFUL in 4m51s. 1988 tests, 0 failed, 0 skipped.** backend-swe 875, qa 1113 (976 pre-existing + 137 new).               |
| R3 to R5 | `./gradlew test --rerun --tests` for `RegistrationRaceTest`, `QaConcurrencyTest`, `QaFixRoundTest`, `ConcurrentWritesTest`, `RowLockTimeoutTest`, `LockFailureTest`, `QaLockTimeoutTest` | 3 of 3 green: 140 tests each run, 0 failed (46 s, 45 s, 51 s)                                                                        |
| R6       | `cd api-gateway && ./gradlew build`                                                                                                                                                      | BUILD SUCCESSFUL (up to date). 411 tests, 0 failed, 4 skipped (the gateway's D10 baseline, `docs/qa/api-gateway-report.md`)          |
| R7       | `npx -p @dbml/cli dbml2sql docs/erd/cucumber.dbml --postgres` (10.3.1)                                                                                                                   | Exit 0: **21 tables, 22 foreign keys, 8 enums**, as expected                                                                         |
| R8       | Mutation groups A to D in a `/tmp` copy                                                                                                                                                  | See "Mutation check"                                                                                                                 |

**Build time:** 4m51s for the clean build, against about 3.5 min for 1851 tests before qa's additions. qa's 137 new
tests account for about 55 s (`QaStrictInputTest` 24 s, `QaLockTimeoutTest` 13 s, `QaMigrationsTest` 8 s). That's about
3.6× the H2 baseline (review point D: 4m03s ≈ 3×). Under the NFR, this is raised as question Q5, not worked around.

**Contexts:** qa adds no new cached context. The new classes reuse `@AuthServiceIntegrationTest` +
`@Import(ProjectFixture.class)` and `DevSqlLoggingTest`'s dev-profile configuration. Start-up tests run their own
`SpringApplication` on scratch databases and close it.

## Bugs

### BUG-auth-service-persistence-1: a database password containing
`${` is rejected as unset and printed in the start-up log

- Severity: S3 Major (a secret in a log, but only for passwords that contain `${`; pm may raise it to S2)
- Affected: auth-service, start-up (`configuration/DatabaseProperties`, E81)
- Linked AC: AC-1.6 (variant); AC-1.5; D5 / D14 / G12 "the database password is never logged"; E81 "messages never
  contain the values"
- Commit tested: `79212be` + working tree
- Steps to reproduce:
    1. `cd auth-service && ./gradlew bootJar`
    2.
    `env JWT_SECRET="$(head -c 64 /dev/urandom | base64)" AUTH_DB_URL=jdbc:postgresql://localhost:1/none AUTH_DB_USERNAME=qa_user 'AUTH_DB_PASSWORD=Qa${Pw-61' java -jar build/libs/auth-service-0.0.1-SNAPSHOT.jar --server.port=0`
- Expected: the password is set (it isn't an unresolved `${…}` placeholder), so the settings check passes. Start-up then
  fails only on the connection, and the value is never printed.
- Actual: exit 1, "APPLICATION FAILED TO START". The report prints the real value:
  ```
  Property: spring.datasource.password
  Value: "Qa${Pw-61"
  Reason: spring.datasource.password must be set through the AUTH_DB_PASSWORD environment variable
  ```
  The same value is in the `BindValidationException` message ("rejected value [Qa${Pw-61]"). `Pw${QA}61` behaves the
  same way; `Pw$QA{61}` passes the check.
- Evidence: `NO_PLACEHOLDER = "(?s)(?!.*\\$\\{).*"` rejects any value that contains `${`, not only an unresolved
  placeholder. Boot's bind-validation failure analyzer prints the rejected value. So the accepted E81 deviation ("the
  printed value is only ever a blank or the `${…}` placeholder") doesn't hold. Generated managed-database passwords can
  contain `$` and `{`.
- Suggested owner: backend-swe (for example, reject only values that are entirely an unresolved `${NAME}` placeholder,
  and keep values out of the failure report). pm decides whether to accept the risk.

### BUG-auth-service-persistence-2: whitespace-padded UUIDs in paths are accepted as the canonical id

- Severity: S3 Major (P2 AC rule fails; one resource has several id strings)
- Affected: auth-service, every UUID path variable (`configuration/CanonicalUuidBinding`)
- Linked AC: AC-7.1 (variant); D13; contract `docs/api/auth-service.openapi.yaml` line 28 ("canonical UUIDs (36
  characters ...)")
- Commit tested: `79212be` + working tree
- Steps to reproduce (Projects fixture, Ana's token, MockMvc in the shared test context):
    1. `GET /api/orgs/%20{acme}` with `Authorization: Bearer {token of Ana}`
    2. `GET /api/orgs/{acme}%20`, `GET /api/orgs/%09{acme}`
    3. `GET /internal/projects/%20{checkout}/access` as Hal
- Expected: 400 MALFORMED_REQUEST, because the value isn't 36 characters of 8-4-4-4-12 hexadecimal digits.
- Actual: 200 with the resource (`{"id":"<acme>","name":"Acme QA",...}`); the access check answers 200 `TESTER`.
- Evidence: `CanonicalUuidEditor.setAsText` calls `text.trim()` before matching the pattern. That mirrors Spring's
  lenient `UUIDEditor`, but it isn't canonical. The spec's rows don't include whitespace, so BE `CanonicalUuidTest`
  passes.
- Suggested owner: backend-swe (match the untrimmed text). A test row per form can join `CanonicalUuidTest` or qa's
  `QaStrictInputTest` once fixed.

### BUG-auth-service-persistence-3: Java 17 and PostgreSQL 17 disagree on lower case for characters added in Unicode 14+, which gives a generic 409 CONFLICT

- Severity: S4 Minor (exotic characters; a 409, never a 5xx or bad data)
- Affected: auth-service; `POST /api/orgs/{orgId}/projects` (and rename); `POST /api/auth/register`, invite
- Linked AC: AC-2.2, AC-2.3 (variants); D8 items 5 and 6; A10 (its 2026-10-15 correction says "Emails stay safe")
- Commit tested: `79212be` + working tree
- Steps to reproduce (Projects fixture):
    1. Ana `POST {acme-projects}` `{"name":"Ꟁ"}` → 201. Then `{"name":"ꟁ"}` → **409 CONFLICT** "The request conflicts
       with existing data". The same happens for U+2C2F / U+2C5F and U+10570 / U+10597.
    2. `POST /api/auth/register` `{"email":"Ꟁa@example.com","password":"Secret123!","fullName":"Zed QA"}` → **409
       CONFLICT**.
- Expected: one rule. Either the application's rule (Java `toLowerCase(Locale.ROOT)`, under which these are different
  names, so 201), or PROJECT_NAME_TAKEN if pm decides they're the same name. For emails: 201, or a 400.
- Actual: the application lets the request through, and the database rejects it (`uk_projects_org_id_name_lower` under
  ICU; `ck_users_email_lower_case` under builtin `lower()`, Unicode 15.1). The handler logs "Request rejected by a
  database constraint: DataIntegrityViolationException".
- Evidence: Java 17 (the toolchain) has Unicode 13 tables, so U+A7C0 has no lower-case mapping there. qa's agreeing
  pairs (U+0130, U+1E9E, final sigma, U+212A, U+01C5, U+10400) all pass in `QaDatabaseRulesTest`.
- Suggested owner: pm (decide and correct A10), then backend-swe if a change is wanted. Options: run on Java 21 or
  later (Unicode 15), or map this unique violation to PROJECT_NAME_TAKEN.

### BUG-auth-service-persistence-4: a valid email whose lower-case form exceeds 254 characters fails in the database with 409 CONFLICT

- Severity: S4 Minor
- Affected: auth-service; `POST /api/auth/register`, `POST /api/orgs/{orgId}/invitations`
- Linked AC: unspecified (D8 item 2 "lengths match the API"; US-5's goal "text PostgreSQL can't store is rejected with a
  400")
- Commit tested: `79212be` + working tree
- Steps to reproduce:
    1. Email = 64 × U+0130 + `@` + 63 × `a` + `.` + 63 × `b` + `.` + 55 × `c` + `.com` (252 UTF-16 units; passes
       `@Email` and `@Size(max = 254)`).
    2. `POST /api/auth/register` with it, password `Secret123!`, full name `Zed QA`. Then Ben invites it to Acme.
- Expected: 400 VALIDATION_FAILED for `email`, because the stored value (trimmed, lower case) can't fit `varchar(254)`.
- Actual: 409 CONFLICT for both requests. `toLowerCase(Locale.ROOT)` makes the email 316 characters, and the insert
  fails with SQLState 22001.
- Suggested owner: pm (decide whether the 254 limit applies to the stored value), then backend-swe.

### BUG-auth-service-persistence-5: under connection-pool exhaustion, login answers 401 UNAUTHENTICATED and other requests a non-Problem 500

- Severity: S4 Minor (unspecified; only reachable now that the database can be unavailable)
- Affected: auth-service; `POST /api/auth/login`, every authenticated request
- Linked AC: unspecified (FU-1 covers errors outside Spring MVC)
- Commit tested: `79212be` + working tree
- Steps to reproduce: start auth-service on a scratch database with
  `--spring.datasource.hikari.maximum-pool-size=2 --spring.datasource.hikari.connection-timeout=500`. Hold both
  connections from the pool, then call `GET /api/orgs` with a valid token and `POST /api/auth/login` with valid
  credentials.
- Expected: unspecified. Likely a 503 or 500 Problem.
- Actual: `GET /api/orgs` → 500 `{"timestamp":...,"status":500,"error":"Internal Server Error","path":"/api/orgs"}` (
  Boot's body; the failure is in the JWT filter). Login → **401 UNAUTHENTICATED** "A valid bearer token is required",
  which clients would read as an authentication problem.
- Suggested owner: pm (decide the wanted behaviour; likely with FU-1).

## Pre-existing failures

None in auth-service: the whole suite is green. The agent baseline from `aa2234c` no longer applies. The gateway's 4
skipped tests are its documented D10 baseline. No other service was built.

## Mutation check (audit of backend-swe's new tests)

Mutants were applied to a throw-away copy of `auth-service` under `/tmp` and run in four groups. The copy was deleted
afterwards; the repository's `src/main` wasn't touched.

| #   | Mutant                                                      | Killed by backend-swe                                      | Killed by qa                                                                |
|-----|-------------------------------------------------------------|------------------------------------------------------------|-----------------------------------------------------------------------------|
| M1  | V5 index without `COLLATE "und-x-icu"`                      | `DatabaseRulesTest#izmirAndIzmirCanCoexist`                | `QaProjectNamesTest`, `QaDatabaseRulesTest`, `QaSchemaTest` (new key check) |
| M2  | V6 FKs on organizations without `ON DELETE RESTRICT`        | AC-2.7                                                     | `#everyConstraintHasItsReadableName`                                        |
| M3  | V6 without `ix_invitations_invited_by`                      | AC-2.7                                                     | `#everyForeignKeyLeadsAValidFullIndex`                                      |
| M4  | V3 without `token SET NOT NULL`                             | AC-2.1 token row                                           | `QaSchemaTest`                                                              |
| M5  | V5 without `ck_invitations_email_lower_case`                | AC-2.2 row 4                                               | `#everyConstraintHasItsReadableName`                                        |
| M6  | `application.yaml` without `lock_timeout=5s`                | `LockTimeoutSettingTest`                                   | —                                                                           |
| M7  | `logServerErrorDetail: true`                                | **survived** (`ConcurrentWritesTest` AC-3.3 ×5 green)      | `#theServerErrorDetailNeverReachesAMessage`                                 |
| M8  | Strict strings without the Boolean coercion                 | AC-6.3 `true`/`false` rows                                 | `QaStrictInputTest` (3 rows)                                                |
| M9  | UUID pattern first group `{1,8}`                            | **survived** (`CanonicalUuidTest` green)                   | `#nonCanonicalUuidFormsAreMalformed` "35 characters"                        |
| M10 | `StorableText` checks C0 and DEL only (no C1)               | AC-5.1 U+0085 row, `StorableTextTest`                      | `QaStrictInputTest` (5 rows)                                                |
| M11 | `VisibleText` without U+FEFF                                | AC-6.1 FEFF row, `NameConstraintsTest`                     | `#everyUnicodeBlankCodePointIsBlank`                                        |
| M12 | `MaxCodePoints` counts UTF-16 units                         | AC-6.2, `NameConstraintsTest`                              | 3 rows                                                                      |
| M13 | `OrganizationServiceImpl` uses `Instant.now()`              | `ServiceClockTest` AC-4.2 (3)                              | —                                                                           |
| M14 | `InvitationServiceImpl.accept` uses `Instant.now()`         | `ServiceClockTest` AC-4.3 410 row                          | —                                                                           |
| M15 | Dev profile back to `show-sql: true`                        | `DevSqlLoggingTest`                                        | `QaDevLoggingTest`                                                          |
| M16 | `DatabaseProperties` without the password placeholder check | AC-1.5 password "not set"                                  | `QaDatabaseSettingsTest`                                                    |
| M17 | `ddl-auto: none`                                            | AC-1.3 (2)                                                 | `QaMigrationsTest` drift (2)                                                |
| M18 | V2 leaves `projects.archived_at` without a time zone        | `TimestampColumnsTest`                                     | `QaSchemaTest`, `QaMigrationsTest`                                          |
| M19 | `createProject` without the organization lock               | AC-3.4 ×5 (generic CONFLICT instead of PROJECT_NAME_TAKEN) | —                                                                           |
| M20 | `DataSourceDependsOnDatabaseProperties` deleted             | AC-1.5 (5 of 6 rows)                                       | `QaDatabaseSettingsTest` (5)                                                |

backend-swe's tests kill 18 of 20; qa's tests kill every mutant they target, including M7 and M9. Two history drifts
that backend-swe doesn't cover (a changed checksum on V5, the latest migration marked failed) are qa test rows in
`QaMigrationsTest`.

## Test weaknesses (backend-swe's new tests)

| #  | Test                                                                                                                             | Weakness                                                                                                                                                                                                                                                                                                                                                                     | Impact                                                          | Covered by qa                                                  |
|----|----------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|----------------------------------------------------------------|
| W1 | `ConcurrentWritesTest` AC-3.3                                                                                                    | The race is usually decided by the application's own email check, so the unique constraint is rarely reached. The test never asserts that the database path ran, so it can't detect `logServerErrorDetail: true` (M7 survived).                                                                                                                                              | AC-3.3's "leaks no email" isn't proven for the database path    | `QaDatabaseRulesTest#theServerErrorDetailNeverReachesAMessage` |
| W2 | `CanonicalUuidTest`                                                                                                              | The spec's rows never shorten the first group, so a too-lenient first group survives (M9). Whitespace isn't covered (BUG-2).                                                                                                                                                                                                                                                 | AC-7.1                                                          | 16 forms in `QaStrictInputTest`                                |
| W3 | `DevSqlLoggingTest`                                                                                                              | Logback levels are global in the test JVM, and every context that starts later re-applies its own levels. The test passes only because its context is created just before it runs. qa's `QaDevLoggingTest` failed this way in R1 when it reused the cached dev context. It now re-applies the dev profile's level from the context's environment and restores it afterwards. | Latent order dependency if another class shares the dev context | — (risk noted)                                                 |
| W4 | `TimestampColumnsTest`                                                                                                           | `isNotEmpty()` plus "every present column is timestamptz"; the 10 columns aren't pinned                                                                                                                                                                                                                                                                                      | A dropped timestamp column wouldn't fail it                     | `QaSchemaTest` pins all 10                                     |
| W5 | `ServiceClockTest` AC-4.2                                                                                                        | The time zone changes after the pool is open, so the JDBC session zone stays the machine's (Asia/Tashkent here, UTC on a UTC CI host)                                                                                                                                                                                                                                        | Doesn't exercise a non-UTC session in every environment         | `QaTimeZoneTest` sets the zone before start-up                 |
| W6 | `DatabaseRulesTest#everyForeignKeyIsIndexedAndRestrictsDeletes`                                                                  | Checks `indkey[0] = conkey[1]` only, ignoring partial or invalid indexes                                                                                                                                                                                                                                                                                                     | Fine for today's single-column FKs                              | `#everyForeignKeyLeadsAValidFullIndex`                         |
| W7 | `DatabaseStartupTest`, `ConcurrentWritesTest`, `ListOrderTest`, `CanonicalUuidTest`, `UtcTimestampsTest`, `TimestampColumnsTest` | Several AC tests have no `@DisplayName` (only parameterized names)                                                                                                                                                                                                                                                                                                           | Reporting style (D15) only                                      | —                                                              |

The Gherkin rows of every audited class match the spec (row counts and values), including the review-point notes (Kit
instead of Gus, the "Slow call" WARN, `userId=-`).

## Assessment of the accepted deviations

| Deviation                                                                                      | Assessment                                                                                                                                                                                                                                                           |
|------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| T12 `StrictJsonStringsConfiguration`                                                           | Works for integers, floats, exponents, `-0`, big integers and booleans in every string field; `null` still reaches validation. No other existing test changed outcome. Killed by M8. Sound.                                                                          |
| T13 `CanonicalUuidBinding`                                                                     | The binder-editor mechanism is right (a converter would fall back to the lenient `UUIDEditor`). It covers every controller's UUID path variables, including `/internal`. **But it trims, which is BUG-2.**                                                           |
| AC-8.1 `userId=-` on the authentication lookup; formatted multi-line SQL                       | Acceptable. The lookup runs before the user is known, and every later statement carries the user id. Continuation lines carry no pattern, so `grep requestId` tooling sees only the first line of each statement.                                                    |
| Review A: container lifecycle, support annotations, extra "Slow call" WARN, Kit instead of Gus | Verified: no `@DirtiesContext` anywhere, no context closed early, the build is stable across 3 reruns. The "Slow call" WARN is still logged at 1 s waits (above the 500 ms threshold).                                                                               |
| ICU project-name index                                                                         | Agrees with Java for every Unicode 13 pair qa tried (U+0130 with and without the combining dot, U+1E9E/ß, final sigma, U+212A, U+01C5, U+10400, dotless ı, ß/ss). It disagrees for Unicode 14+ characters (BUG-3). qa's `QaSchemaTest` now pins the full expression. |
| E81 placeholder-only value print                                                               | **Doesn't hold** for a real value that contains `${` (BUG-1). It holds for blank and unset values (`QaDatabaseSettingsTest`).                                                                                                                                        |
| Build time about 3×                                                                            | Now about 3.6× with qa's 137 tests (Q5).                                                                                                                                                                                                                             |

## F1 review (qa lines edited by backend-swe)

| Item                                   | Finding                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
|----------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| F1-a `QaSchemaTest` port               | Faithful. Same class, method names and `@DisplayName`s; the three properties are still checked (columns with type, length and nullability; enum values from the check constraints; unique constraints and indexes plus FKs), from `information_schema` and `pg_constraint` with `string_agg`. No check dropped. **Weakness, now fixed by qa:** unique indexes were listed by plain columns only, so `uk_projects_org_id_name_lower` showed as `PROJECTS(ORG_ID)` (M1 would have survived). The query now prints each key with `pg_get_indexdef(index, k, true)` in index order and skips partial indexes. Expected: `USERS(email)`, `INVITATIONS(token)`, `ORG_MEMBERSHIPS(user_id,org_id)`, `PROJECT_MEMBERSHIPS(user_id,project_id)`, `PROJECTS(org_id,lower(name::text COLLATE "und-x-icu"))`. Still 3 tests. |
| F1-b `QaProjectLoggingTest` 31, 69, 74 | Exactly as allowed: the import and `@TestPropertySource` deleted, and the Javadoc sentence is the spec's wording.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| F1-c expected values for V2 to V7      | Equal to the as-built ERD: `varchar(254)`/`(100)`, NOT NULL set, `varchar(32)` enums, `timestamp(6) with time zone` on the 10 columns, `audit_log` and its 4 FKs gone, 7 FKs left.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| F1-d `Instant` lines in 6 files        | Only `LocalDateTime` → `Instant` with the same offsets (`plusDays(7)` → `plus(7, DAYS)`, `minusMinutes(1)` → `minus(1, MINUTES)`, `minusSeconds(1)` unchanged), and at most one added `ChronoUnit` import per file.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| F1-e `QaInputEdgeCasesTest`            | As allowed: `@DisplayName` and the method name say "malformed", the assertion is 400 MALFORMED_REQUEST with no `errors` check, and the unused `Map` import is removed.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |

## Manual run (AC-1.7, AC-8.2, T14 flow)

Setup: container `qa-auth-persistence-pg` (`postgres:17` 17.11, `datlocprovider` `b`, `C.UTF-8`, UTF8) on 127.0.0.1:
15432. auth-service jar with
`--spring.profiles.active=dev --spring.datasource.url=jdbc:postgresql://localhost:15432/auth_service` and no `AUTH_DB_*`
variable. Gateway jar with `dev` on 8080. Both ran on Corretto 17. Postgres.app (5432) and Camunda weren't touched. The
container and its volume were removed afterwards.

| Step     | Call                                                                                                             | Status                                             |
|----------|------------------------------------------------------------------------------------------------------------------|----------------------------------------------------|
| Start 1  | Flyway: "Empty Schema", migrating V1 to V7, "Successfully applied 7 migrations ... now at version v7"            | OK                                                 |
| 1        | Ana registers, logs in, creates Acme (via gateway)                                                               | 201, 200, 201 (`createdAt` ends in `Z`)            |
| 2        | Ana creates Checkout, invites cai@example.com MEMBER                                                             | 201, 201 (`expiresAt` = `createdAt` + 7 days, `Z`) |
| 3        | Cai accepts anonymously, logs in                                                                                 | 201, 200                                           |
| 4        | Ana adds Cai to Checkout as TESTER; Cai views Checkout                                                           | 201; 200 `myRole` TESTER                           |
| 7a       | Cai's access check on 8081 before removal                                                                        | 200 `role` TESTER                                  |
| 5        | Ana removes Cai; Cai views Checkout                                                                              | 204; 403 FORBIDDEN                                 |
| 6        | AC-7.6: `GET /internal/projects/{checkout}/access` via gateway                                                   | 404 without `code`                                 |
| 7b       | Cai's access check on 8081 after removal                                                                         | 403 FORBIDDEN                                      |
| Restart  | Flyway: "Successfully validated 7 migrations", "Current version ... 7", "up to date. No migration necessary."    | OK                                                 |
| R1 to R5 | Ana logs in, lists orgs (Acme, same `createdAt`), views Checkout (ADMIN); Cai logs in, lists orgs (Acme, MEMBER) | 200 each                                           |
| R6, R7   | Ana re-adds Cai as TESTER; Cai views Checkout (step 4's view)                                                    | 201; 200 `myRole` TESTER                           |

- **p95 (informational, 60 calls, warm JVM):** `GET {acme-projects}/{checkout}` through the gateway 99.8 ms (median 38.2
  ms); access check on 8081 64.4 ms (median 22.5 ms). Both are under the 100 ms target, the first only just.
- **AC-8.2 / D14 log scan** (two auth-service logs, 17 521 lines; gateway log, 102 lines): no occurrence of
  `auth_service_dev_only`, the dev JWT secret, `Secret123!`, either email, either full name, `$2a$`, `Failing row`,
  `Key (` or `binding parameter`. All 94 `org.hibernate.SQL` lines of the first run carry a request id; statements show
  `?` placeholders only.

## Open questions for pm

1. **Q1:** D11 rule T2's set of invisible characters is closed. Should names made only of U+3164, U+2800, U+115F,
   U+FFA0, U+00AD, U+180E, U+200E/U+200F, U+2061 or U+034F (all 201 today), and a ZWSP-prefixed duplicate project name,
   stay accepted until FU-2?
2. **Q2:** BUG-1. Do you accept AC-1.6's failing variant for this phase, or route it to backend-swe? Is S3 right, or
   S2 (which would make the verdict Not signed off)?
3. **Q3:** BUG-3. Which rule applies to Unicode 14+ case pairs? A10's correction ("Emails stay safe") also needs
   rewording.
4. **Q4:** BUG-4. Does the 254-character email limit apply to the stored (lower-cased) value?
5. **Q5:** The build is now about 3.6× H2 (4m51s), and qa's additions are about 55 s of it. Accept, or should qa trim
   scratch-app tests?
6. **Q6:** BUG-5. What's the wanted answer when the database pool is exhausted (login 401 today)?

## Gaps and risks

| #   | Item                                                                                                                                                                     | Why                                                                                        | Ask                                              |
|-----|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------|--------------------------------------------------|
| R1  | `ddl-auto: validate` misses `timestamptz` → `timestamp`, `integer` → `bigint`, dropped NOT NULL and shortened lengths (qa probe on scratch databases: start-up succeeds) | Hibernate only checks compatible JDBC types. `QaSchemaTest` covers the test database only. | pm: accept, or add a start-up schema check later |
| R2  | Flyway starts when the history has a version newer than the code (V8 ghost row: start-up succeeds)                                                                       | Flyway's default `ignoreMigrationPatterns` `*:future`                                      | Informational (supports rollbacks)               |
| R3  | Unicode-blank gap (Q1); `"\u200BCheckout"` is a new project next to "Checkout"                                                                                           | Spec's closed set                                                                          | pm                                               |
| R4  | Jackson decodes the overlong UTF-8 `C0 80` as U+0000 (then 400 VALIDATION_FAILED)                                                                                        | Jackson's decoder; every case still ends in 400                                            | Informational                                    |
| R5  | Body UUIDs accept a leading space (`{"userId":" <id>"}` → 201)                                                                                                           | A9, out of scope                                                                           | Informational                                    |
| R6  | Enum columns are `varchar(32)` in V4 but `varchar(255)` in the entities (pattern item 7 says entities mirror lengths)                                                    | E71 changed only the migration                                                             | pm: note for the pattern                         |
| R7  | ERD: stale "proposed (auth-service-persistence)" markers remain on `users.password_hash` and in the `invitations` note, although the legend says none should be left     | Docs                                                                                       | pm or backend-swe                                |
| R8  | `dbml2sql` 10.3.1 prints `uk_projects_org_id_name_lower` with the expression first (`(lower(...), org_id)`); the DBML source has `org_id` first                          | Tool quirk; the migration is correct                                                       | Informational                                    |
| R9  | Deadlock (40P01) proven on a scratch app with `deadlock_timeout` 200 ms; on the shared database the 1 s lock timeout races PostgreSQL's 1 s deadlock check               | Test design                                                                                | —                                                |
| R10 | Docker is required for every build; there's no CI                                                                                                                        | Q1 = A                                                                                     | —                                                |

## qa changes in this round

- New: `qa/QaMigrationsTest` (8), `qa/QaDatabaseRulesTest` (40), `qa/QaLockTimeoutTest` (8), `qa/QaStrictInputTest` (
  73), `qa/QaTimeZoneTest` (2), `qa/QaDatabaseSettingsTest` (5), `qa/QaDevLoggingTest` (1).
- Changed: `qa/QaSchemaTest#constraintsMatchTheErd`, the unique-index query and its expected values (same 3 tests).
- Docs: this report and `docs/qa/auth-service-persistence-test-plan.md`.
- Nothing under `src/main`, no configuration, no `build.gradle`, and no backend-swe test was edited. No `@Disabled`,
  assumption or `@DirtiesContext` was added. No git state was changed.

## Re-verification (fix round 1), 2026-10-15

### Verdict

**Signed off with known issues.** Tested on HEAD `79212be` plus the uncommitted tree after FR1 (E83, E84), on
2026-10-15.

- **All 36 ACs pass:** P0 6 of 6, P1 23 of 23, P2 7 of 7. None is blocked or not tested.
- **BUG-1 (S2, pm's rating) and BUG-2 (S3) are fixed and verified.** No S1, S2 or S3 bug is open.
- **Four S4 bugs stay open, each accepted by pm as a follow-up:** BUG-3 (FU-17), BUG-4 (FU-18), BUG-5 (FU-19), and
  BUG-6, which is new in this round. pm still has to decide on BUG-6.
- **Build time:** 4m30s before qa's trimming and 4m03s after, which meets FU-21's ≈4m05s target. No check was dropped.

This replaces the conditional verdict above.

### AC results (36)

| AC           | Priority | Before FR1       | After FR1 | Evidence                                                                                                                                                                                                                                                                                                                                                                                                          |
|--------------|----------|------------------|-----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| AC-1.5       | P1       | Pass             | Pass      | BE `DatabaseStartupTest` AC-1.5 rows, plus the new rows for an exact `${AUTH_DB_*}` placeholder. QA `QaDatabaseSettingsTest`, now 19 tests: messages are checked exactly, and `#exactlyOneUnresolvedPlaceholderIsUnset` (3) passes.                                                                                                                                                                               |
| AC-1.6       | P1       | **Fail (BUG-1)** | **Pass**  | BE AC-1.6 (BUG-1) rows, 3 passwords. QA `#aValueThatOnlyContainsDollarOrBracesIsSet` (9 values, including `${`, `}${`, `$${A}`, `${A}${B}`, `x${A}`), and `#theServiceStartsWithADollarBracePasswordAndNeverPrintsIt`. In that test, a real role with the password `Qa${Pw-61 Pw${QA}61 Pw$QA{61}` logs in, the service starts and answers register 201, and no log line contains the password or any part of it. |
| AC-7.1       | P2       | **Fail (BUG-2)** | **Pass**  | BE `CanonicalUuidTest`, with 5 new rows. QA `#nonCanonicalUuidFormsAreMalformed`, now 26 rows (10 new): leading and trailing space, leading and trailing tab, and trailing no-break space, on `/api/orgs`, projects, `/internal/projects/.../access`, and the org and project member deletes.                                                                                                                     |
| The other 33 |          | Pass             | Pass      | Full clean build below                                                                                                                                                                                                                                                                                                                                                                                            |

Counts: Pass 36, Fail 0, Blocked 0, Not tested 0.

### How the fixes were verified

- **BUG-1, start-up runs on the jar** (Corretto 17, unreachable database URL, process environment controlled):
    - `Qa${Pw-61`: the settings check passes, and start-up fails only on the connection ("Connection to localhost:1
      refused"). The value appears nowhere in the 204-line output.
    - `Pw${QA}61` and `Pw$QA{61}`: same result.
    - `${AUTH_DB_PASSWORD}` (the variable set to its own placeholder): Spring's placeholder resolution fails first ("
      Circular placeholder reference 'AUTH_DB_PASSWORD'"). Boot prints `Value: "${AUTH_DB_PASSWORD}"`, which is only the
      placeholder text and the variable's name. **Assessed acceptable:** it names the variable and holds no secret. qa
      pins it in `#aVariableSetToItsOwnPlaceholderPrintsOnlyThePlaceholder`.
    - But see BUG-6 for a variant that does print part of a value.
- **BUG-1, in the build output:** the full build's JUnit XML contains none of `Qa${Pw-61`, `Pw${QA}61`, `Pw$QA{61}`,
  `Qa-Db-Pw-Marker-61` or `Wrong-Pw-QA-61`, except inside test names (`<testcase name=...>`). None appears in any
  captured output.
- **Mutation check of the fixes** (a `/tmp` copy, since deleted):
    - Putting back "contains `${`" in `DatabaseSettingsCheck` fails 9 of qa's 19 `QaDatabaseSettingsTest` tests,
      including the real start-up.
    - Putting back `trim()` in `CanonicalUuidEditor` fails 9 of qa's 10 new whitespace rows. The tenth, a no-break
      space, is also rejected by the old code, because `trim()` doesn't remove U+00A0.
- **W items:**

  | Item | Status |
    |---|---|
  | W2 | Fixed. The BUG-2 rows and the 7-digit first group are in `CanonicalUuidTest`. |
  | W3 | Fixed. `DevSqlLoggingTest` re-applies the dev profile's `org.hibernate.SQL` level from the environment and restores the previous level afterwards. |
  | W4 | Fixed. **9 timestamp columns is correct.** V2 converted 10 columns, but `audit_log.created_at` was dropped with its table in V7. qa's earlier "pins all 10" was wrong: `QaSchemaTest` also pins 9. |
  | W7 | Fixed. Every test in the 6 classes has a `@DisplayName`. |
  | W1, W5, W6 | Unchanged by design (spec FR1). qa's tests cover them. |

### Bug status

| ID                             | Severity | Status                                                                                                | Owner                    |
|--------------------------------|----------|-------------------------------------------------------------------------------------------------------|--------------------------|
| BUG-auth-service-persistence-1 | S2 (pm)  | **Fixed, verified** (E83: `DatabaseSettingsCheck` after binding, a plain `DatabaseProperties` record) | —                        |
| BUG-auth-service-persistence-2 | S3       | **Fixed, verified** (E84: no trimming)                                                                | —                        |
| BUG-auth-service-persistence-3 | S4       | Open, accepted as FU-17 (phase 3)                                                                     | pm, backend-swe          |
| BUG-auth-service-persistence-4 | S4       | Open, accepted as FU-18                                                                               | backend-swe (phase 3)    |
| BUG-auth-service-persistence-5 | S4       | Open, accepted as FU-19 (with FU-1)                                                                   | pm, backend-swe          |
| BUG-auth-service-persistence-6 | S4       | **New, open**                                                                                         | pm (decide), backend-swe |

### BUG-auth-service-persistence-6: a password that contains a reference to its own variable is printed in the start-up failure

- Severity: S4 Minor. It needs a password that literally contains `${AUTH_DB_PASSWORD}` (or `${AUTH_DB_URL}` or
  `${AUTH_DB_USERNAME}` in that variable).
- Affected: auth-service start-up (Spring placeholder resolution of `application.yaml`'s `${AUTH_DB_*}`)
- Linked AC: AC-1.6 (variant); G12 / D5 "never logged"
- Commit tested: `79212be` + working tree after FR1
- Steps to reproduce:
    1.
    `env JWT_SECRET="$(head -c 64 /dev/urandom | base64)" AUTH_DB_URL=jdbc:postgresql://localhost:1/none AUTH_DB_USERNAME=qa_user 'AUTH_DB_PASSWORD=Qa-Marker-61${AUTH_DB_PASSWORD}' java -jar build/libs/auth-service-0.0.1-SNAPSHOT.jar --server.port=0`
- Expected: start-up fails without printing any part of the value.
- Actual: the report's `Value:` line shows only `"${AUTH_DB_PASSWORD}"`, but its `Reason:` line prints the raw value:
  `Circular placeholder reference 'AUTH_DB_PASSWORD' in value "Qa-Marker-61${AUTH_DB_PASSWORD}" <-- "${AUTH_DB_PASSWORD}"`.
- Evidence: the exception comes from Spring's `PlaceholderResolutionException`, raised before any auth-service code
  runs.
- Note: Spring resolves `${NAME}` placeholders inside an environment variable's value. So a password that contains a
  placeholder for an existing property or variable (for example `${HOME}`) is probably substituted silently, which gives
  a wrong password but prints nothing. qa didn't verify that case.
- Suggested owner: pm. Accept as a documented rule ("database passwords must not contain `${`"), for example in the
  README's configuration section. A code change would need handling outside today's allowed files.

### Build time (FU-21)

| Run                                      | Tests                                                     | Time              | qa's phase-4 classes |
|------------------------------------------|-----------------------------------------------------------|-------------------|----------------------|
| Before trimming (FR1 tree, qa unchanged) | 2002 (889 + 1113), 0 failed                               | **4m30s** (270 s) | about 45 s           |
| After trimming (final)                   | **2021 (889 backend-swe + 1132 qa), 0 failed, 0 skipped** | **4m03s** (243 s) | 26.6 s               |

What changed, with every check kept:

- `QaStrictInputTest`, 16.8 s → 6.0 s. It now has 83 tests (10 new rows). The fixture is seeded once and reused while
  the database is unchanged. A state snapshot after each test triggers a fresh seed whenever anything changed.
- `QaLockTimeoutTest`, 12.5 s → 7.3 s. The 6 locked writes now run at the same moment behind one lock, each still
  asserted with SoftAssertions, plus "nothing stored" and "under 7 s". The class has 3 tests instead of 8: the rows
  became one test.
- `QaMigrationsTest`, 7.2 s → 4.6 s. The "given" migrations run through the Flyway API instead of an application start.
- `QaDatabaseSettingsTest` grew from 5 to 19 tests (2.7 s) for BUG-1.

### Suites run (final)

| Command                                                                                                                                                                                | Result                                                          |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|
| `cd auth-service && ./gradlew clean build`                                                                                                                                             | BUILD SUCCESSFUL in 4m03s: 2021 tests, 0 failed, 0 skipped      |
| Concurrency classes `--rerun` ×3 (`RegistrationRaceTest`, `QaConcurrencyTest`, `QaFixRoundTest`, `ConcurrentWritesTest`, `RowLockTimeoutTest`, `LockFailureTest`, `QaLockTimeoutTest`) | 3 of 3 green, 135 tests each, 0 failed                          |
| `cd api-gateway && ./gradlew build`                                                                                                                                                    | BUILD SUCCESSFUL: 411 tests, 0 failed, 4 skipped (D10 baseline) |

### qa changes in this round

- `qa/QaDatabaseSettingsTest`: stricter messages; BUG-1 unit rows for the settings check; a real start-up with a `$`/
  `{`/`${` password on its own role (created and dropped on the test server, with its scratch database); the
  self-placeholder case.
- `qa/QaStrictInputTest`: 10 whitespace UUID rows; fixture reuse.
- `qa/QaLockTimeoutTest`: the concurrent locked writes.
- `qa/QaMigrationsTest`: Flyway API for setup.
- No other file was changed. No `@Disabled`, assumption or `@DirtiesContext` was added, and no git state was changed.
  Scratch files and the mutation copy were deleted.
- **Gradle daemons:** my own daemon was stopped. A second Gradle 9.5.1 daemon (Corretto 21, started 12:36 while qa was
  paused) isn't qa's, so `./gradlew --stop` wasn't used, because it would have stopped that one too.
