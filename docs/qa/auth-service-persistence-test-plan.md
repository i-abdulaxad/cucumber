# Test plan: auth-service phase 4 (auth-service-persistence)

## 1. Scope and references

| Reference          | Value                                                                                                                                            |
|--------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| Spec               | `docs/specs/auth-service-persistence.md` (Ready; review points A to D passed, E pending qa), 36 ACs: P0 6, P1 23, P2 7; manual AC-1.7 and AC-8.2 |
| Decisions in scope | D1 to D18, "Database pattern for every service" (items 1 to 19), E59 to E82, X4, F1-a to F1-e, P17 to P21a                                       |
| Contract           | `docs/api/auth-service.openapi.yaml` 1.3.0 (UTC `Timestamp` schema, D11 text rules, D12 strict strings, D13 canonical UUIDs)                     |
| ERD                | `docs/erd/cucumber.dbml`, `auth_service` group (as-built V1 to V7); `dbml2sql` expected: 21 tables, 22 FKs, 8 enums                              |
| README             | `auth-service/README.md` ("Running locally with PostgreSQL", "Database and migrations")                                                          |
| Code               | HEAD `79212be` plus the uncommitted phase-4 tree (migrations `V1` to `V7`, new configuration classes, validators, tests)                         |
| Test database      | Testcontainers `postgres:17`, builtin `C.UTF-8`, `lock_timeout` 1 s in tests (E82), 5 s in `application.yaml`                                    |

In scope: every AC of US-1 to US-8, the F1 edits to qa's files, an audit of backend-swe's new tests with a mutation
check, adversarial checks of migrations, database rules, time zones, locks and pools, strict input, settings and dev
logging, and the manual run through the gateway.

Out of scope (spec "Out of scope"): production deployment, H2 data migration, audit-log writing, password content rules,
NFC normalisation (FU-2), errors outside Spring MVC (FU-1), UUIDs inside JSON bodies (A9), any gateway change.

## 2. Testability findings sent to pm

The spec is testable as written. No AC was sent back before execution. These points came up during execution and go to
pm with the report (they're questions about rules, not blocked ACs):

1. **The Unicode-blank set (D11 rule T2) is closed.** It names five invisible format characters. Names made only of
   other invisible code points (U+3164 HANGUL FILLER, U+2800 BRAILLE PATTERN BLANK, U+115F, U+FFA0, U+00AD, U+180E,
   U+200E/U+200F, U+2061, U+034F) are accepted, and so is a project name that differs from an existing one only by a
   leading U+200B. Is that intended until FU-2?
2. **A10 and D8 item 6 assume Java's and PostgreSQL's case mappings agree.** They agree for Unicode 13. Java 17 (the
   toolchain) uses Unicode 13; PostgreSQL 17 uses Unicode 15.1 (builtin) and the image's ICU. Characters added in
   Unicode 14 disagree (BUG-3).
3. **The length rule for emails counts the value as sent, but the column holds the lower-cased value.** Lower-casing can
   lengthen a string (U+0130 becomes two characters), so a valid 252-character email can be 316 characters when stored (
   BUG-4).
4. **Behaviour under connection-pool exhaustion isn't specified** (login answers 401 UNAUTHENTICATED, other requests a
   non-Problem 500).

## 3. Approach and levels

| Level                         | Tooling                                                                                                   | Use                                                                                       |
|-------------------------------|-----------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| Integration (full context)    | `@AuthServiceIntegrationTest` + MockMvc on the shared test database                                       | API behaviour, direct SQL in rolled-back transactions, catalog reads                      |
| Start-up (scratch database)   | `SpringApplicationBuilder` on `support/ScratchDatabase`, port 0, real HTTP                                | Migrations, drift, settings, time zone from start-up, deadlock with its own lock settings |
| Flyway API (scratch database) | `org.flywaydb.core.Flyway` with `target`                                                                  | One-version-at-a-time migration, V1 snapshot                                              |
| Mutation check                | A throw-away copy of `auth-service` under `/tmp`, one mutant group per run, targeted test classes         | Audit of backend-swe's tests (production code in the repository is never edited)          |
| Manual                        | `java -jar` both services with `dev`, own `postgres:17` on port 15432, `curl` through the gateway on 8080 | AC-1.7, AC-8.2, the T14 flow, restart persistence, p95                                    |

Rules followed: no `@DirtiesContext` (P21a); new qa classes reuse existing context configurations (
`@AuthServiceIntegrationTest` + `@Import(ProjectFixture.class)`, and the dev-profile configuration of
`DevSqlLoggingTest`), so no new cached context is added; start-up tests use scratch databases that are always dropped.

