# QA report: report-service phase 1 (T1–T15)

**Verdict: Signed off with known issues.** All 61 ACs pass (27 of 27 P0). No S1 or S2 bug is open. Two S3 bugs are open,
one in report-service and one in the gateway, plus three S4 observations for pm.

- **Code under test:** HEAD `1a2d927` plus the working tree (T14 and T15 diffs, GW1–GW5, contracts, spec, README).
- **Environment:** qa, 2026-11-02. Docker was running; no `.env` was used; no `dev` profile; no `src/main` edits; no git
  that changes state.
- **Spec and contracts:** `docs/specs/report-service.md` and `docs/api/report-service.openapi.yaml` 1.0.2 (BD-19, RE11
  and RE12 are documented there).

## Builds and runs

| Run                                                                                                                                                | Result                                                                                                                                                                                                                                                                                                  |
|----------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| report-service `clean build`                                                                                                                       | 780 tests, 0 failed, 0 skipped (50 classes). backend-swe's 412 + qa's 368                                                                                                                                                                                                                               |
| api-gateway `clean build`                                                                                                                          | 523 tests, 0 failed, 3 skipped (the 3 skips were already there). 488 + qa's 35                                                                                                                                                                                                                          |
| Concurrency classes ×3 (`ConcurrentTokenCreateTest`, `RevokeRacingUploadTest`, `CompleteRacingRecordTest`, `ProjectLockTest`, `QaConcurrencyTest`) | 59/0/0 each time                                                                                                                                                                                                                                                                                        |
| Test contexts                                                                                                                                      | No new context. Every qa class uses `@ReportServiceIntegrationTest` or `GatewayIntegrationTest`                                                                                                                                                                                                         |
| auth-service (US-8)                                                                                                                                | Not re-run: Gradle was limited to report-service and the gateway. Its existing XML results read 2086/0/0 (10:12 today), including `InternalProjectStatusTest`, `InternalServiceKeyTest`, `InternalServiceKeyUnsetTest` and `InternalServiceKeyStartupTest`. C7 was exercised live in the end-to-end run |

**Mutation check:** 24 targeted mutants on a /tmp copy, since deleted. 23 killed and 1 equivalent, so 23/23 (100 %) on
the non-equivalent ones.

- **Killed:**
    - tokens: the limit `>=`, idempotent revoke, the revoke re-check under the lock, `lastUsedAt`;
    - body limit off by one;
    - Cucumber JSON: AMBIGUOUS/UNDEFINED precedence, the UTF-16 truncation cut, background attachment;
    - C7: the `projectId` comparison, `X-Request-Id`;
    - lifecycle: recording on a closed run, cancel role, `startedAt` moving, close re-stamping `finishedAt`;
    - planning: the FEATURE_CHANGED re-check, case-insensitive tags, the 2,000 limit;
    - reads: HALF_EVEN rounding, page-size limit, IDOR on run reads;
    - RE11, RE12, scenario-service 401.
- **Equivalent (M1):** `isAfter` → `!isBefore` in `UploadTokenAuthenticationFilter`. `UploadServiceImpl.store` checks
  `expiresAt.isAfter(now)` again under the lock, so an upload at exactly `expiresAt` is still 401 (checked by
  `QaCiUploadTest.theExpiryBoundaryIsExclusive`).

**Manual end-to-end through the gateway (standards §6):**

- **Setup:** my own `postgres:17` on a free port with 3 databases; the 4 boot jars on free ports (8080 was taken by
  another process); throwaway secrets that were never printed; PIDs recorded and killed by PID; the container removed
  with its volume.
- **AC-6.6:**
    - CI upload through the gateway: 201;
    - Gus plans: 201;
    - Dan lists runs: 200;
    - `/runsx` with a token: the gateway's 404;
    - Dan records: 403; Hal records and completes: 200.
- **Archived project, with the real C5 and C7:**
    - upload: 409 `PROJECT_ARCHIVED`;
    - token create: 409; revoke: 200; reads: 200;
    - after restore, the revoked token gets 401.
- **Other gateway checks:**
    - RE11 duration overflow: 422;
    - 21 MiB upload: 413;
    - upload token on a user path: 401; JWT on the CI path: 401 `UPLOAD_TOKEN_INVALID`.
