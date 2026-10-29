# QA report: platform-hardening (review point B, whole batch)

Spec: `docs/specs/platform-hardening.md`. Test plan: `docs/qa/platform-hardening-test-plan.md`.
Commit tested: `566fc1b` plus working tree (T7 E13/E14, T8 E15, the auth-service test-JVM `server.address` line the main session calls E16, gateway contract 0.3.1). qa, 2026-10-29.

## Verdict

**Signed off with known issues.** All 22 ACs pass, including every P0. No S1, S2 or S3 bug is open. One S4 contract inconsistency is open (BUG-PH-1), and there are two testability findings for pm.

| Result | Count |
|---|---|
| Pass | 22 (P0 13/13, P1 9/9) |
| Fail / Blocked / Not tested | 0 |

## Suites run (all on the commit above)

| Run | Result |
|---|---|
| auth-service `clean build` | 2,061 tests, 0 failed, 0 skipped (2,043 + 18 qa) |
| scenario-service `clean build` | 899 tests, 0 failed, 1 skipped (the accepted on-demand measurement). That is 880 + 17 rejection tests + 1 start-up test + 1 new matrix row |
| api-gateway `clean build` | 475 tests, 0 failed, 3 skipped (445 + 30 qa). `theErrorPathIsAnUnroutedPath` re-enabled, so skipped went from 4 to 3 |
| 3× `test --rerun`, auth | `ConnectionUnavailableTest`, `QaPlatformHardeningRealServerTest`, `RegistrationRaceTest`, `InvitationAcceptanceTest`: green in runs 1 and 3. Run 2 had one failure in qa's refusal test (OBS-3), now fixed and green 5 more times in isolation, plus a whole-class re-run |
| 3× `test --rerun`, scenario | `SingleAccessCheckAttemptTest`, `QaAccessCheckFailureMatrixTest`, `QaConcurrencyTest`, `ConcurrentCreateTest`, `ConcurrentSaveTest`, `ProjectLockTest`: green in runs 1 and 2. Run 3 had one time-bound failure (OBS-1), which didn't reproduce in 24 further isolated runs |
| 3× `test --rerun`, gateway | `QaCommittedResponseAbortTest`, `QaCommittedResponseAbortAdversarialTest`, `QaUncommittedFailureGuardTest`, `QaDownstreamResponseFailureTest`, `DownstreamResponseFailureTest`: green 3/3 |
| Manual G10a run | Pass (see below) |

**Contexts:** qa adds no cached context. The auth and scenario qa classes use `RunningAuthService` and `RunningScenarioService`, each one extra app start per class. The gateway qa classes share the plain `GatewayIntegrationTest` context or the existing US-6 base. The gateway still starts 16 applications per build, the same count as before.

## AC results

| AC | Result | Evidence |
|---|---|---|
| AC-1.1 | Pass | 4 backend rows; qa matrix with 6 rows, the no-retry count now asserted on every row; `QaStartUpAttemptLimitTest`; manual run: real `java -jar`, C5 closes at once, 1 connection, then 503 `ACCESS_CHECK_UNAVAILABLE` |
| AC-1.2 | Pass | `SingleAccessCheckAttemptTest#aNormalAnswerIsOneRequest` |
| AC-2.1 | Pass | 4 backend rows; 6 more qa rows (`;jsessionid`, `%2e%2e`, `%252e`, `%3B`, `..;`, an expired token) and 3 `Accept` values; manual |
| AC-2.2 | Pass | backend; qa with token, cookie and custom header: nothing echoed; manual |
| AC-2.3 | Pass | backend 3 rows; qa 6 variants (POST, DELETE, query, expired token, no token, `;x=1`) |
| AC-2.4 | Pass | backend; manual (Tomcat page, no version) |
| AC-2.5 | Pass | backend; full suite |
| AC-3.1 | Pass | backend 5 rows; qa burst of 12 concurrent requests (all 503 in < 3 s, no 401 or 500, no registration stored); qa database-refusal test (each request 503 within 2 s); manual refusal, direct and through the gateway |
| AC-3.2 | Pass | backend (one WARN naming `SQLTransientConnectionException`); qa: no token, password, email or stack trace in the burst's logs |
| AC-3.3 | Pass | backend; recovery in both qa D7 tests, with 0 active connections afterwards; manual |
| AC-4.1 | Pass | backend 4 rows; qa 7 rows and 3 `Accept` values, no C5 call; manual |
| AC-4.2 | Pass | backend; qa with token, cookie and custom header; manual |
| AC-4.3 | Pass | backend 3 rows; qa 6 variants, no C5 call; manual |
| AC-4.4 | Pass | backend; manual |
| AC-5.1 | Pass | backend 3 rows; qa 7 rows asserting `instance` equal to the path as sent, 4 `Accept` values, and client request IDs (valid one kept, invalid one replaced and not echoed); manual |
| AC-5.2 | Pass | backend; qa PUT, DELETE, PATCH; manual |
| AC-5.3 | Pass | backend; qa on 4 paths with token, cookie and header: nothing echoed, no `X-Request-Id`, no `requestId` (as the contract documents); manual |
| AC-5.4 | Pass | backend; `QaUnknownPathsTest#theErrorPathIsAnUnroutedPath` re-enabled; qa 6 variants plus HEAD |
| AC-5.5 | Pass | backend; manual (400 Tomcat page; CONNECT 501) |
| AC-6.1 | Pass | `QaCommittedResponseAbortTest` (3 rows) and `QaCommittedResponseAbortAdversarialTest` (an invalid chunk after 40 KB, a JSON stall after 16 KB, complete large bodies untouched, 4 aborted plus 4 complete downloads at once); manual: curl exit 18 for chunked and length |
| AC-6.2 | Pass | qa 3 rows plus the adversarial tests: one WARN, no ERROR, no `Failure in @ExceptionHandler`, access `status=200`; manual access entries `status=200` |
| AC-6.3 | Pass | `QaUncommittedFailureGuardTest`; AC-4.6 and AC-4.7 regression green |