## 4. Test cases

"BE" is backend-swe's test, "QA" is qa's. QA classes are under `auth-service/src/test/java/com/example/authservice/qa/`.

### US-1: PostgreSQL with a Flyway-managed schema

| ID      | AC     | Level      | Preconditions and data                     | Steps                                                                                                                                                             | Expected                                                                                                        | Priority |
|---------|--------|------------|--------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------|----------|
| TC-1.1  | AC-1.1 | Start-up   | Scratch database                           | BE `DatabaseStartupTest#aFreshDatabaseIsMigratedAtStartUp`                                                                                                        | History V1..V7 in order, all successful; 6 tables + `flyway_schema_history`; register 201                       | P0       |
| TC-1.1a | AC-1.1 | Flyway API | Scratch database                           | QA `QaMigrationsTest#everyMigrationAppliesOneByOneAndV1IsTheAsBuiltSchema`                                                                                        | Each version applies alone; V1 = the phase-2 as-built ERD in PostgreSQL types; enum values unchanged through V7 | P0       |
| TC-1.1b | AC-1.1 | Static     | Migration files                            | QA `QaMigrationsTest#migrationFilesFollowTheRules`                                                                                                                | `V<n>__snake_case.sql`, contiguous from 1, no `IF [NOT] EXISTS`, no explicit transactions, no `CREATE TYPE`     | P1       |
| TC-1.1c | AC-1.1 | Flyway API | Database with libc `en_US.utf8`            | QA `#theMigrationsApplyOnADatabaseWithAnotherLocale`                                                                                                              | Same columns as on builtin `C.UTF-8`                                                                            | P2       |
| TC-1.1d | AC-1.1 | Start-up   | Two scratch databases                      | QA `#hibernatesDdlForTheEntitiesMatchesTheMigratedColumns`                                                                                                        | Hibernate's own DDL for today's entities equals the migrated columns, except enum lengths (255 vs 32)           | P1       |
| TC-1.2  | AC-1.2 | Start-up   | Scratch database                           | BE `#dataSurvivesARestart`; manual restart (section 7)                                                                                                            | History unchanged; login 200; org listed                                                                        | P0       |
| TC-1.3  | AC-1.3 | Start-up   | Migrated scratch database plus drift       | BE `#theServiceRefusesToStartOnASchemaThatDoesNotMatchItsEntities` (2 rows); QA `#startUpRefusesDriftThatFlywayOrValidateCanSee` (boolean to text, table renamed) | Start-up fails naming the object; history and tables unchanged                                                  | P1       |
| TC-1.4  | AC-1.4 | Start-up   | Migrated scratch database                  | BE `#aMigrationEditedAfterItWasAppliedStopsTheStartUp` (V1); QA drift rows: checksum of V5, latest version marked failed                                          | Validation error naming the version; nothing changed                                                            | P1       |
| TC-1.5  | AC-1.5 | Start-up   | Process environment removed                | BE `#withoutDatabaseSettingsTheServiceRefusesToStart` (6); QA `QaDatabaseSettingsTest` (all three missing at once; tab, line break, empty)                        | Failure names the setting(s); no set value printed                                                              | P1       |
| TC-1.6  | AC-1.6 | Start-up   | Wrong password `Wrong-Pw-QA-61`            | BE `#aWrongDatabasePasswordStopsTheStartUpWithoutBeingPrinted`; manual: a set password containing `${`                                                            | Never printed in logs or the cause chain                                                                        | P1       |
| TC-1.7  | AC-1.7 | Manual     | Own `postgres:17` on 15432, no `AUTH_DB_*` | Start with `dev` and `--spring.datasource.url=...15432...`; flow; restart                                                                                         | Flyway applies V1..V7, then "up to date"; data persists                                                         | P1       |

### US-2: Database rules