- **C7, called directly on auth-service:**
    - right key: 200 with exactly `{archived, orgId, projectId}`;
    - upper-case id: 200 with the `projectId` in lower case; unknown id: 404;
    - no key, a wrong key or a JWT only: 401;
    - C5 with the key and no JWT: 401.
- **Timings:**
    - the JVM sample: 29–49 ms per upload;
    - a 19.9 MB upload with 5,000 cases: 517–810 ms, well under the 5 s stop condition;
    - a plan: 216 ms.
- **Log scan:** across all 4 logs, no token, prefix, JWT, service key, `JWT_SECRET` or password appears, and none of the
  markers except QA-4's line.

## Per-AC results

- **US-1:** AC-1.1 to AC-1.10 pass. qa adds Unicode names, the BD-17 boundary on the injected clock, 20 distinct tokens,
  `Cache-Control: no-store`, B1 untouched by a Checkout-path revoke, expiry freeing a slot, and 5-way and
  revoke-vs-create races.
- **US-2:** AC-2.1 to AC-2.15 pass.
    - Re-verified fixed: **QA-1** (duration overflow, now 422 at `$[0].elements[n]`, including hook and background sums)
      and **QA-2** (malformed encoding is 400 when report-service is called directly). Through the gateway, see QA-4.
    - qa adds token presentation variants, the D13 order matrix, query boundaries, `lastUsedAt` set only on 201, odd C7
      answers (exactly one request, one WARN line, no URL, key or token in the log) and a 15 MiB `error_message`.
- **US-3:** AC-3.1 to AC-3.8 pass. qa adds case-sensitive tags, exactly 2,000 cases → 201, an unselected feature
  changing, 4 calls for 2 features, and odd scenario-service answers → 503 with no retry.
- **US-4:** AC-4.1 to AC-4.8 pass. qa adds the D8 deviation 2 (UNKNOWN keeps `executedBy`), `startedAt` set once, every
  closed-run transition, the 404-before-409 order, comment rules, and races (complete vs cancel; 3 concurrent
  recordings).
- **US-5:** AC-5.1 to AC-5.5 pass. qa adds half-up cases (1/16 = 6.3, 1/6 = 16.7, ...), null durations and feature
  order. QA-3 lies outside the AC rows.
- **US-6:**
    - AC-6.1 and AC-6.2 pass. qa adds the full 7×11 matrix on active and archived Checkout, with database invariants on
      every denial.
    - AC-6.3 to AC-6.5 pass. qa adds upper-case ids on writes, the IDOR set, the same 403 body for an unknown project,
      and that `X-User-Id` is ignored.
    - AC-6.6 passes (manual run, plus the gateway qa bypass suite of 35 tests).
- **US-7:** AC-7.1 to AC-7.4 pass. qa's root-DEBUG run in the shared context covers the token, upload, 4xx, C7 failure,
  planning, scenario-service failure and recording paths: no secret, query value, step text, comment or "Listing
  entities".
- **US-8:** AC-8.1 to AC-8.5 pass. The evidence is the live C7 checks above plus backend-swe's suite results (AC-8.4 was
  not re-run by qa with the key set and unset).

## Bugs

| ID   | Sev | Area                                                                    | AC              | Status                                            | Owner                        |
|------|-----|-------------------------------------------------------------------------|-----------------|---------------------------------------------------|------------------------------|
| QA-1 | S3  | ingest: duration overflow gave 500                                      | AC-2.8          | Fixed (RE11), verified                            | backend-swe                  |
| QA-2 | S3  | malformed `%` encoding gave 500 and logged the value                    | AC-2.10, AC-6.5 | Fixed in report-service (RE12), verified directly | backend-swe                  |
| QA-3 | S3  | reads: a far `page` gives 500                                           | AC-5.4          | Open                                              | backend-swe                  |
| QA-4 | S3  | gateway: malformed `%` encoding gives 500 and logs the query value      | AC-6.6, D17     | Open                                              | backend-swe (gateway E-item) |
| QA-5 | S4  | token `expiresAt` accepts a JSON number                                 | AC-1.4          | Open, pm decides                                  | pm                           |
| QA-6 | S4  | a repeated query parameter is joined (`name=a&name=b` stores `a,b`)     | AC-2.10         | Open, pm decides                                  | pm                           |
| QA-7 | S4  | a token name of only U+202E (an invisible format character) is accepted | AC-1.4          | Open, pm decides                                  | pm                           |

**QA-3: a far `page` gives 500.**