## Mutation check (25 mutants, `/tmp` copies, deleted afterwards)

25 of 25 were killed: 24 on the first run and S1 after qa's new test.

| Area | Mutants | Killed by backend-swe's tests | Killed only by qa |
|---|---|---|---|
| auth E1 permit, direct `/error`, `instance` (2), D7 matching (3), E5 filter, D7 log level, dispatch 503 | A1–A10 | 10 | 0 |
| scenario E7 call in `main` | S1 | 0 (survived) | killed by the new `QaStartUpAttemptLimitTest` |
| scenario E8, E9, `instance` (2) | S2–S5 | 4 | 0 |
| gateway E13 committed branch (3 forms) | G1–G3 | 0 | 3 (`QaCommittedResponseAbort*`) |
| gateway E14 `CLOSE` instead of `CLOSE_NOW`, valve inverted | G4, G5 | 2 | 0 |
| gateway E14 valve not registered | G6 | 0 | 1. The first try was an invalid mutant (a syntax error in my replacement); the corrected re-run was killed |
| gateway `instance` from `/error` | G7 | 0 | 1 (`QaGatewayRejectionsTest`) |
| gateway D5 texts (400, TRACE 405, MVC 405) | G8–G10 | 3 | 0 |

**Audit of backend-swe's tests against the Gherkin:**
- auth and scenario cover every row with strong assertions.
- In the gateway, `GatewayErrorDispatchTest` never asserts `instance` (D5 item 4). E13 and E14 have only a mocked valve unit test. Both are covered by qa's classes, which are the agreed AC tests.
- No change is required.

## Tolerances re-checked (qa's own tests)

| Test | Change | Why |
|---|---|---|
| scenario `QaAccessCheckFailureMatrixTest#transportFailuresFailClosed` | Connection-count assertion restored for every row; new row "resets before reading the request"; tagged AC-1.1 | BUG-4 fixed by T3 (D2) |
| scenario `QaStrictInputTest#urlsTheFirewallRejects` | Comment only | It said the body isn't a Problem. On a real server it now is (AC-4.1); MockMvc has no error dispatch |
| gateway `QaUnknownPathsTest#theErrorPathIsAnUnroutedPath` | `@Disabled` removed; tagged AC-5.4 | BUG-api-gateway-6's `/error` part fixed by T5 |
| gateway `QaUnknownPathsTest#healthSubPathsAre404Problems` | Still `@Disabled`; reason now points to FU-5 | Q3 moved the health sub-paths to FU-5; they still answer an empty 404 |
| gateway `QaCommittedResponseAbortPendingT7Test` | Renamed `QaCommittedResponseAbortTest`; javadoc records the red evidence from 2026-10-26 and the fix; raw client moved to `RawDownload` | T7 landed; "pending" no longer applies |
| auth `QaPublicEndpointBypassTest` (TRACE `isIn(400, 401, 405)`, firewall rows) and gateway `QaPublicOperationBypassTest` (`isIn(400, 401)`) | Unchanged | MockMvc or unspecified 4xx by design; the exact Problems are asserted on real servers by the new qa classes |

## Manual G10a run

- **Setup:**
  - throwaway `postgres:17` (builtin `C.UTF-8`), host loopback on a free port;
  - jars from the clean builds;
  - no `dev` profile, generated throwaway secrets, nothing printed or written;
  - scenario-service's C5 pointed at a raw server that closes at once; report-service is a raw server for the abort rows;
  - auth-service `connection-timeout=1000` (PH-FU-1);
  - PIDs recorded and killed; container removed.