| ID      | AC                 | Level       | Preconditions and data | Steps                                                                                                                                              | Expected                                                                             | Priority |
|---------|--------------------|-------------|------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------|----------|
| TC-2.1  | AC-2.1             | Integration | Projects fixture       | BE `DatabaseRulesTest#requiredColumnsRejectAMissingValue` (19 rows)                                                                                | 23502                                                                                | P1       |
| TC-2.1a | AC-2.1 / D8 item 2 | Integration | Projects fixture       | QA `QaDatabaseRulesTest#lengthsAreCountedInCodePoints`                                                                                             | 100 supplementary code points fit `varchar(100)`, 101 is 22001; 254/255 for emails   | P1       |
| TC-2.2  | AC-2.2             | Integration | Projects fixture       | BE `#storedEmailsAreLowerCaseSoUniquenessIgnoresLetterCase` (4)                                                                                    | 23514 / 23505                                                                        | P1       |
| TC-2.2a | AC-2.2             | Integration | Register via API       | QA `#unicodeEmailsAreStoredInALowerCaseTheDatabaseAccepts` (U+0130, U+1E9E, Greek, U+212A, U+01C4, Cyrillic, Armenian, ASCII)                      | 201; stored = Java's normalised value; `lower()` agrees                              | P1       |
| TC-2.3  | AC-2.3             | Integration | Projects fixture       | BE `#projectNamesAreUniquePerOrganizationIgnoringLetterCase` (4), `#izmirAndIzmirCanCoexist`, `#icuLowerCasingMatchesJava`                         | 23505 / success                                                                      | P1       |
| TC-2.3a | AC-2.3             | Integration | API                    | QA `#theProjectNameIndexAgreesWithTheApplicationsRule` (9 pairs)                                                                                   | 409 PROJECT_NAME_TAKEN exactly when Java's rule says equal; never a generic CONFLICT | P1       |
| TC-2.4  | AC-2.4             | Integration | Projects fixture       | BE `#enumColumnsAcceptOnlyTheDocumentedValues` (7), `#everyEnumColumnIsAShortVarcharWithANamedCheck`; QA `#enumChecksAreExactAndCaseSensitive` (4) | 23514                                                                                | P1       |
| TC-2.5  | AC-2.5             | Integration | Projects fixture       | BE `#aSeatLimitIsPositiveOrUnlimited` (3); QA `#positiveSeatLimitsAreAccepted` (1, MAX)                                                            | 23514 / success                                                                      | P1       |
| TC-2.6  | AC-2.6             | Integration | Projects fixture       | BE `#aRowThatOthersDependOnCantBeDeleted` (4); QA `#everyForeignKeyRestrictsDeletesOnItsOwn` (7, one per FK, named in the error)                   | 23503 naming the FK; nothing deleted                                                 | P1       |
| TC-2.7  | AC-2.7             | Catalog     | —                      | BE `#everyForeignKeyIsIndexedAndRestrictsDeletes`; QA `#everyForeignKeyLeadsAValidFullIndex`, `#everyConstraintHasItsReadableName`; `QaSchemaTest` | Every FK leads a valid, non-partial index; delete rule `r`; D4 names                 | P2       |

### US-3: Existing behaviour on PostgreSQL

| ID     | AC            | Level       | Preconditions and data    | Steps                                                                                                                   | Expected                                                                 | Priority |
|--------|---------------|-------------|---------------------------|-------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------|----------|
| TC-3.1 | AC-3.1        | Build       | Docker running            | `cd auth-service && ./gradlew clean build`                                                                              | 0 failed, 0 skipped; pre-existing qa classes keep 976 tests; backend 875 | P0       |
| TC-3.2 | AC-3.2        | Integration | Acme fixture, Gus invited | BE `RowLockTimeoutTest` (2 rows + value); QA `QaLockTimeoutTest` (6 project/member writes, burst of 20, deadlock 40P01) | 409 CONFLICT within 7 s; one handler WARN; nothing stored; pool recovers | P0       |
| TC-3.3 | AC-3.3        | Integration | Default logging           | BE `ConcurrentWritesTest` AC-3.3 ×5; QA `QaDatabaseRulesTest#theServerErrorDetailNeverReachesAMessage`                  | One winner; no email in body or log; no DETAIL in any exception message  | P0       |
| TC-3.4 | AC-3.4        | Integration | Projects fixture          | BE AC-3.4 ×5                                                                                                            | One 201, four PROJECT_NAME_TAKEN                                         | P1       |
| TC-3.5 | AC-3.5        | Integration | Projects fixture          | BE AC-3.5 ×5                                                                                                            | One 201, one ALREADY_MEMBER                                              | P1       |
| TC-3.6 | AC-3.6        | Integration | Seeded orgs and members   | BE `ListOrderTest` (2)                                                                                                  | Code-point order                                                         | P1       |
| TC-3.x | AC-3.2 to 3.5 | Build       | —                         | Concurrency classes `--rerun` 3×                                                                                        | Green 3 runs in a row                                                    | P0       |

### US-4: UTC timestamps