- **Steps:** as Dan, `GET /api/projects/{checkout}/runs?page=21474837&size=100`. The same happens with
  `GET {runs}/{id}/results?page=2147483647&size=500`.
- **Expected:** 200 with empty `items`. The contract allows any int32 `page` ≥ 0; alternatively 400 `VALIDATION_FAILED`,
  if pm prefers.
- **Actual:** 500 `INTERNAL_ERROR`, and an ERROR log line:
  `InvalidDataAccessApiUsageException: Page offset exceeds Integer.MAX_VALUE (2147483647)` from
  `PageableUtils.getOffsetAsInteger`. `RunQueryServiceImpl.paging` doesn't bound `page × size`.
- **Evidence:** a MockMvc probe and the manual run through the gateway (500).
- **Regression guard:** `QaManualRunTest.aFarPageIsEmpty` (`page=21474836`, still inside the int range) passes.

**QA-4: the gateway gives 500 for a malformed percent-encoding and logs the query value.**

- **Steps:** through the gateway, `POST /api/ci/runs?branch=QaLeakMarker67%FF` with a valid upload token. The same 500
  happens for `GET /api/projects/{p}/runs?page=%FF` with a JWT.
- **Expected:** a 400 Problem, with no parameter value in any log (RE12's intent "400 everywhere"; standards §3
  logging).
- **Actual:** the gateway answers 500. `GatewayExceptionHandler` logs ERROR `Unexpected gateway error` with
  `org.apache.tomcat.util.http.InvalidParameterException: Character decoding failed. Parameter [branch] with value [QaLeakMarker67�]`,
  raised at `StrictHttpFirewall$StrictFirewalledRequest.getParameterMap`. The request never reaches report-service.
- **Evidence:** manual runs 1 and 2. `gateway.log` contained the marker once each time, with 2 ERROR lines.
- **Fix route:** like FU-3, through a user-approved gateway E-item.

**S4 observations (pm decides; not blocking):**

- **QA-5:** `{"name":"x","expiresAt":4102444800}` → 201, stored as `2100-01-01T00:00:00Z`. The contract's `Timestamp` is
  a string. Observed on the code before T13; T13 didn't touch deserialization.
- **QA-6:** `name=a&name=b` → 201 with the name `a,b`. D9 is silent on repeated parameters.
- **QA-7:** a token name of only `‮` → 201. That character is not "Unicode-blank" by the rule as written.

**Test audit:**

- backend-swe's tests match the Gherkin rows.
- Gaps that qa now covers:
    - `RoleMatrixTest`'s 403 rows don't check that nothing was stored;
    - `ProjectCheckFailureTest` doesn't check "exactly one C7 request";
    - `ConcurrentTokenCreateTest` runs a single round;
    - `ParameterDecodingTest` doesn't check that a readable value next to the bad escape stays out of the log.

## qa files (new)

- `/Users/uDevs/IdeaProjects/cucumber/report-service/src/test/java/org/reportservice/qa/`:
    - `QaCiUploadTest`
    - `QaConcurrencyTest`
    - `QaCrossProjectTest`
    - `QaCucumberIngestTest`
    - `QaLogLeakageTest`
    - `QaManualRunTest`
    - `QaRoleMatrixTest`
    - `QaUploadTokenTest`
- `/Users/uDevs/IdeaProjects/cucumber/api-gateway/src/test/java/org/apigateway/qa/QaReportServiceRoutesTest.java`

## Fix round 1 re-verification (2026-11-02)

- **Code under test:** HEAD `1a2d927` plus the working tree: T16/RE13 in `RunQueryServiceImpl`, T17/GW6 in
  `GatewayExceptionHandler`, gateway contract 0.3.4.
- **Diffs:** both are confined to the named methods.
- **Rules followed:** no `src/main` edits, no `.env`, no git that changes state.

### QA-3 (S3, far `page` gave 500): Closed, verified

- **Fix:** if `page × size` (as a `long`) exceeds `Integer.MAX_VALUE`, the service answers 200 with empty `items` and
  the real `totalItems`, read from the first row.
