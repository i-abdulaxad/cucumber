# Engineering standards (binding for every spec and every agent)

These are the rules every feature so far has agreed on, collected in one place. Specs **reference** this file instead of copying it, and a spec adds only its own deviations, each one named and approved. Changing this file needs the user's approval; pm maintains it.

Last updated: 2026-10-26

Sources: the accepted specs `api-gateway`, `auth-service`, `auth-service-projects` and `auth-service-persistence`, and `scenario-service`. Reference implementations: auth-service, for persistence, settings, logging and strict input; scenario-service, for remote access checks, the Gherkin adapter and hashed indexes.

## 1. Workflow and artifacts

`pm` (spec) → user approves → `backend-swe` / `frontend-swe` (batches with review points) → `qa` (verify) → fix round if needed → `pm` (accept). The main session routes every handoff and asks the user. Subagents never ask the user directly; they put questions in their handoff.

| Artifact | Location | Owner |
|---|---|---|
| Spec | `docs/specs/<feature>.md` | pm |
| API contract (OpenAPI 3.1) | `docs/api/<service>.openapi.yaml` | backend-swe |
| ERD (DBML) | `docs/erd/cucumber.dbml`, one `TableGroup` per service | pm proposes changes; backend-swe keeps it as-built |
| Test plan and QA report | `docs/qa/<feature>-test-plan.md`, `docs/qa/<feature>-report.md` | qa |
| These standards | `docs/engineering/standards.md` | pm, with the user's approval |

Acceptance criteria are Gherkin, tagged `@US-n @AC-n.m @P0|@P1|@P2`. Tests carry `@Tag("AC-n.m")`.

## 2. Approval rules

- **Existing code is the user's.** A committed file changes only through an approved change item (E-item), exactly as that item describes. If an item turns out to need more than it says, stop and ask.
- **Allowed files.** A task creates, modifies or deletes only the files on its Allowed files line.
- **No drive-by changes.** No refactors, reformatting, renames or "while I'm here" fixes. Report problems you find as refactoring candidates (RC-n).
- **No new dependency or version change** without approval. Verify artifact names and versions in the resolved jars or the BOM; don't guess them.
- **Breaking contract changes** need pm's and frontend-swe's agreement before they're made.
- **Stop conditions are real.** When one triggers, stop and report the evidence and the options. Never work around one, and never disable or weaken a check to get past it.
- **Batches end at review points.** At a review point the main session verifies the work and the user decides whether to continue.
- **Fix rounds after QA.** qa's tests (`.../qa/**`) are read-only for backend-swe unless an F1 exception names the exact lines. A fix starts with a failing test.

## 3. Code standards (Spring Boot 4.1, Java 17)

**Layering.** These rules are binding.

| Layer | Owns | Never |
|---|---|---|
| Controller | HTTP mapping, `@PreAuthorize`, exactly one service call per handler | repositories, `EntityManager`, entities, business branching, hand-written validation, reflection, building error bodies |
| Service | the use case: rules, `@Transactional` (after any remote call), repository calls, entity-to-record mapping, typed exceptions | HTTP types, persistence mechanics (native SQL, lock modes) |
| Repository | queries, locks, counts | business rules |
| Advice (`@RestControllerAdvice`) | the only place exceptions become responses | business decisions |
| Configuration | beans, typed `@ConfigurationProperties` records, filters, the security chain | business logic |