| ID     | AC     | Level                  | Preconditions and data        | Steps                                                                                                                             | Expected                                                                                        | Priority |
|--------|--------|------------------------|-------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|----------|
| TC-4.1 | AC-4.1 | Integration            | Projects fixture, Kit invited | BE `UtcTimestampsTest` (9)                                                                                                        | Every field matches the UTC pattern                                                             | P1       |
| TC-4.2 | AC-4.2 | Integration / start-up | Adjustable clock; zones       | BE `ServiceClockTest` (3 zones); QA `QaTimeZoneTest` (JVM and session zone America/St_Johns and Pacific/Kiritimati from start-up) | Body and stored value are the same instant; read-back equals the POST value; 7-day expiry exact | P1       |
| TC-4.3 | AC-4.3 | Integration            | Adjustable clock              | BE `ServiceClockTest` AC-4.3 (2)                                                                                                  | 201 at 08:59:59.999999, 410 at 09:00:00                                                         | P2       |
| TC-4.4 | AC-4.4 | Catalog                | —                             | BE `TimestampColumnsTest`; `QaSchemaTest#columnsMatchTheErd` (all 10 columns pinned)                                              | `timestamp with time zone`                                                                      | P1       |

### US-5: Unstorable text

| ID     | AC     | Level       | Preconditions and data        | Steps                                                                                                                                                                                                                                                                                       | Expected                                                                | Priority |
|--------|--------|-------------|-------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------|----------|
| TC-5.1 | AC-5.1 | Integration | Projects fixture, Kit invited | BE `UnstorableTextTest` (12); QA `QaStrictInputTest#everyControlCharacterIsRejected` (all 65 C0/C1 in org and full names), `#loneSurrogatesAreRejectedInEveryPosition` (13), `#bodiesThatArentValidUtf8AreRejected` (6), `QaDatabaseRulesTest#postgresCantStoreNulAndChangesALoneSurrogate` | 400 VALIDATION_FAILED for the field; value never echoed; nothing stored | P0       |
| TC-5.2 | AC-5.2 | Integration | Projects fixture              | BE (2); QA `#onlyWhatTrimRemovesIsIgnoredInProjectNames` (5)                                                                                                                                                                                                                                | U+0000..U+0020 trimmed (409); DEL and C1 are 400                        | P1       |
| TC-5.3 | AC-5.3 | Integration | Projects fixture              | BE `UnstorableTokenTest` (3)                                                                                                                                                                                                                                                                | 404 INVITATION_NOT_FOUND or the firewall's 400; never 5xx               | P1       |
| TC-5.4 | AC-5.4 | Integration | Projects fixture              | BE (4)                                                                                                                                                                                                                                                                                      | Stored with the exact code points                                       | P1       |

### US-6: Visible names and strict JSON types

| ID     | AC     | Level       | Preconditions and data | Steps                                                                                                                                                                 | Expected                               | Priority |
|--------|--------|-------------|------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------|----------|
| TC-6.1 | AC-6.1 | Integration | Projects fixture       | BE `NameRulesTest` (5), `NameConstraintsTest`; QA `#everyUnicodeBlankCodePointIsBlank` (every whitespace or space code point plus the 5 format characters, and a mix) | 400 VALIDATION_FAILED for the field    | P1       |
| TC-6.2 | AC-6.2 | Integration | Projects fixture       | BE (6); QA `#nameLimitsCountCodePointsInEveryNameField` (8: supplementary letters, combining marks, untrimmed spaces, rename, accept)                                 | Limits in code points, counted as sent | P1       |
| TC-6.3 | AC-6.3 | Integration | Projects fixture       | BE `StrictJsonTypesTest` (8); QA `#numbersAndBooleansAreMalformedInEveryStringField` (12: floats, exponents, `-0`, big integers, booleans)                            | 400 MALFORMED_REQUEST; nothing stored  | P1       |
| TC-6.4 | AC-6.4 | Integration | —                      | BE (1); QA `#nullIsAValidationErrorInEveryStringField` (10)                                                                                                           | 400 VALIDATION_FAILED with the field   | P2       |

### US-7: Canonical UUIDs

| ID     | AC     | Level       | Preconditions and data | Steps                                                                                                                                          | Expected                              | Priority |
|--------|--------|-------------|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------|----------|
| TC-7.1 | AC-7.1 | Integration | Projects fixture       | BE `CanonicalUuidTest` (6); QA `#nonCanonicalUuidFormsAreMalformed` (16 forms over every path variable); manual probe of whitespace-padded ids | 400 MALFORMED_REQUEST; nothing stored | P2       |
| TC-7.2 | AC-7.2 | Integration | Projects fixture       | BE (1); QA `#mixedCaseCanonicalIdsIdentifyTheResource`                                                                                         | 200, id in lower case                 | P2       |

### US-8: Dev SQL logging