- **Direct (MockMvc), new rows in `QaManualRunTest`:** 200, `items` empty, correct `totalItems`, and `page`/`size`
  echoed:
    - runs: `page=2147483647&size=1` (offset exactly `Integer.MAX_VALUE`, which still takes the normal query path);
      `page=2147483647&size=100` (maximum size); `page=21474836&size=100` (just inside the range);
    - an empty project (Billing): `totalItems` 0;
    - results: `page=4294967&size=500` (inside) and `page=4294968&size=500` (beyond); `page=2147483647&size=1`;
    - results beyond the range with a filter: `status=PASSED` gives the filtered total (1); `status=FAILED` gives 0.
- **The order of checks still holds on far pages:**
    - `size=101`: 400 `VALIDATION_FAILED`;
    - Ivy: 403 `FORBIDDEN`;
    - an unknown run: 404 `RUN_NOT_FOUND`;
    - `status=BROKEN`: 400 `VALIDATION_FAILED`.
- **Through the gateway (manual run):** all three answered 200 with `items=0` and the right `totalItems`:
    - `?page=21474837&size=100` (`totalItems` 1);
    - `?page=2147483647&size=1`;
    - `/results?page=2147483647&size=500` (`totalItems` 4).
- **Logs:** no ERROR line in `report.log`.

### QA-4 (S3, the gateway gave 500 and logged the query value): Closed, verified

- **Through the gateway (manual run):**
    - `POST /api/ci/runs?branch=QaLeakMarker67%FF` with an upload token: 400 "The request query could not be decoded.",
      with no `code`;
    - `GET {runs}?page=QaLeakMarker67%FF` with a JWT: 400, the same.
- **The 401/404 order is kept:** the same request without a token is 401, and an unrouted path with a JWT is 404.
- **Directly on report-service (RE12, still holding):** both requests are 400 `MALFORMED_REQUEST`.
- **Log capture across all 4 services:**
    - `QaLeakMarker67`: 0 occurrences;
    - ERROR lines: 0;
    - the upload token: 0 occurrences;
    - the gateway logged exactly 2 WARN lines, `Request query could not be decoded: InvalidParameterException` (type
      only).
- **New `api-gateway/.../qa/QaQueryDecodingTest` (16 rows, raw socket):**
    - with the marker in the parameter name or the value, on upload-tokens, the run results, the auth route `/api/orgs`,
      `/api/reports/**`, the scenario route `/tags` (overlong `%C0%AF`), the public `/api/ci/runs` and
      `/api/auth/register`, and the broken escapes `%`, `%G1`, `%F` and `%FF%FE=`: 400;
    - protected paths without a token or with an expired one: 401;
    - `/internal/...` and an unrouted path with a JWT: 404;
    - in every row: nothing is forwarded, no ERROR line, no marker in the body or the log, no "Character decoding" text.
- **Manual run setup:** my own `postgres:17` on a free port, throwaway secrets that were never printed, and the four
  boot jars (none older than its sources). PIDs 24229–24232 were recorded and killed by PID, and container
  `qa-fr1-24060` was removed with its volume.

### Mutants (on /tmp copies, since deleted): 6 of 6 killed

| Mutant                                                                 | Killed by                                                   |
|------------------------------------------------------------------------|-------------------------------------------------------------|
| R1: the offset guard computed in `int`, so it overflows                | `FarPageTest` and the qa far-page rows (9 failures)         |
| R2: a far run page reports `totalItems` 0                              | 3 failures                                                  |
| R3: a far result page returns the first row                            | 4 failures                                                  |
| R4: the result guard disabled                                          | 5 failures (500)                                            |
| G1: the decoding failure answered with 500                             | `QueryDecodingTest` and `QaQueryDecodingTest` (15 failures) |
| G2: the WARN line logs the exception message, which contains the value | 15 failures (marker found in the log)                       |

### Builds (one clean build per service, run one after the other)

| Service        | Tests | Failed | Skipped | Notes                                                                                     |
|----------------|-------|--------|---------|-------------------------------------------------------------------------------------------|
| report-service | 798   | 0      | 0       | backend-swe's 785 plus 13 new qa rows; qa total 381                                       |
| api-gateway    | 545   | 0      | 3       | backend-swe's 529 plus the new `QaQueryDecodingTest` (16); the 3 skips were already there |

### Updated verdict: **Signed off**

- Every one of the 61 ACs has a result and passes (27/27 P0).
- QA-1 to QA-4 are fixed and verified, so no S1, S2 or S3 bug is open.
- **Still open, for pm only:** QA-5 (numeric `expiresAt`), QA-6 (a repeated query parameter is joined) and QA-7 (a token
  name of only U+202E), all S4.