**Other code rules:**
- **Errors:** RFC 9457 Problem Details with a stable `code` (an `ErrorCode` enum), plus `errors[]` for validation failures and `requestId` from `X-Request-Id`. One typed `ApiException` per code. Unexpected errors are 500 with no internals in the body.
- **Records and injection:** DTOs are records; no `@Builder` unless something calls it. Constructor injection with `private final` fields. No field injection, no static mutable state.
- **Strict input:**
  - canonical UUIDs only, matched exactly as sent (a binder-only `@ControllerAdvice` with a strict `PropertyEditor`; a Converter won't work);
  - JSON strings must be strings (a `JsonMapperBuilderCustomizer`);
  - lengths count code points;
  - reject control characters, lone surrogates and Unicode-blank names.
- **Time:** an injected UTC `Clock` with microsecond ticks, `Instant` in code, `timestamptz` in the database, `Z` in the API. No `LocalDateTime` and no clock-less `Instant.now()`.
- **Security:**
  - HS512 JWTs with the shared `JWT_SECRET`; the `Bearer` scheme is case-insensitive;
  - every service validates the JWT itself;
  - project access from another service goes through auth-service's C5 access check: no cache, fail closed (503), no retries.
  - scenario-service sets `jdk.httpclient.redirects.retrylimit=1` JVM-wide (JDK 17 has no per-client retry switch; platform-hardening SFU-1, user decision 2026-10-26). Any future HTTP client there must not rely on following redirects, or must use a dedicated mechanism.
- **Logging:**
  - the AOP aspect around services logs one outcome line per call;
  - MDC carries `requestId` and `userId`;
  - **never** log arguments, return values, content, emails, tokens, passwords or secrets;
  - `org.hibernate.orm.jdbc.bind` is never enabled.
- **Service boundaries:** no cross-service database access and no shared library. Patterns are copied, never imported.

## 4. Persistence

The full, binding pattern is in `docs/specs/auth-service-persistence.md`, section "Database pattern for every service (reusable)". In short:
- **Database:** PostgreSQL 17 with builtin `C.UTF-8`, one database per service.
- **Migrations:** Flyway with `ddl-auto: validate`. Files are `V<n>__<snake_case>.sql` and **append-only** once they've passed a review point.
- **Enums:** `varchar(32)` plus a named CHECK. JSON columns are `jsonb`.
- **Case-insensitive unique indexes** use `lower(x COLLATE "und-x-icu")`. Long values use `md5(...)` in the index, because of the B-tree entry limit, and the application compares with the same database expression.
- **Locking:** `lock_timeout` is 5 s in production and 1 s in tests. Writes take the project lock first and keep the work under the lock short: batch inserts, load reference rows in one query, and parse before the transaction.
- **Settings:** `<SVC>_DB_URL/USERNAME/PASSWORD` are checked after binding. The check names the missing variable and never prints a value. Values must not contain `${`.

## 5. Testing

- **Test first.** Show the new test failing before the change, and say how it failed.
- **Database in tests:** a Testcontainers `postgres:17` singleton via `@ServiceConnection`, reused through the shared support annotations.
- **Test contexts:** no `@DirtiesContext`. Keep cached contexts at 10 or fewer, and report the count when you add a context.
- **Shared JVM state:** log levels and the adjustable clock are JVM-wide. Set them right before use and restore them in `finally`.
- **No test activates the `dev` profile or loads any `.env`.** The build must pass with no `.env` present.
- **Never weaken a test:** no `@Disabled`, no weakened assertions, no deleted tests. An on-demand measurement test may be skipped by default only with approval.
- **Concurrency:** when a change touches concurrency or locking, run the concurrency test classes 3 times in a row.
- **Mutation checks (qa):** about 10–25 targeted mutants of the changed code, on a `/tmp` copy that is deleted afterwards.

## 6. Environment safety (always)

- **Docker** must be running for builds. If it isn't, stop and say so.
- **`.env`:** never read, print, copy, create, change or `source` any `.env` file.
- **Databases you must not touch:** Postgres.app on 5432, the `cucumber-postgres` container, Camunda `c8run`, the user's IDE, and any container or process you didn't start.
- **Manual runs:** use your own throwaway `postgres:17` on a free port, no `dev` profile, and throwaway secrets passed as environment variables, never written to a file or printed. Remove the container and its volume afterwards.
- **Killing processes:** only by the PID you recorded when you started the process. Never `pkill`, `killall` or a kill by name. `./gradlew --stop` only when no other build is running in that service.
- **Git:** none that changes state; the user commits. Report `git status --short` and `git diff --stat`.
- **Clean up:** your `/tmp` scratch files, containers and daemons.

## 7. Working efficiently (without lowering quality)

- **Read narrowly.** Grep the spec for the task ID, then read only that task and the D-, E- and AC-items it cites, plus the sections they reference. Read source files by line range. Don't re-read files you have already read in the same task unless they changed.
- **Batch tool calls.** Run independent reads, greps and checks in parallel, in one step.
- **Iterate with targeted tests** (`./gradlew test --tests '<Class>'`). Run the full `clean build` once at the end of each task, and once before handing off. Run the concurrency classes 3 times only when concurrency or locking changed. Don't run `clean` between iterations.
- **Keep build output small.** Use `-q --console=plain`, and get counts from `build/test-results/test/*.xml` with a one-line script instead of reading logs. Read a log only for a failing test.
- **Verify once.** When a fact about a framework or jar is already recorded in a spec or this file, trust it unless the task changes it.
- **Write reports to files, not chat.** The final message is the short handoff; details belong in the spec, the report or the contract.
- **Stop early** on a stop condition or blocker instead of exploring alternatives the spec didn't ask for.

## 8. Handoff (the final message of every task)

Keep it under about 40 lines. No restating of the brief, and no tables of unchanged things.

```text
## Handoff: <from> → <to>
- Feature / task: <feature> (<spec path>), <task ids>
- Result: done | stopped (<stop condition>) | blocked (<reason>)
- Changed: <files, grouped; "new" or "modified">
- Evidence: <red then green per AC>; build <tests/failed/skipped>, contexts <n>; concurrency 3x <yes/n.a.>
- Contract / schema / config changes: <none | list, breaking yes/no>
- Deviations: <numbered, each with its reason, or "none">
- Decisions needed: <numbered, each naming who decides, or "none">
- git: <git status --short summary>; cleanup: <done>
```