- **E2E through the gateway:** register 201, login 200, `GET /api/orgs` 200.
- **Gateway:**
  - `POST /api/auth/./login` and `GET /api/orgs//x` → 400 D6 with `instance` as sent;
  - `POST /actuator/health` → 405 D6;
  - `TRACE` → 405 D6 without a request ID, marker not echoed;
  - `GET /error` → 401 without a token, 404 with one;
  - `login%2F..%2Forgs` → 400 and `CONNECT` → 501 (Tomcat pages).
- **auth-service direct:**
  - `register;x=1` → 400 `MALFORMED_REQUEST`, `requestId` `ph-auth-1`;
  - `TRACE` → 405 `METHOD_NOT_ALLOWED`;
  - `/error` → 401, and 404 with a token;
  - `%2F` and `%00` → 400 Tomcat pages.
- **scenario-service direct:**
  - `;x=1` → 400 `MALFORMED_REQUEST`;
  - `TRACE` → 405;
  - `/error` → 404;
  - `%2F` → 400;
  - no C5 connection before the access check; exactly 1 after it (503).
- **Aborts through the gateway:** `big-cut-chunked` → 200, 97,793 bytes, curl exit 18, no Problem; `big-cut-length` → 200, 97,892 bytes, curl exit 18, no Problem. Access entries `status=200`; one WARN each.
- **D7:**
  - database `ALLOW_CONNECTIONS false` and backends terminated;
  - immediate `GET /api/orgs` → 503;
  - after 2 s, direct → 503 with `requestId` `ph-db-1`, and login through the gateway → 503 (not 504, because of the 1 s timeout);
  - re-allowed → 401 `INVALID_CREDENTIALS`.
- **Logs:** 0 ERROR lines, 0 stack-trace lines, 0 occurrences of the token, the passwords or the trace marker in all three services.

## Bugs

| ID | Severity | Status | Owner |
|---|---|---|---|
| BUG-PH-1 | S4 | Open | backend-swe (contract) |

### BUG-PH-1: gateway contract 0.3.1 contradicts itself on `X-Request-Id` for TRACE
- **Area / AC:** `docs/api/api-gateway.openapi.yaml` (D8, T6/T7); AC-5.3.
- **Steps:** read line 114 (Headers table, `X-Request-Id` row), then lines 32–33 and 335.
- **Expected:** one rule. The built behaviour is that a TRACE 405 carries no `X-Request-Id` (qa `QaGatewayRejectionsTest#traceEchoesNothing`; manual run).
- **Actual:** line 114 says "Exactly one header on every response, including gateway-generated errors". Lines 32–33 and 335 say the TRACE 405 has none. Container-level rejections (D6) also have none, and line 114 doesn't mention them either.
- **Also:** the two "Implementation status … pending qa verification" notes (0.3.0, 0.3.1) are now out of date after this verification.
- **Evidence:** contract text; the test above (status 405, `X-Request-Id` absent, no `requestId` field).

## Observations (not bugs)

- **OBS-1 (scenario-service, AC-7.3 bound, not reproduced):** in 3× run 3, the row "stalls after sending part of the head" took 6.27 s against its 3 s bound, about the stub's 6 s stall. That run had three services' suites going in parallel. In 24 isolated runs (8 × 3 stall rows) every one took 2.0 s. No action is needed unless it recurs; if it does, look at the JDK client's request timeout under load.
- **OBS-2 (auth-service, D7 logging):** after the database terminates the pool's connections, HikariCP logs its own WARN per dead connection ("Failed to validate connection … (This connection has been closed.)"), 10 in the manual run, before the single D7 WARN. These lines carry no request data and come from the library, not the error mapping, so AC-3.2 is unaffected. Noisy on a database restart.
- **OBS-3 (qa's own test):** qa's refusal test first bounded two requests together at 4 s and failed once under parallel load (5.25 s). It now times each request against AC-3.1's 2 s and was green 5 times in isolation and in a whole-class run.
- The services' TRACE 405 `detail` is "Method Not Allowed" (the existing text); the gateway's is its own D5 sentence. Both are per D5 item 3.

## Testability findings for pm

1. **E16 isn't in the spec.** `auth-service/build.gradle` gained `systemProperty 'server.address', '127.0.0.1'` (test JVM). The E-list stops at E15, whose text covers only `support/*` test helpers. Please record E16 and its approval date.
2. **No D7 bound for the refusal case.** qa applied AC-3.1's 2 s, which holds with a 500 ms connection-timeout.

## git and cleanup

- `git status --short`:
  - new: the qa classes listed in the AC map and the two docs;
  - modified: `QaAccessCheckFailureMatrixTest`, `QaStrictInputTest`, `QaUnknownPathsTest`;
  - deleted: `QaCommittedResponseAbortPendingT7Test` (renamed);
  - plus backend-swe's uncommitted T7/T8 files.
  - No production code, config, build file, spec or contract changed by qa.
- Removed: `/tmp` mutation copies, the manual-run container and processes, and the scratch files.
