# Test plan: report-service phase 1

- Feature: `report-service` (spec `docs/specs/report-service.md`, Ready, last updated 2026-11-02). **61 ACs: 27 P0, 33
  P1, 1 P2** (D1's "59 ACs, 26 P0, 32 P1" predates AC-2.15 (P0) and AC-7.4 (P1); see finding F1).
- Contracts: `docs/api/report-service.openapi.yaml` 1.0.0 (1.0.1 with the D9 Cucumber-JS note is due from T6),
  `docs/api/auth-service.openapi.yaml` 1.5.0 (C7), `docs/api/scenario-service.openapi.yaml` 1.0.4 (C6), gateway 0.2.4 (
  due from T11).
- Data model: `docs/erd/cucumber.dbml`, group `report_service` (as built per BD-3).
- Code at planning time: HEAD `75b19e5`, working tree with `docs/engineering/standards.md` and the spec modified. Batch
  A and T5 are in the tree; T6 and T7 (batch B) are being built in parallel, and batches C and D haven't started yet.
  Nothing was run for this plan (docs only, as instructed).
- Author: qa, 2026-11-02.

## Scope and references

**In scope:** every operation in D3; upload tokens (D4); CI authentication and C7 (D5); the manual-run tie to
scenario-service (D6); roles (D7); lifecycle (D8); the Cucumber JSON subset, including the Cucumber-JS 13 forms (D9);
planning (D10); environments and builds (D11); summary (D12); error order (D13); locking (D14); settings (D15);
logging (D17); auth-service AE1–AE5; gateway GW1–GW5; RE1–RE8.

**Read:** D1–D17, US-1 to US-8, the E-items, the guardrails, T1–T12, BD-1 to BD-7, RC-1, RC-2, FU-1, the contract
header (error order, headers, outbound calls), the upload operation, the C8 schemas, `StepResult`, and C7 in
auth-service 1.5.0.

**Accepted before verification (I assess these, I don't re-file them):**

- D5: the service-key deviation;
- D9: unknown fields are ignored;
- BD-1: AC-8.3 row 2 accepts upper-case ids at C7;
- BD-2: query 400 before JSON 400, page defaults, step requires `keyword`/`name`/`result` (now relaxed by the JS forms);
- BD-5, BD-6 (typed token exceptions, the single `@Pattern` name rule) and BD-7 (`UploadTokenCodec.hash` reused by the
  token service);
- RE6/RE7: JVM-wide redirect retry limit;
- RE8;
- RC-2: C7's 401 advertises `Bearer`. Not a bug here; it stays a P2 follow-up.

**Not in scope:** FU-1 (a separate task). Behaviour of scenario-service and auth-service beyond C5, C6 and C7.

## Testability findings for pm

The ACs are testable as written. None of these blocks verification. Items 2–9 need an answer before T6, T7 or T8 closes,
so that qa's assertions match the intended behaviour.

1. **F1 – AC counts in D1 are stale.** D1 still says 59 ACs, 26 P0, 32 P1. With AC-2.15 and AC-7.4 it is 61 ACs: 27 P0,
   33 P1, 1 P2. Docs only.
2. **F2 – The C8 contract lags D9.** At 1.0.0, `CucumberStep` requires `name`, and the schema has no `hidden` or
   `arguments`. A Cucumber-JS hidden hook is therefore invalid against the published schema. T6 owns 1.0.1. qa will
   check that 1.0.1 documents: the `hidden` hook form, the `arguments[].rows`/`arguments[].content` fallback, the "other
   `arguments` entries are ignored" rule, and the pointer used when `name` is missing.
3. **F3 – More than one `arguments` entry of the same kind.** D9 says the table comes from `arguments[].rows[].cells[]`.
   It doesn't say what happens when two entries both carry `rows`, or two carry `content`. Options: the first one wins;
   422 with a pointer; the last one wins. qa recommends "first wins" (Cucumber steps have at most one argument). Please
   state it.
4. **F4 – An explicit `null` on a read member that has a default.** For example `"name": null`, `"elements": null`,
   `"tags": null`, `"line": null`, `"duration": null`, `"error_message": null`. Should these be treated as absent (use
   the default) or as a wrong type (422)? Cucumber-JS emits no nulls, and some JVM plugins emit `"line": null`. qa will
   assert 422 unless pm says "null = absent".
5. **F5 – Hidden-hook keyword matching.** Is `Before ` (trailing space) or `before` a hook? D9 trims trailing whitespace
   from keywords but says "keyword `Before` or `After`". qa recommends: trim first, then match case-sensitively. A
   non-boolean `hidden` (`"true"`) is a wrong type (422).
6. **F6 – Background element hooks.** D9 moves a `background` element's `steps` onto the next scenario. It doesn't say
   what happens to the background's own `before`/`after` arrays (Cucumber-JVM puts hooks on the scenario element, but
   hand-written JSON can carry them). Options: ignore them; 422; attach them as hooks. qa recommends ignoring them.
7. **F7 – Concurrency "before" with a microsecond clock.** AC-2.14 says "`createdAt` before the token's `revokedAt`",
   and AC-4.8 says "`finishedAt` before the run's `finishedAt`". Under the project lock the second write can read the
   same microsecond tick, so the stamps can be equal. qa will assert `≤`, plus the stronger ordering evidence that the
   201 or 200 committed before the closing write. Please confirm, or amend the ACs to "not after".
8. **F8 – Header and transport edge cases on the upload that the spec doesn't cover.** qa will test these and report
   what happens; pm decides whether any of them is a bug.
    - (a) `Authorization: bearer <token>` in lower case. Standards §3 says the Bearer scheme is case-insensitive "for
      JWTs". qa expects this to be accepted on `/api/ci/runs` too.
    - (b) `Content-Type: application/json; charset=utf-8`: expected 201. `application/json; charset=ISO-8859-1`:
      undefined.
    - (c) `Content-Encoding: gzip`: expected not decoded, so 400 `MALFORMED_REQUEST`. A decoded body could bypass the 20
      MiB limit (zip bomb).
    - (d) A `Content-Length` larger than 20 MiB with a short body: expected 413 without reading the body.
9. **F9 – `X-Request-Id` to C7.** The auth-service 1.5.0 C7 operation accepts `X-Request-Id`, and the report-service
   contract lists C5 and scenario-service as forwarding it. D5 is silent for C7. qa will record whether it is forwarded,
   for log correlation in AC-7.2. Please decide whether it is required.
10. **F10 – `expiresAt` input format and boundary.**
    - The Timestamp schema requires `Z` and at most 6 fractional digits. qa will assert 400 for `+02:00` offsets and for
      7–9 fractional digits, unless pm prefers that they be accepted and normalised.
    - "Must be in the future": qa will assert that `expiresAt == now` is 400 and that `now + 1 µs` is 201.
    - An upload at exactly `expiresAt` is 401 ("in the future" is false).
11. **F11 – C7 answering the same project in upper case.** A 200 whose `projectId` is the token's project in upper case:
    is it "another projectId" (503) or the same id? auth-service emits lower case, so this only matters against a stub.
    qa recommends comparing parsed UUIDs, so the same project is accepted. Please confirm.
12. **F12 – Unbounded token list (P2, design).** `GET .../upload-tokens` has no pagination. Revoked tokens accumulate,
    because revoke is final and there's no delete. Not a phase 1 bug; noted for phase 2.
13. **F13 – `Cache-Control: no-store` on the token create response.** It isn't in the spec. The response carries a
    bearer secret, and RFC 9110 practice is `no-store`. qa will record what the service sends; it is not filed as a bug
    unless pm adds it.
14. **F14 – AC-2.7 and AC-3.7 rows with 3 s and 6 s delays.** These cost about 2 s and about 5 s of wall time each. qa
    keeps them as written, with the stub releasing its thread after the client times out so the build doesn't stall.
    Informational.

## AC → test map

Owner of the primary test: **B** = backend-swe's test (audited by qa against the Gherkin), **Q** = new qa test in
`org.reportservice.qa` (or `com.example.authservice.qa`), **M** = manual run.
"Audit" means: every Examples row is a separate assertion; status, `code`, the stated members, and the "nothing stored"
or "count unchanged" clause are asserted with a database count, not inferred.

### US-1 Upload tokens (T5)

| AC      | P  | Primary                       | qa audit focus / extra assertions                                                                                                                                                                                                                                       |
|---------|----|-------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| AC-1.1  | P0 | B `UploadTokensTest` + Q      | 47 chars, `^rct_[A-Za-z0-9_-]{43}$`, decodes to 32 bytes; `tokenPrefix` = first 12 characters; hash = lower-case SHA-256 hex (64 chars); **no column in any table** contains the token text (scan every text column of `upload_tokens`); `createdBy` = Cai; three nulls |
| AC-1.2  | P0 | B + Q                         | exact order, newest first; no `token`, `hash` or `tokenHash` member (JSON key scan, not just field absence in a record); `revokedBy` Cai                                                                                                                                |
| AC-1.3  | P0 | B + Q                         | same `revokedAt` (string-equal); `revokedBy` unchanged when **Ana** sends the second revoke; then upload is 401 and the run count is unchanged                                                                                                                          |
| AC-1.4  | P1 | B + Q                         | 6 rows plus the count unchanged per row; Q adds Unicode rows (below)                                                                                                                                                                                                    |
| AC-1.5  | P1 | B + Q                         | the expired and revoked tokens don't count; after one revoke, 201                                                                                                                                                                                                       |
| AC-1.6  | P0 | B + Q                         | 8 rows; Q extends to the full role × operation matrix (below)                                                                                                                                                                                                           |
| AC-1.7  | P1 | B                             | create 409 `PROJECT_ARCHIVED` and nothing stored; list 200; revoke 200, and the token is really revoked (upload 401)                                                                                                                                                    |
| AC-1.8  | P1 | B + Q                         | zero UUID and `{B1.id}` → 404; **{B1} remains active** afterwards (an upload with {B1} is 201 into Billing)                                                                                                                                                             |
| AC-1.9  | P1 | B `ConcurrentTokenCreateTest` | exactly one 201 and one 409; active count = 10 afterwards; repeated over ≥ 10 rounds; 3× class runs                                                                                                                                                                     |
| AC-1.10 | P0 | B `TokenSecrecyTest` + Q      | marker search for: token, 12-character prefix, hash, `Bearer rct_`, the JWT; at **root DEBUG** as well as the package-level DEBUG the AC states                                                                                                                         |

### US-2 CI upload (T6, T7)

| AC      | P  | Primary                             | qa audit focus / extra assertions                                                                                                                                                                                                                                                          |
|---------|----|-------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| AC-2.1  | P0 | B + Q                               | `Location` relative with lower-case ids; `finishedAt` == `createdAt` (string-equal); build name `1.4.2+517` (the `%2B` decoded); commit lower case; all 7 counts; `lastUsedAt` == `createdAt` read from the DB                                                                             |
| AC-2.2  | P0 | B + Q                               | positions 1..4; the 4 link fields null and `attempt` 1; `durationMs` 6; detail rows in order with kinds; AFTER_HOOK 0 ms, `keyword`/`text` null on hooks; OLD5 `errorMessage` and `Then` SKIPPED                                                                                           |
| AC-2.3  | P0 | B (ingest unit) + Q                 | 8 rows; Q adds all 15 pairwise precedence pairs, so every adjacent swap in the order is caught                                                                                                                                                                                             |
| AC-2.4  | P0 | B + Q                               | 7 rows, each with `WWW-Authenticate: Bearer`, run count unchanged, **C7 not called**, and `lastUsedAt` of {T2}/{T3} unchanged                                                                                                                                                              |
| AC-2.5  | P0 | B `CiPathAuthenticationTest`        | 401 `UNAUTHENTICATED`, C5 not called                                                                                                                                                                                                                                                       |
| AC-2.6  | P0 | B                                   | 409, run count unchanged, `lastUsedAt` unchanged, no environment or build row created                                                                                                                                                                                                      |
| AC-2.7  | P0 | B + Q                               | 7 rows; **exactly one** WARN line per request; the WARN line contains none of: the C7 URL or host:port, the project id path, the key, the token or prefix; nothing stored (runs, environments, builds, `lastUsedAt`); C7 received exactly 1 request (no retry)                             |
| AC-2.8  | P1 | B (ingest unit) + Q                 | 10 rows; exact pointer strings; run count unchanged; 422 `errors` ≤ 20                                                                                                                                                                                                                     |
| AC-2.9  | P1 | B + Q                               | 413 with and without `Content-Length`; the server stops reading (Q: assert the response arrives before the client finishes sending a 40 MiB chunked body, or the connection is closed); 415; 5,001 → 422; **boundary**: exactly 20,971,520 bytes → 201, and exactly 5,000 test cases → 201 |
| AC-2.10 | P1 | B + Q                               | 5 rows; `errors[].field` named; Q adds each parameter's boundaries (below)                                                                                                                                                                                                                 |
| AC-2.11 | P1 | B                                   | same env and build ids; branch still `main`; `Staging` creates a second environment (row count 2)                                                                                                                                                                                          |
| AC-2.12 | P1 | B + Q                               | 201; no attachment table exists and no 2 MB string is in any row (sum of column lengths); `errorMessage` = first 65,536 **code points** (Q: built from astral characters, so a UTF-16 cut would split a pair)                                                                              |
| AC-2.13 | P1 | B (ingest)                          | exact list                                                                                                                                                                                                                                                                                 |
| AC-2.14 | P1 | B + Q                               | 20 repetitions; each upload is 201 with `createdAt ≤ revokedAt` (F7), or 401 with nothing stored; no 500; 3× class runs                                                                                                                                                                    |
| AC-2.15 | P0 | B (ingest unit + one HTTP test) + Q | 4 rows in order; BEFORE_HOOK keyword/text null; table from `arguments`; docString from `arguments[].content`; AFTER_HOOK `errorMessage` "boom"; result FAILED; the `name`-less row → 422 with the exact pointer; hidden + name → STEP keyword `Before`                                     |

### US-3 Planning (T8)

| AC     | P  | Primary | qa audit focus / extra assertions                                                                                                                  |
|--------|----|---------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| AC-3.1 | P0 | B + Q   | full snapshot of "Pay by card" (dataTable, docString); feature versions 2 and 1; summary UNKNOWN 4, `passRate` null; `startedAt`/`finishedAt` null |
| AC-3.2 | P1 | B       | `tagExpression`; exactly 2 results; Q: `@Smoke` matches nothing (case-sensitive) → 422 `RUN_EMPTY`                                                 |
| AC-3.3 | P1 | B       | 7 rows, count unchanged, **scenario stub and C5 not called** (A3: 400 precedes the access check)                                                   |
| AC-3.4 | P1 | B       | `featureIds` member, exact order, lower case; nothing stored                                                                                       |
| AC-3.5 | P1 | B       | `RUN_EMPTY`; 2,001 → `RUN_TOO_LARGE`; Q: exactly 2,000 → 201                                                                                       |
| AC-3.6 | P1 | B       | 409, count unchanged; Q: a change on a feature that wasn't selected does not trigger 409                                                           |
| AC-3.7 | P0 | B + Q   | 6 rows; Q adds: 404 → 503; 3xx → 503 with exactly one request; 200 with a missing required field → 503; failure on the **second** list call → 503  |
| AC-3.8 | P1 | B       | exactly 3 requests, in order; `Authorization` byte-equal to Gus's; `X-Request-Id` `plan-1`; Q: n = 2 features → 4 requests (3 + n − 1)             |

### US-4 Recording and lifecycle (T9)

| AC     | P  | Primary | qa audit focus / extra assertions                                                                                                                                                                               |
|--------|----|---------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| AC-4.1 | P0 | B       | `executedBy` Hal; the run moves to IN_PROGRESS with `startedAt`; a second recording doesn't move `startedAt`                                                                                                    |
| AC-4.2 | P0 | B       | UNKNOWN and comment null; summary counts UNKNOWN; Q: does `executedBy`/`finishedAt` stay or reset on UNKNOWN? (D8: "UNKNOWN resets the result"; asserted as reset to null, otherwise filed to pm as a question) |
| AC-4.3 | P1 | B + Q   | 7 rows, result unchanged (DB row compared before and after); Q: 2,000 astral code points accepted; LF and TAB accepted; CR (U+000D) rejected; lone surrogate rejected                                           |
| AC-4.4 | P0 | B       | same `finishedAt`; 2 results stay UNKNOWN; later record → 409 `RUN_CLOSED`; cancel → 409                                                                                                                        |
| AC-4.5 | P1 | B       | idempotent cancel; complete → 409                                                                                                                                                                               |
| AC-4.6 | P1 | B       | 3 rows → 409 `RUN_NOT_MANUAL`; the automated run is unchanged                                                                                                                                                   |
| AC-4.7 | P1 | B + Q   | 3 rows; Q: Ana (ADMIN of both projects) uses Checkout's path with a Billing run → 404, and the Billing result is unchanged                                                                                      |
| AC-4.8 | P1 | B + Q   | 20 fresh runs; `≤` (F7); no 500; 3×                                                                                                                                                                             |

### US-5 Reads (T10)

| AC     | P  | Primary | qa audit focus / extra assertions                                                                                              |
|--------|----|---------|--------------------------------------------------------------------------------------------------------------------------------|
| AC-5.1 | P0 | B       | paging members; order newest first; list item members at top level (BD-2)                                                      |
| AC-5.2 | P0 | B + Q   | `durationMs` sum; `byFeature` order and counts; Q: one result with null duration is excluded from the sum, and all-null → null |
| AC-5.3 | P1 | B       | 1 item, `totalItems` 1                                                                                                         |
| AC-5.4 | P1 | B       | 5 rows, C5 not called                                                                                                          |
| AC-5.5 | P2 | B + Q   | 66.7; Q: half-up cases 1/8 = 12.5, 1/6 = 16.7, 5/6 = 83.3, 2/3 = 66.7, 0/n = 0.0, and 1/16 = 6.25 → 6.3 (kills HALF_EVEN)      |

### US-6 Security (T3, T9, T11)

| AC     | P  | Primary                                   | qa audit focus / extra assertions                                                                            |
|--------|----|-------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| AC-6.1 | P0 | B + Q                                     | 14 rows; Q runs the full matrix below                                                                        |
| AC-6.2 | P0 | B + Q                                     | 5 rows; nothing changed; Q adds token create (409), list and revoke (200)                                    |
| AC-6.3 | P0 | B `AccessCheckFailureMatrixTest`          | 8 rows; Q repeats rows 1, 2 and 6 on plan, record and token create: nothing stored, scenario stub not called |
| AC-6.4 | P0 | B `BearerAuthenticationTest`              | 4 rows, `WWW-Authenticate`, C5 not called                                                                    |
| AC-6.5 | P1 | B `StrictIdsTest`                         | 3 rows, C5 not called                                                                                        |
| AC-6.6 | P1 | **M** + B (GW4 `ReportServiceRoutesTest`) | manual run through the gateway (below)                                                                       |

### US-7 Operability (T2, T3, T7)

| AC     | P  | Primary                                      | qa audit focus / extra assertions                                                                                                     |
|--------|----|----------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| AC-7.1 | P1 | B `SettingsStartupTest`/`SettingsChecksTest` | 6 rows; message names the variable and contains no value (the test asserts the planted value is absent)                               |
| AC-7.2 | P1 | B `RequestCorrelationTest` + Q               | requestId `r-77` on every line; one aspect line per service call; content markers (below) absent                                      |
| AC-7.3 | P1 | B `SchemaTest`                               | compare with the ERD as built: columns, nullability, named unique indexes, CHECKs, `fk_` with RESTRICT, no defaults; no `attachments` |
| AC-7.4 | P1 | B + Q                                        | effective level of `org.hibernate.orm.core` is INFO under root DEBUG; no hash, step text, "expected rejected" or "Listing entities"   |

### US-8 C7 in auth-service (T4)

| AC     | P  | Primary                        | qa audit focus / extra assertions                                                                                                                                              |
|--------|----|--------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| AC-8.1 | P0 | B (auth-service)               | exactly 3 members; archived flips after Ana archives Checkout (no cache)                                                                                                       |
| AC-8.2 | P0 | B + Q                          | 4 rows; Q adds the key variants (below)                                                                                                                                        |
| AC-8.3 | P1 | B                              | 404; upper case → 200 with lower-case `projectId` (BD-1)                                                                                                                       |
| AC-8.4 | P0 | B + full auth-service build ×2 | full auth-service suite green with the key unset and with it set (two builds, counts compared with the previous auth-service report); C5 with `X-Service-Key` and no JWT → 401 |
| AC-8.5 | P1 | B + Q                          | `short` → start-up fails, naming the variable without the value; root-DEBUG log scan for the key and the header name with its value                                            |

## Role × operation matrices (effective role from C5)

Fixture, Checkout: Ana, Cai ADMIN; Gus MEMBER; Hal TESTER; Dan READER; Ivy, Eve no access. Billing: Ana ADMIN.

**Checkout, active project.** Q `QaRoleMatrixTest`: a parameterised test with all 7 users × 12 operations = 84 cells.
Writes run on fresh fixtures, and every denied write asserts the DB is unchanged.

| Operation (minimum role) | Ana                                    | Cai | Gus | Hal | Dan | Ivy | Eve |
|--------------------------|----------------------------------------|-----|-----|-----|-----|-----|-----|
| create token (ADMIN)     | 201                                    | 201 | 403 | 403 | 403 | 403 | 403 |
| list tokens (ADMIN)      | 200                                    | 200 | 403 | 403 | 403 | 403 | 403 |
| revoke token (ADMIN)     | 200                                    | 200 | 403 | 403 | 403 | 403 | 403 |
| plan run (MEMBER)        | 201                                    | 201 | 201 | 403 | 403 | 403 | 403 |
| cancel run (MEMBER)      | 200                                    | 200 | 200 | 403 | 403 | 403 | 403 |
| record result (TESTER)   | 200                                    | 200 | 200 | 200 | 403 | 403 | 403 |
| complete run (TESTER)    | 200                                    | 200 | 200 | 200 | 403 | 403 | 403 |
| list runs (READER)       | 200                                    | 200 | 200 | 200 | 200 | 403 | 403 |
| view run (READER)        | 200                                    | 200 | 200 | 200 | 200 | 403 | 403 |
| list results (READER)    | 200                                    | 200 | 200 | 200 | 200 | 403 | 403 |
| view result (READER)     | 200                                    | 200 | 200 | 200 | 200 | 403 | 403 |
| CI upload                | n/a (JWT → 401 `UPLOAD_TOKEN_INVALID`) |     |     |     |     |     |     |

On a 403, C5 was called exactly once and the scenario stub was not called.

**Checkout, archived project** (C5 `archived: true`; D7):

| Operation                       | Allowed role           | Result                                           |
|---------------------------------|------------------------|--------------------------------------------------|
| list tokens, revoke token       | ADMIN                  | 200                                              |
| create token                    | ADMIN                  | 409 `PROJECT_ARCHIVED`                           |
| plan, cancel                    | MEMBER                 | 409 `PROJECT_ARCHIVED`, scenario stub not called |
| record, complete                | TESTER                 | 409 `PROJECT_ARCHIVED`                           |
| all four reads                  | READER                 | 200                                              |
| any write                       | below its minimum role | 403 (the access check precedes 409, per D13)     |
| CI upload (C7 `archived: true`) | token                  | 409 `PROJECT_ARCHIVED`                           |

**Cross-project / IDOR** (Q `QaCrossProjectTest`; each case asserts the foreign object is unchanged):

- Ana on Checkout's path with a Billing run id, for view, results, record, complete and cancel → 404 `RUN_NOT_FOUND`.
- Run A's path with a result id of run B (same project) → 404 `RESULT_NOT_FOUND`.
- Ana revokes {B1} through Checkout's path → 404 `UPLOAD_TOKEN_NOT_FOUND`, and {B1} is still active.
- Cai (no role in Billing) on any Billing path → 403, even with a valid Billing run id (no existence leak: same body as
  for an unknown project).
- An upload with {B1} creates the run in **Billing**: invisible on Checkout's path (404) and visible to Ana on
  Billing's.
- A run id of Checkout used on the `{runs}` path of an unknown project → 403 (unknown project is 403, per D7).
- The `X-User-Id` header is ignored: Dan's JWT with `X-User-Id: <Ana>` on a write → 403.

## Upload-token cases (Q `QaUploadTokenTest`, `QaCiAuthenticationTest`)

**Issue:**

- 50 creates (with revokes in between, to stay under the limit) give 50 distinct tokens, prefixes and hashes.
- Each token decodes from base64url to exactly 32 bytes and has no padding.
- The response never repeats the token in any header.
- The list and revoke responses have no `token` member.
- `Cache-Control` is recorded (F13).

**Names** (1–100 code points, not blank, no control character):

| Name                                        | Expected                                                      |
|---------------------------------------------|---------------------------------------------------------------|
| 100 × U+1F600                               | 201                                                           |
| 101 × U+1F600                               | 400                                                           |
| `"​"`, `" "`, `" "`, `"‮"` alone            | 400 (blank)                                                   |
| `"a‮b"`                                     | 201                                                           |
| `"CI\u0085"` (C1 control)                   | 400                                                           |
| `"CI\u007F"`                                | 400                                                           |
| lone surrogate `"\uD800"`                   | 400                                                           |
| `"Ünïcødé"`                                 | 201, round-trips exactly                                      |
| a duplicate name                            | 201 (no uniqueness rule)                                      |
| unknown member `{"name":"x","scope":"all"}` | recorded against `additionalProperties: false` (expected 400) |
| `{"name":null}`                             | 400                                                           |
| `[]` or `"x"` as the body                   | 400 `MALFORMED_REQUEST`                                       |

**`expiresAt`** (adjustable clock, F10):

- `now` → 400; `now + 1 µs` → 201;
- `+02:00` offset → 400; 9 fractional digits → 400; `null` → 201 (never expires); a number → 400 `MALFORMED_REQUEST`.

**Active set and limit:**

- 10 active → 409.
- A token whose `expiresAt` passes (clock moved) frees a slot without being revoked.
- Revoking an expired token → 200, and `revokedAt` is set.
- A revoke by Ana after Cai's keeps Cai's `revokedBy` and `revokedAt`.

**Presentation on `/api/ci/runs`** (each → 401 `UPLOAD_TOKEN_INVALID` + `WWW-Authenticate`, run count unchanged, C7 not
called, unless the row says otherwise):

- the AC-2.4 rows;
- `rct_` + 42 or 44 characters;
- `rct_` + 43 characters including `+`, `/` or `=`;
- `RCT_` + valid body; `rcx_` + valid body;
- {T1} with one character's case flipped;
- {T1} followed by a space or a tab; a leading double space after `Bearer`;
- two `Authorization` headers ({T1} and garbage);
- `Bearer {T1}` sent as `X-Upload-Token` only;
- the token in the query string (`?token=`);
- `bearer {T1}` in lower case → **expected 201** (F8a).

**Expiry boundary** (adjustable clock): an upload at `expiresAt − 1 µs` → 201; at `expiresAt` exactly → 401.

**`lastUsedAt`:**

- set only on 201;
- unchanged on every 401, 409 `PROJECT_ARCHIVED`, 503, 413, 415, 400 and 422 path;
- two uploads → `lastUsedAt` = the second run's `createdAt`.

**Token on user paths:** `Bearer {T1}` on each of the 11 user operations → 401 `UNAUTHENTICATED`, C5 not called (AC-2.5
extended).

**Hashing owner (BD-7, code review):** only `UploadTokenCodec` hashes. A grep for `MessageDigest`/`sha256` outside it
must find nothing.

## Cucumber-JVM and Cucumber-JS ingest edge cases

These are mostly pure unit tests on the `ingest` reader (`QaCucumberIngestTest`; no Spring context), plus a few HTTP
checks in `QaUploadIngestTest`. Real samples: the T6 test resources (one Cucumber-JVM 7.18 and one Cucumber-JS 13.3.0
sample, sources named). qa re-derives the expected rows by hand from each sample.

**Mapping (JVM):**

- Background attachment:
    - A background followed by two scenarios: its steps go to **both**? D9 says "before the next scenario element". qa
      tests real JVM output, where the background is repeated before each scenario. With hand-written JSON (one
      background, then 2 scenarios), qa asserts per D9 that only the next scenario gets the steps, and records the
      result.
    - A trailing background with no scenario after it adds no row and creates no test case.
    - A background in feature 1 never reaches feature 2's first scenario.
- Row order with all hook kinds: element `before` ×2 → step 1 with step-level `before`/`after` → step 2 → element
  `after` ×2. The kinds and positions are asserted exactly.
- `scenario_outline` is read as scenario.
- Two elements with the same `id` give two results, both `attempt` 1.
- Positions run 1..n continuously across features.
- Tags:
    - feature `@a`, element `@a @b @a` → `[@a, @b]`;
    - `@A` and `@a` both kept (case-sensitive);
    - a feature with tags and no elements → `UPLOAD_EMPTY` if no other feature has a scenario.
- Keyword trimming: `'Given '` → `Given`; `'* '` → `*`; leading whitespace kept (only trailing is trimmed, per D9).
- Data table: ragged rows (2 cells, then 3) stored as given; empty `cells: []`; a cell that is a number → 422 with
  pointer `...rows[0].cells[1]`.
- Doc string: `doc_string.value` an empty string → `""` (not null); `doc_string` without `value` → recorded (expected
  422 for the missing required field, or null; filed to pm if unclear).

**Status rollup:**

- All 15 ordered pairs of the 6 statuses → the worse one by D9 precedence.
- A hook-only scenario (no steps, an `after` hook failed) → FAILED.
- No rows → UNKNOWN with `durationMs` null.

**Duration:**

- 2 rows of 999,999 ns → rows 0 ms and 0 ms, test case **1 ms** (floor of the sum, not the sum of floors).
- One row 1,500,000 ns plus one row without a duration → 1 ms (not null).
- `duration: 0` → 0 ms (not null).
- `duration: 9223372036854775807` (Long.MAX) on two rows → no overflow 500: either 422 or a correct total. Filed as a
  bug if 500.
- `duration: 1.5`, `"100"`, `1e3`, or a value above Long.MAX → 422 with pointer `.result.duration`.

**Strictness on read members** (each 422 `CUCUMBER_JSON_INVALID` with an exact pointer, run count 0):

- `uri` of 4,097 code points (4,096 astral code points → 201);
- `uri: ""`;
- `line: 0`; `line: -1`; `line: "12"`; `line: 1.0` (recorded);
- `tags: {}`; `tags[0].name: 5`; `tags[0]` without `name`;
- `elements: {}`; `type` absent; `type: "Scenario"` (case);
- `steps: "x"`; a step without `result`; `result.status` absent;
- a lone surrogate in `error_message`; `\u0000` in a `cells` value, in `doc_string.value` and in `uri`;
- `null` on defaulted members (F4).

**Ignored members:**

- `\u0000` and a lone surrogate inside `match.location`, `comments`, `embeddings[].data` or `id` → 201 (not read, so not
  validated).
- An unknown top-level member on a feature → 201.

**Error list:** a body with 30 independent errors → exactly 20 `errors`, in document order, the first one at the lowest
pointer.

**Parser robustness** (none may be a 500, and none may echo content in logs):

- nesting depth 2,000 in an ignored member → 400 `MALFORMED_REQUEST` or 422, never a 500;
- duplicate keys in one object (recorded; strict JSON may reject them);
- a top-level object or scalar → 422 pointer `$`;
- a UTF-8 BOM at the start (recorded);
- invalid UTF-8 bytes → 400;
- trailing garbage after the array → 400;
- a 15 MiB single string (`error_message`) → 201 with the truncated value stored, and the heap stays bounded (the test
  passes in the default test JVM).

**Cucumber-JS 13 forms:**

- AC-2.15 rows exactly.
- `hidden: true` + keyword `After` + no name, inside **step-level** position (between steps) → AFTER_HOOK at that
  position.
- `hidden: false` + `Before` + no name → 422 pointer `.name`.
- `hidden: "true"` → 422 (F5).
- `hidden: true` + keyword `Given` + no name → 422 `.name`.
- Keyword `Before ` with a trailing space (F5).
- `rows` present **and** `arguments[].rows` present → `rows` wins.
- `doc_string` present and `arguments[].content` present → `doc_string` wins.
- `arguments` with an unrelated entry (`{"foo":1}`) → ignored, so dataTable and docString are null.
- `arguments: {}` (not an array) → 422 (it is read when the fallback applies).
- Two `rows` entries (F3).
- `arguments[0].content: 5` → 422 with pointer `...arguments[0].content`.
- The JS sample's `duration` values stay in nanoseconds (A2): 400,000 ns → 0 ms.

## Upload limits and query parameters (Q `QaUploadLimitsTest`)

**Size:**

- 20,971,520 bytes (padding in an ignored member) → 201.
- 20,971,521 bytes → 413, with `Content-Length` and chunked.
- `Content-Length: 30000000` with a 10-byte body → 413 promptly (F8d).
- A 20 MiB upload: time recorded against the 5 s p95 stop condition (informational, 5 samples).

**Media type:**

- `text/plain` → 415.
- No `Content-Type` → 415.
- `application/json; charset=utf-8` → 201.
- `application/vnd.cucumber+json` → 415 (recorded).
- `Content-Encoding: gzip` (F8c).

**Order (D13, BD-2)** — each combination returns the earlier code:

| Combination                    | Expected                   |
|--------------------------------|----------------------------|
| no token + `text/plain`        | 401                        |
| `text/plain` + 21 MiB          | 415                        |
| 21 MiB + bad `commit`          | 413                        |
| bad `commit` + "not json" body | 400 `VALIDATION_FAILED`    |
| "not json" + archived (C7)     | 400                        |
| invalid JSON (422) + C7 down   | 422, and **C7 not called** |
| C7 down + archived             | 503                        |

**Counts:** 5,000 scenario elements + 100 backgrounds → 201 with 5,000 results; 5,001 → 422.

**Query boundaries:**

| Parameter     | Valid                        | Invalid (400 `VALIDATION_FAILED` with `errors[].field`)        |
|---------------|------------------------------|----------------------------------------------------------------|
| `name`        | 255 astral code points       | 256; `\t` (control); `%00`; a lone `%ED%A0%80`                 |
| `environment` | 100                          | 101                                                            |
| `build`       | 255                          | 256                                                            |
| `branch`      | 255                          | 256                                                            |
| `commit`      | `abcd`, 64 hex               | `abc`, 65 hex, `ghij`                                          |
| `ciUrl`       | `http://x`; 2,048 characters | `javascript:alert(1)`, `//host/x`, `http:/x`, 2,049 characters |

- A repeated parameter (`name=a&name=b`) is recorded.
- Every error is listed when two parameters are bad.
- The default name is `CI upload <createdAt>`, and that `createdAt` is the same instant as the run's.

## Concurrency (Q `QaConcurrencyTest`; plus B's AC-1.9, AC-2.14 and AC-4.8 classes; each class runs 3× in a row)

Each case uses a start barrier (`CountDownLatch`), at least 20 rounds, a check that no response is a 500, and a final DB
invariant check.

| Case                                                                                                                                           | Expected                                                                                                                               |
|------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| AC-1.9 extended: 5 concurrent creates at 9 active                                                                                              | exactly 1 × 201, 4 × 409; active = 10                                                                                                  |
| AC-2.14                                                                                                                                        | per the AC (F7)                                                                                                                        |
| AC-4.8                                                                                                                                         | per the AC (F7)                                                                                                                        |
| two uploads with the same **new** environment and build names                                                                                  | both 201; exactly 1 environment and 1 build row; the build metadata is from the one that committed first                               |
| complete racing cancel on a PLANNED run                                                                                                        | one wins; the other is 409 `RUN_CLOSED`; final status matches the winner; `finishedAt` set once                                        |
| two recordings on the same result                                                                                                              | both 200; final row equals one of them in full (status, comment and `executedBy` together, not mixed)                                  |
| first recordings on two different results of a PLANNED run                                                                                     | the run is IN_PROGRESS; `startedAt` set once (not overwritten by the second)                                                           |
| token revoke racing token create at 10 active                                                                                                  | no 500; the active count never exceeds 10                                                                                              |
| uploads into Checkout and Billing in parallel                                                                                                  | no lock wait between projects (both complete in < 1 s with the Checkout lock held for 3 s by a JDBC session)                           |
| lock timeout: a JDBC session holds `pg_advisory_xact_lock(<checkout key>)` for 2 s while an upload, a plan, a record and a token create arrive | each is 409 `CONFLICT` after about 1 s (test `lock_timeout`); nothing stored; `lastUsedAt` unchanged; no 500                           |
| C5 and C7 are not called inside a transaction                                                                                                  | code review plus a test: with the lock held, a C7 delay does not extend the time the lock is held (the lock is taken after C7 answers) |

## Leakage (Q `QaLogLeakageTest`; standards §3 Logging, D17)

**Method:**

- `OutputCaptureExtension`.
- The root logger is set to DEBUG right before use and restored in `finally` (standards §5).
- Unique **markers** are planted in every input.
- Every captured line, and every response body and header, is scanned.

**Markers:**

| Source        | Marker                                                                                                                  |
|---------------|-------------------------------------------------------------------------------------------------------------------------|
| upload token  | full value; 12-character prefix; SHA-256 hex; `Bearer rct_`                                                             |
| service key   | Base64 value; decoded bytes as hex; `X-Service-Key` with its value                                                      |
| JWT           | full; `eyJ` header segment of the test JWT                                                                              |
| Cucumber JSON | `uri`, feature name, scenario name, step text, a cell value, a doc string, `error_message` (`expected rejected`), a tag |
| user content  | manual comment; token name                                                                                              |
| query values  | `name`, `environment`, `build`, `branch`, `commit`, `ciUrl`                                                             |
| Hibernate     | `Listing entities`, `binding parameter`, `org.hibernate.orm.jdbc.bind`                                                  |

**Scenarios** (each a separate test, each asserting zero hits):

1. A full token lifecycle: create → upload → revoke (AC-1.10 at root DEBUG).
2. A successful upload with every marker present (AC-7.2, AC-7.4). Also: exactly one INFO upload line, with run id,
   count and duration; MDC `tokenId` = the token's **id**, never its value.
3. A malformed body `not json <marker>`. Risk: Jackson's `Unrecognized token '<marker>'` reaching a WARN line through
   Spring's `HttpMessageNotReadableException` logging.
4. A 422 body with markers in invalid fields. The log must not contain them. The Problem `errors[].message` must not
   echo the value either (recorded; filed S3 if content is echoed in logs, S4 if only in the response).
5. Each C7 failure row (AC-2.7): the WARN line contains no URL, host:port, project id, key or token.
6. Each C5 failure row: no JWT or `Authorization` value.
7. A scenario-service failure (planning): no JWT, no feature names, no response body fragments.
8. A 500 (forced via a stub returning a value that breaks a DB constraint, if reachable; otherwise skipped with a note):
   the body has no internals (`HttpCalls.Answer.assertNoInternals`), and the log has no SQL with values.
9. A start-up failure for each of the AC-7.1 rows: the planted secret is absent (B covers this; qa audits it).
10. auth-service C7 at root DEBUG: the key and the header value are absent from every line (AC-8.5).

**Responses:**

- The token list and revoke never carry the token or its hash.
- No `Server` version or stack trace in Problems.
- No CORS or `X-Request-Id` response header (contract).

## C5 failure matrix (consumer; AC-6.3 plus qa extensions)

Each row asserts: the stated answer; C5 received exactly 1 request (no retry, no cache); nothing stored; scenario stub
not called; no content in logs.

| C5 stub behaviour                             | Expected on `GET {runs}`                                                                                        | Also on plan / record / token create |
|-----------------------------------------------|-----------------------------------------------------------------------------------------------------------------|--------------------------------------|
| not listening                                 | 503 `ACCESS_CHECK_UNAVAILABLE`                                                                                  | same                                 |
| 500 / 502 / 503                               | 503                                                                                                             | same                                 |
| answers after 3 s (read timeout 2 s)          | 503 in about 2 s                                                                                                | same                                 |
| 200 role `OWNER` / missing role / `null` role | 503                                                                                                             | —                                    |
| 200, another projectId                        | 503                                                                                                             | —                                    |
| 200, unreadable body / empty body / HTML      | 503                                                                                                             | —                                    |
| 200, `archived` missing or a string           | 503 (recorded)                                                                                                  | —                                    |
| 302 to a stub that answers 200 READER         | 503; the target is **never** called (no redirect following, RE6)                                                | —                                    |
| 401                                           | 401 `UNAUTHENTICATED`                                                                                           | same                                 |
| 403                                           | 403 `FORBIDDEN`                                                                                                 | same                                 |
| 404                                           | per C4 consumer rules (scenario-service D4), as already implemented in `AccessCheckFailureMatrixTest`; recorded | —                                    |
| READER, then 403 on the next request          | 200, then 403 (no cache)                                                                                        | —                                    |
| connection reset after headers                | 503                                                                                                             | —                                    |

## C7 failure matrix (consumer, report-service; AC-2.7 plus qa extensions)

Each row asserts: the stated answer; C7 received exactly 1 request carrying the exact `X-Service-Key` and no
`Authorization`; nothing stored (runs, environments, builds); `lastUsedAt` unchanged; one WARN line without URL, key or
token.

| C7 stub behaviour                           | Expected                                                                           |
|---------------------------------------------|------------------------------------------------------------------------------------|
| not listening                               | 503 `PROJECT_CHECK_UNAVAILABLE`                                                    |
| 500 / 503                                   | 503                                                                                |
| answers after 3 s (read timeout 2 s)        | 503 in about 2 s (with a 1 s connect timeout, connect-phase behaviour isn't timed) |
| headers fast, body trickles past 2 s        | 503                                                                                |
| 200, unreadable / empty / `[]` body         | 503                                                                                |
| 200, another projectId                      | 503                                                                                |
| 200, same project in upper case             | recorded (F11)                                                                     |
| 200, `archived` missing / `null` / `"true"` | 503                                                                                |
| 200 with an extra member (`"region":"eu"`)  | **201** (C7 "fields are only ever added")                                          |
| 200 `archived: true`                        | 409 `PROJECT_ARCHIVED` (AC-2.6)                                                    |
| 400 / 403 / 429                             | 503                                                                                |
| 401 (wrong key)                             | 503                                                                                |
| 404                                         | 503                                                                                |
| 302 to a stub that answers 200              | 503; the target is never called                                                    |
| connection reset                            | 503                                                                                |

**Not called at all:** a 401 (each AC-2.4 row), 415, 413, 400 or 422 upload.
**Recorded:** whether `X-Request-Id` is forwarded (F9).

## C7 provider matrix (auth-service, Q `com.example.authservice.qa.QaServiceKeyTest`)

This reuses auth-service's existing test context with the key set. The "unset" row reuses backend-swe's unset context;
qa adds no new context.

| Request to `GET /internal/service/projects/{checkout}`                                              | Expected                                                                          |
|-----------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| right key                                                                                           | 200, exactly 3 members                                                            |
| header name `x-service-key` (lower case)                                                            | 200                                                                               |
| no key / empty value / key + trailing space / key minus its last character / key plus one character | 401                                                                               |
| the same bytes, Base64 without padding or URL-safe                                                  | 401 (exact value, per the contract)                                               |
| two `X-Service-Key` headers (right + wrong)                                                         | 401 (recorded)                                                                    |
| key in `Authorization: Bearer <key>`                                                                | 401                                                                               |
| key in the query string                                                                             | 401                                                                               |
| Ana's valid JWT only                                                                                | 401                                                                               |
| right key + a garbage `Authorization: Bearer x`                                                     | recorded; expected 200 (the JWT filter must not reject on this chain, RC-10 risk) |
| right key + `POST`                                                                                  | 405 (or 401 without the key)                                                      |
| right key on `/internal/service/projects` and `/internal/service/x`                                 | 404 / 401 recorded (no data)                                                      |
| right key on `GET /internal/projects/{checkout}/access` without a JWT                               | 401 (AC-8.4)                                                                      |
| right key on any `/api/**` path without a JWT                                                       | 401                                                                               |
| zero UUID                                                                                           | 404; `{checkout}` in upper case → 200 lower case (BD-1); `123` → 400              |
| Checkout archived, then unarchived                                                                  | `archived` true, then false (no cache)                                            |
| through the gateway (manual)                                                                        | gateway 404 (`/internal/**` never routed)                                         |

**Start-up:**

- `short`, `not base64!`, 31 bytes of Base64 → fails, naming the variable without the value.
- 32 bytes → starts.
- A value containing `${` → fails.

**Constant-time comparison:** code review (`MessageDigest.isEqual` or equivalent on bytes; no `String.equals`), plus
mutation M-A1.

## Manual end-to-end (AC-6.6; standards §6)

- Throwaway `postgres:17` on a free port with the databases `auth_service`, `scenario_service` and `report_service`.
- The gateway, auth-service, scenario-service and report-service started with throwaway secrets passed as environment
  variables (never written or printed). No `dev` profile. PIDs recorded.
- Afterwards: every process killed by PID, the container and volume removed.

**Steps:**

1. Seed the Reports fixture through the public APIs.
2. Create {T1} through the gateway.
3. Upload the CI sample at `http://localhost:8080/api/ci/runs` → 201 with 2/1/1.
4. Gus plans Payment + Address → 201 with 4 results.
5. Hal records 4 results and completes the run.
6. Dan reads the summary.
7. `GET /api/projects/{checkout}/runsx` → gateway 404.
8. `GET /api/ci/runs` and `POST /api/ci/runs/x` without a token → 401 at the gateway (GW4).
9. `/internal/service/projects/{checkout}` through the gateway → 404.
10. A 20 MiB upload through the gateway → 201 within the 10 s gateway timeout (timed).
11. Stop auth-service → upload 503 `PROJECT_CHECK_UNAVAILABLE` and `GET {runs}` 503 `ACCESS_CHECK_UNAVAILABLE`, with no
    data in the body.
12. Revoke {T1} → upload 401.
13. Grep every service log for the markers (token, key, JWT, step text).

## Planned mutation targets (about 22, on a `/tmp` copy, deleted afterwards)

Changed code only. Each mutant must be killed by at least one existing B or Q test. A survivor becomes a qa test, or is
filed as a gap.

| ID                   | File (as built)                 | Mutation                                                                                                                                                                         | Expected killer                                              |
|----------------------|---------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------|
| M-T1                 | `UploadTokenCodec`              | prefix length 12 → 11                                                                                                                                                            | AC-1.1                                                       |
| M-T2                 | `UploadTokenCodec`              | `HexFormat` lower → upper (or `%X`)                                                                                                                                              | AC-1.1 DB hash                                               |
| M-T3                 | `UploadTokenCodec`              | 32 random bytes → 31                                                                                                                                                             | AC-1.1 length 47                                             |
| M-T4                 | `UploadTokenServiceImpl`        | active limit `>= 10` → `> 10`                                                                                                                                                    | AC-1.5                                                       |
| M-T5                 | `UploadTokenServiceImpl`        | active count ignores `expiresAt`                                                                                                                                                 | AC-1.5 (expired one)                                         |
| M-T6                 | `UploadTokenServiceImpl`        | `expiresAt.isAfter(now)` → `!isBefore`                                                                                                                                           | Q `expiresAt == now`                                         |
| M-T7                 | `UploadTokenServiceImpl`        | revoke overwrites `revokedAt`/`revokedBy`                                                                                                                                        | AC-1.3 / Q Ana-second-revoke                                 |
| M-T8                 | `UploadTokenServiceImpl`        | archived check removed on create                                                                                                                                                 | AC-1.7                                                       |
| M-T9                 | `UploadTokenServiceImpl`        | project lock call removed                                                                                                                                                        | AC-1.9 (3×)                                                  |
| M-A1                 | token authenticator (CI path)   | expired check removed / `isAfter` → `isBefore`                                                                                                                                   | AC-2.4 row {T3}; Q boundary                                  |
| M-A2                 | token authenticator             | revoked check removed                                                                                                                                                            | AC-2.4 row {T2}, AC-1.3                                      |
| M-A3                 | `SecurityConfig` CI chain       | CI chain accepts JWT                                                                                                                                                             | AC-2.4 row JWT                                               |
| M-I1                 | `ingest` rollup                 | swap PENDING and UNDEFINED in the precedence order                                                                                                                               | Q pairwise                                                   |
| M-I2                 | `ingest` duration               | sum of floors instead of floor of the sum                                                                                                                                        | Q 2 × 999,999 ns                                             |
| M-I3                 | `ingest` duration               | null when **any** row lacks a duration                                                                                                                                           | Q mixed rows                                                 |
| M-I4                 | `ingest` truncation             | 65,536 → 65,535, or cut by UTF-16 units                                                                                                                                          | AC-2.12 astral                                               |
| M-I5                 | `ingest` background             | attach to every following scenario / drop                                                                                                                                        | Q background cases                                           |
| M-I6                 | `ingest` hooks                  | step-level hooks after the step instead of before                                                                                                                                | Q row-order case                                             |
| M-I7                 | `ingest` tags                   | no de-duplication / case-insensitive de-duplication                                                                                                                              | AC-2.13, Q `@A`/`@a`                                         |
| M-I8                 | `ingest` JS                     | `hidden` hook without checking `name` absent                                                                                                                                     | AC-2.15 row 3                                                |
| M-I9                 | `ingest` JS                     | `arguments` used even when `rows` present                                                                                                                                        | Q precedence                                                 |
| M-I10                | `ingest` limits                 | `> 5000` → `>= 5000`; errors cap 20 → 21                                                                                                                                         | AC-2.9 boundary; Q 30 errors                                 |
| M-U1                 | upload service                  | `lastUsedAt` not set / set before the C7 check                                                                                                                                   | AC-2.1; AC-2.6 unchanged                                     |
| M-U2                 | bounded body reader             | limit `>` → `>=`                                                                                                                                                                 | Q exactly 20,971,520 → 201                                   |
| M-C7                 | `ProjectStatusClient`           | projectId comparison removed                                                                                                                                                     | AC-2.7 row 5                                                 |
| M-S1                 | auth-service service-key filter | `isEqual` → prefix compare / unset key accepted                                                                                                                                  | Q key-minus-one-char; AC-8.2 row 4                           |
| Batch C (when built) | planning, lifecycle, summary    | tag filter case-insensitive; version bracket compare removed; `RUN_TOO_LARGE` `>` → `>=`; HALF_UP → HALF_EVEN; transition COMPLETED → CANCELLED allowed; `startedAt` overwritten | AC-3.2 / Q `@Smoke`; AC-3.6; Q 2,000; Q 1/16; AC-4.4; Q race |

The final list is fixed at verification time against the files actually changed (`git status`). It stays within 10–25
mutants per verification round; batch C mutants replace the batch B ones that are already killed if the cap is reached.

## Test data, support and runs

- **Support reused** (no new context): `@ReportServiceIntegrationTest`, `ReportsFixture`, `ReportData`, `TestTokens`,
  `AuthServiceStub` (C5, and C7 once T7 adds it), the scenario stub (T8), `HttpCalls`, `RequestLog`, `AdjustableClock`,
  `ProblemAssertions`.
- The ingest tests are plain JUnit with no context. The auth-service qa test reuses its existing context(s). The context
  count is reported in the report.
- The CI sample, the JS sample and the 20 MiB bodies are generated in code (no large files committed). The real samples
  are T6's resources.
- **Runs at verification:**
    - targeted `--tests 'org.reportservice.qa.*'`;
    - the full `clean build` of report-service, auth-service (key unset and set), and api-gateway (GW1–GW4 changed it).
      scenario-service only if `git status` shows it changed;
    - `ConcurrentTokenCreateTest`, the AC-2.14 and AC-4.8 classes and `QaConcurrencyTest` run 3× in a row;
    - one manual end-to-end run.
    - Counts come from `build/test-results/test/*.xml`.

## Risks and items not covered

- Constant-time comparison is verified by code review and mutation, not by timing measurement.
- The performance NFRs (run list ≤ 200 ms p95, planning ≤ 2 s) are measured once in the manual run and are informational
  only.
- Real Cucumber versions other than JVM 7.18 and JS 13.3.0 are out of scope (D9's deviation covers additive fields).
- Batch C and D rows can only be executed after those batches land. This plan is revised then, if the as-built names or
  the answers to F3–F11 change any expected result.