| ID     | AC     | Level       | Preconditions and data      | Steps                                                                                                                         | Expected                                                                                                                                          | Priority |
|--------|--------|-------------|-----------------------------|-------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|----------|
| TC-8.1 | AC-8.1 | Integration | `dev` profile, Acme fixture | BE `DevSqlLoggingTest`; QA `QaDevLoggingTest` (register, login, org, invite, accept, login, a database-rejected registration) | `org.hibernate.SQL` lines with the request id; no email, name, password, hash, token, JWT, dev DB password, dev JWT secret, DETAIL or bound value | P2       |
| TC-8.2 | AC-8.2 | Manual      | Manual run logs             | `grep` both auth-service logs and the gateway log                                                                             | Migration version shown; no password or secret                                                                                                    | P2       |

### Cross-cutting

| ID    | Ref              | Level             | Steps                                                                            | Expected                                                                                                                                              |
|-------|------------------|-------------------|----------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| TC-X1 | D4               | Integration       | QA `QaDatabaseRulesTest#flywayAndHibernateRunWithTheD4Settings`                  | Clean disabled, no baseline, validate on migrate, location `classpath:db/migration`, `ddl-auto: validate`, `org.hibernate.orm.jdbc.bind` not at DEBUG |
| TC-X2 | F1-a, F1-c       | Review + mutation | `QaSchemaTest` against the ERD; unique-index keys printed with `pg_get_indexdef` | Matches the ERD; the expression key is checked in full                                                                                                |
| TC-X3 | F1-b, F1-d, F1-e | Review            | `git diff` of the qa files                                                       | Only the allowed lines                                                                                                                                |
| TC-X4 | D18              | Build             | `cd api-gateway && ./gradlew build`                                              | Green, 411 tests, 4 skipped (gateway D10 baseline)                                                                                                    |
| TC-X5 | X4               | Tool              | `dbml2sql --postgres` on the ERD                                                 | Exit 0; 21 tables, 22 FKs, 8 enums                                                                                                                    |

## 5. Role and permission matrix

Phase 4 changes no role or permission (spec "Roles"). The phase-1 and phase-2 matrices (`QaAuthorizationMatrixTest`,
`QaProjectRoleMatrixTest`, `QaProjectAccessCheckTest`) run unchanged on PostgreSQL under AC-3.1. Roles used in new
tests:

| Actor     | Role                         | Used for                                                            |
|-----------|------------------------------|---------------------------------------------------------------------|
| Ana       | Acme OWNER                   | Organization and project creation, org-role changes, adding members |
| Ben       | Acme ADMIN                   | Invitations, bursts                                                 |
| Cai       | Acme ADMIN, Checkout ADMIN   | Rename, archive, project-member changes                             |
| Hal       | Acme MEMBER, Checkout TESTER | Access check, UUID forms                                            |
| anonymous | —                            | Register, login, accept                                             |

## 6. Audit and mutation method

Each backend-swe test class named in the brief is read against its Gherkin scenario (rows, Then clauses, "nothing was
stored"). Mutants are applied with `sed` to a throw-away copy of `auth-service` under `/tmp`, in four groups (
migrations; configuration and validators; start-up, schema and locking; the E81 depends-on class). Each group runs the
backend-swe classes that should kill it, plus qa's. The repository's `src/main` is never edited.

## 7. Test data, environment and tooling

- Fixtures: `AcmeFixture`, `ProjectFixture` (Gus, Hal, Ivy; Checkout, Billing, Initech QA), plus Kit invited where the
  spec's Background says so.
- Docker Desktop (server 27.4.0) with `postgres:17`; Corretto 17.0.18 (the Gradle toolchain).
- The build machine's zone is Asia/Tashkent (UTC+05), so every shared-context connection already has a non-UTC session
  zone.
- Manual run: own container `qa-auth-persistence-pg` (`postgres:17`, builtin `C.UTF-8`) on 127.0.0.1:15432, removed
  afterwards with its volume. Postgres.app on 5432 and Camunda are never touched.
- No new tooling or dependency is proposed.

## 8. Risks and items not covered

- `ddl-auto: validate` doesn't detect type drift between compatible types (`timestamptz` to `timestamp`, `integer` to
  `bigint`), dropped NOT NULL or shortened lengths. `QaSchemaTest` catches these in the test database only. Production
  drift of that kind would start silently.
- Flyway ignores applied migrations that are newer than the code (default `*:future`), so an older build starts on a
  newer schema. That's Flyway's default, not a defect.
- Errors outside Spring MVC (pool exhaustion inside the JWT filter) give Boot's default 500 body (FU-1, out of scope).
- Deadlock (40P01) is proven on a scratch app with `deadlock_timeout` 200 ms; the shared test database's 1 s lock
  timeout races the default 1 s deadlock check, so it isn't proven there.
- No CI; Docker must be running for every build.
