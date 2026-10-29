# report-service phase 1: test runs and results

Status: **Ready** (approved by the user on 2026-10-26: Q1–Q10 as recommended). **Gate:** Batch A starts only after the platform-hardening batch is accepted, including gateway FU-4.
Last updated: 2026-10-26 (user approval recorded; T11's FU-4 fallback removed). Earlier the same day: first draft.

**Resolved questions (user, 2026-10-26, all as recommended):**
- Q1: scope (D1).
- Q2: token home A, report-service (D4).
- Q3: C7 with `INTERNAL_SERVICE_KEY`, the named §3 deviation (D5).
- Q4: automated results are unlinked snapshots in phase 1 (D6).
- Q5: REST instead of gRPC (D2).
- Q6: roles (D7).
- Q7: GW1–GW5.
- Q8: gateway FU-4 goes into the platform-hardening batch and lands before this spec.
- Q9: the ERD changes.
- Q10: RE1–RE5 and AE1–AE5. The user chose this feature on 2026-10-26, to be built **after** the platform-hardening batch. User decision (2026-10-26): results come in through **both** a CI upload of Cucumber JSON, authenticated by a hashed per-project upload token (shown once, rotatable), **and** manual recording.
**Rulebook:** `docs/engineering/standards.md` (2026-10-26) is binding. This spec states only feature-specific rules and its named deviations (D5, D9).

## Problem and goal
At `fc178b6`, nothing can record test results:
- `report-service` is an empty Boot 4.1 app: main class, one `contextLoads` test, an `application.properties` with the name only. Its `build.gradle` has JPA, a gRPC client with the protobuf plugin, security and validation, all unused, and no web starter.
- The ERD's `report_service` group (approved design 2026-10-12; ERD-D2, D5, D8, D9) is unbuilt.
- scenario-service is accepted (2026-10-26). It publishes stable feature, scenario and example-row ids and derived test cases (C6, D9, D13; contract 1.0.4).
- auth-service removed `projects.secret_token` and left the CI token to this spec (auth-service-projects D8). C5 needs a user JWT, which CI doesn't have.
- The gateway has only the placeholder `/api/reports/**`, and no public path for CI.

**Goal:**
- A project ADMIN issues CI upload tokens.
- CI uploads Cucumber JSON and gets an automated run.
- MEMBERs plan manual runs from features, and TESTERs record a status per test case.
- Everyone with access reads runs, results and a run summary.

**Success:** through the gateway, a CI job posts the CI sample with a token and gets a COMPLETED run with 2 passed, 1 failed and 1 pending. Gus plans a manual run of Payment and Address (4 test cases), Hal records results and completes it, and Dan reads the summary. Without a token, or with a revoked one, nothing is stored. With auth-service down, every call fails with 503 and returns no data.

## Scope
**In:**
- upload tokens (create, list, revoke) and the CI upload of Cucumber JSON;
- manual runs: plan from features with an optional single tag; record a status and comment per test case; complete; cancel;
- run list, run view with summary, results and result detail;
- environments and builds created on first use;
- auth-service C7 (D5), gateway routes, Flyway V1.

**Out (later phases):**
- attachments (uploaded `embeddings` are ignored; no `attachments` table);
- linking automated results to scenario-service ids (D6);
- dashboards, trends, per-tag reports, living documentation;
- full tag expressions, step-level manual recording, reopening a run, deleting runs;
- environment and build management, rerun grouping, JUnit XML or NDJSON input, rate limiting.

## Glossary (new terms only)
| Term | Meaning |
|---|---|
| Upload token | `rct_` + 43 base64url characters (32 random bytes; 47 characters). Shown once; stored only as its SHA-256 hash (D4). |
| Active token | Not revoked, and `expiresAt` null or in the future. |
| CI upload | `POST /api/ci/runs`, with `Authorization: Bearer <upload token>` and a Cucumber JSON body. It creates one AUTOMATED run. |
| Cucumber JSON | Cucumber's `json` formatter output (JVM 7, JS). Only D9's subset is read. |
| Manual / automated run | `source` MANUAL (planned by people) or AUTOMATED (created by a CI upload). |
| Unlinked result | An automated result with `featureId`, `scenarioId`, `exampleRowId` and `featureVersion` all null (D6). |
| Run summary | Counts per status, `executed`, `passRate`, `durationMs` and `byFeature`, computed on read (D12). |
| C7 / service key | auth-service's `GET /internal/service/projects/{projectId}`, authenticated by `X-Service-Key: <INTERNAL_SERVICE_KEY>` and not by a user (D5). |
| Reports fixture | auth-service-projects' Projects fixture, served by a C5 stub and a C7 stub. Checkout: Ana and Cai `ADMIN`, Gus `MEMBER`, Hal `TESTER`, Dan `READER`; Ivy and Eve have no access. Billing: Ana `ADMIN`. Plus the scenario stub and the CI sample below. |
| Scenario stub | An HTTP stub for scenario-service 1.0.4. **Checkout:** (1) **Payment** `{payment}` (`features/checkout/payment.feature`, name `Payment`, `currentVersion` 2), with 3 test cases: `Pay by card` (SCENARIO `{s-card}`, line 12, tags `@checkout @smoke`; steps `Given a basket with these items:` with data table `[["item","price"],["Mug","8.00"]]`, `When the customer pays by card`, and `Then the receipt is:` with doc string `Total: 8.00`); `Pay with voucher SAVE10` (EXAMPLE_ROW `{r-save10}`, scenario `{s-voucher}`, line 30, tags `@checkout @voucher`); `Pay with voucher OLD5` (`{r-old5}`, line 31, same tags). (2) **Address** `{address}` (`features/checkout/address.feature`, v1), with `Change address` (`{s-address}`, line 5, tags `@address @smoke`). (3) **Legacy** `{legacy}`, archived. **Billing:** **Invoice** `{invoice}`. |
| CI sample | A Cucumber JSON array of 2 features. **(1)** `uri` `features/checkout/payment.feature`, name `Payment`, tags `@checkout`, with three background + scenario pairs. Each background step `Given a basket` passed in 1,000,000 ns. The scenarios: `Pay by card` (line 12, tags `@checkout @smoke`; `When` passed in 2,000,000 ns; `Then` passed in 3,000,000 ns; an `after` hook passed in 500,000 ns); `Pay with voucher SAVE10` (line 30, all steps passed); `Pay with voucher OLD5` (line 31; `When` failed with `error_message` `expected rejected but was accepted`; `Then` skipped). **(2)** `features/checkout/address.feature`: `Change address` (line 5, one step `pending`). |
| `{T1}`, `{T2}`, `{T3}`, `{B1}` | Checkout tokens: active, revoked, and expired 1 h ago. `{B1}` is an active Billing token. |
| `{runs}` | `/api/projects/{checkout}/runs` |

## Decisions (recommended defaults)

### D1. Scope and slug
**Default:** slug `report-service`; phase 1 is the "In" list: 8 stories, 59 ACs (26 P0, 32 P1, 1 P2), 4 batches.
**Why:** it's the smallest set that brings results in both ways the user chose.

### D2. REST, not gRPC
**Default:**
- report-service serves REST/JSON, and calls C5, C7 and scenario-service with `RestClient`.
- Remove the gRPC client starter, its test starter and the protobuf plugin (RE1).

**Why:** the platform, C5 and scenario-service are all REST, and nothing serves gRPC. Unused dependencies are forbidden.

### D3. Paths and the gateway
**Default:** port 8083.

| Operation | Method and path |
|---|---|
| Upload tokens | `POST`, `GET /api/projects/{projectId}/upload-tokens`; `POST .../upload-tokens/{tokenId}/revoke` |
| CI upload (public at the gateway; token-authenticated in report-service) | `POST /api/ci/runs?name=&environment=&build=&branch=&commit=&ciUrl=` |
| Manual run | `POST {runs}`; `POST {runs}/{runId}/complete`; `POST {runs}/{runId}/cancel` |
| Record | `PUT {runs}/{runId}/results/{resultId}` |
| Read | `GET {runs}?page=&size=`; `GET {runs}/{runId}`; `GET {runs}/{runId}/results?status=&page=&size=`; `GET {runs}/{runId}/results/{resultId}` |

- **Gateway, GW1:** the `reports` route gains `/api/projects/*/runs`, `/api/projects/*/runs/**`, `/api/projects/*/upload-tokens`, `/api/projects/*/upload-tokens/**` and `/api/ci/runs`. It keeps `/api/reports/**`, as scenario-service kept its placeholder.
- **Gateway, GW2:** `POST /api/ci/runs` becomes public. The gateway forwards `Authorization` unevaluated (gateway D4).

**Why:** user paths are project-scoped, as in scenario-service. The CI path carries no project id: the token names the project, so CI needs only one secret.

### D4. Upload tokens live in report-service
**Default:**
- A table `upload_tokens` in report-service (ERD), following auth-service-projects D8:
  - 32 bytes from `SecureRandom`; format `rct_` + base64url without padding;
  - only the SHA-256 hex hash is stored, unique;
  - the token is returned only by create; `tokenPrefix` (first 12 characters) is shown in lists;
  - name: 1–100 code points, not blank, no control character;
  - optional `expiresAt`, which must be in the future;
  - `lastUsedAt` is set by each successful upload;
  - revoke is idempotent and final;
  - at most **10 active tokens** per project, otherwise 409 `UPLOAD_TOKEN_LIMIT`.
- A presented token is looked up by the hash of its full text. Unknown, revoked, expired and malformed tokens all get the same 401 `UPLOAD_TOKEN_INVALID`.
- Managing tokens needs project `ADMIN`.

**Why:** ERD-D2 gives ingestion to report-service. With the token next to the runs, an upload makes one remote call (C7), and auth-service gains only one small read endpoint.
**Alternative B:** tokens in auth-service: a table, a V8 migration, a management API and a verify endpoint. Same security, about three times the auth-service change.

### D5. How a CI request authenticates and reaches its project (named deviation from standards §3)
**Default:**
1. The security chain authenticates `/api/ci/**` only with an upload token, and every other path only with a JWT:
   - a JWT on `/api/ci/runs` → 401 `UPLOAD_TOKEN_INVALID`;
   - a token on a user path → 401 `UNAUTHENTICATED`.
2. The token row gives the `project_id`.
3. Archived projects are read-only, so report-service calls **C7** `GET /internal/service/projects/{projectId}` with `X-Service-Key`. The answer is `{projectId, orgId, archived}`.
   - `archived: true` → 409 `PROJECT_ARCHIVED`.
   - A 404 or 401 from C7 → 503 `PROJECT_CHECK_UNAVAILABLE` (misconfiguration; projects are never hard-deleted).
   - As for C5: no cache, no retry, connect 1 s, read 2 s, fail closed with 503 `PROJECT_CHECK_UNAVAILABLE`.
4. **The key** is standard Base64 of at least 32 bytes, from `INTERNAL_SERVICE_KEY`.
   - It's required by report-service.
   - It's optional in auth-service: unset means `/internal/service/**` answers 401 to everything, so no existing auth-service test or deployment changes.
   - It's compared in constant time and never logged. The gateway never routes `/internal/**`.

**Deviation:** standards §3 routes cross-service project access through C5. C5 needs a user, and CI has none. C7 only says whether a project exists and is archived; C5 stays the only source of a user's role.
**Why:** a static key is the smallest service credential that works now. mTLS or signed service tokens can replace it later without changing C7's shape.

### D6. How runs tie to scenario-service (no scenario-service change)
**Default:**
- **Manual runs** use scenario-service's existing public REST (1.0.4, C6), called directly at `SCENARIO_SERVICE_URL` (default `http://localhost:8082`). The caller's `Authorization` and `X-Request-Id` are forwarded unchanged.
  1. `GET /api/projects/{p}/features` (path, name, `currentVersion` of the active features).
  2. `GET .../features/{id}/test-cases` for each selected feature.
  3. The list again. If a selected feature's `currentVersion` changed: 409 `FEATURE_CHANGED`, and nothing is stored.
- Each manual result stores `featureId`, `featureVersion` (the number; scenario-service exposes no version ids), `scenarioId`, `exampleRowId`, and a snapshot: path, feature name, name, effective tags, line, and steps with data table and doc string.
- **Automated results are unlinked in phase 1:** a snapshot only, with the `uri` as `featurePath`. CI has no user token, and Cucumber JSON doesn't say which version ran. Linking comes in phase 2, through a scenario-service service contract.
- **Calls to scenario-service:** connect 1 s, read 5 s, no retry.
  - A failure, timeout, 5xx, unreadable body or unexpected status → 503 `SCENARIO_SERVICE_UNAVAILABLE`.
  - A 401 → 401. A 403 or 404 → 503 (C5 already passed, so it means an inconsistency).

**Why:** no change to an accepted service, and the bracketing list makes the recorded version exact.

### D7. Who can do what
| Operation | Minimum effective role | Archived project |
|---|---|---|
| List and view runs, results, summary | `READER` | allowed |
| Record a result; complete a manual run | `TESTER` | 409 `PROJECT_ARCHIVED` |
| Plan a manual run; cancel a run | `MEMBER` | 409 `PROJECT_ARCHIVED` |
| Create, list, revoke upload tokens | `ADMIN` | create 409; list and revoke allowed |
| CI upload | an active token of the project | 409 `PROJECT_ARCHIVED` |

- No access and an unknown project both give 403 `FORBIDDEN`.

**Why:** these are auth-service-projects D3's published meanings. Revoking a credential must always be possible.

### D8. Run lifecycle
**Default:**
- **MANUAL:**
  - plan → PLANNED;
  - the first recorded result → IN_PROGRESS, and `startedAt` is set;
  - `complete` (from PLANNED or IN_PROGRESS) → COMPLETED, and `finishedAt` is set; unrecorded results stay UNKNOWN;
  - `cancel` (from PLANNED or IN_PROGRESS) → CANCELLED, and `finishedAt` is set;
  - complete on COMPLETED, or cancel on CANCELLED → 200, unchanged;
  - any other transition, or recording on a closed run → 409 `RUN_CLOSED`.
- **AUTOMATED:**
  - created COMPLETED; `finishedAt` = the upload time; `startedAt` and `createdBy` null; `uploadTokenId` set;
  - record, complete and cancel → 409 `RUN_NOT_MANUAL`.
- **Recording:**
  - `status` is `PASSED`, `FAILED`, `SKIPPED` or `UNKNOWN` (UNKNOWN resets the result);
  - optional `comment`: at most 2,000 code points, no control characters except LF and TAB; null clears it;
  - sets `executedBy` and `finishedAt`; the last write wins.
- API statuses are UPPER_CASE.

### D9. Cucumber JSON ingestion (named deviation: unknown fields are ignored)
**Default:**
- **Request:**
  - `Content-Type: application/json`, otherwise 415 `UNSUPPORTED_MEDIA_TYPE`;
  - at most **20 MiB** (20,971,520 bytes), otherwise 413 `UPLOAD_TOO_LARGE`, also when there's no `Content-Length` (reading stops at the limit);
  - the body is parsed and validated before any transaction.
- **Subset read.** Unknown fields (`match`, `comments`, `start_timestamp`, `embeddings`) are ignored; that's the deviation, because Cucumber versions add fields.
  - Top level: a non-empty array of features.
  - Feature: `uri` (string, 1–4,096 code points), `name` (default `""`), `tags[].name`, `elements` (default `[]`).
  - Element: `type` is `background` or `scenario` (`scenario_outline` is read as `scenario`); `name`, `line` (integer ≥ 1, optional), `tags[].name`, `steps`, `before`, `after`.
  - Step: `keyword` (trailing whitespace trimmed), `name`, `doc_string.value`, `rows[].cells[]`, `result`, and optional step-level `before` and `after`.
  - Result: `status` is exactly one of `passed`, `failed`, `skipped`, `pending`, `undefined`, `ambiguous`; `duration` (integer ≥ 0, nanoseconds, optional); `error_message` (optional).
- **Strict on what is read:**
  - a wrong type, a missing required field, an unknown status, a negative duration, or U+0000 or a lone surrogate in a string → 422 `CUCUMBER_JSON_INVALID`, with `errors[]` entries `{pointer, message}` as JSONPath (for example `$[0].elements[3].steps[1].result.status`), at most 20 entries;
  - JSON that doesn't parse → 400 `MALFORMED_REQUEST`.
- **Mapping:**
  - each `scenario` element is one test case;
  - a `background` element's steps are put before the next scenario element of the same feature;
  - results keep the order of features, then elements (`position` 1..n);
  - rows within a test case: element `before` hooks (`BEFORE_HOOK`), then each step with its step-level hooks directly before and after it, then element `after` hooks (`AFTER_HOOK`);
  - tags: the feature's, then the element's, each once, at its first position;
  - `attempt` is always 1: two elements with the same id are two results.
- **Status:** the worst row by Cucumber precedence: FAILED > AMBIGUOUS > UNDEFINED > PENDING > SKIPPED > PASSED. No rows → UNKNOWN.
- **Duration:**
  - per row: floor(ns / 1,000,000) ms;
  - per test case: floor(sum of the row ns / 1,000,000) ms, or null when no row has a duration.
- **Limits:**
  - no scenario element → 422 `UPLOAD_EMPTY`;
  - more than **5,000** test cases → 422 `UPLOAD_TOO_MANY_TEST_CASES`;
  - `error_message` is stored truncated to its first 65,536 code points.
- **Query parameters** (each invalid one → 400 `VALIDATION_FAILED`):
  - `name`: 1–255 code points, not blank, no control character; default `CI upload <createdAt>`;
  - `environment`: 1–100 code points; `build` and `branch`: 1–255 code points; same character rules as `name`;
  - `commit`: 4–64 hexadecimal characters, stored lower case;
  - `ciUrl`: an absolute http(s) URL of at most 2,048 characters.

### D10. Manual run planning
**Default:**
- Body:
  - `name`: as in D9;
  - `featureIds`: 1–20 distinct, canonical UUIDs;
  - `tag`: optional; starts with `@`, no whitespace, at most 1,000 code points;
  - `environment`, `build`: optional, as in D9.
- A feature id that isn't an active feature of the project → 422 `FEATURE_NOT_PLANNABLE`, with a member `featureIds` listing all such ids.
- Test cases come in request order, then text order. With a `tag`, only test cases whose effective tags contain it exactly (case-sensitive) are kept.
- 0 test cases → 422 `RUN_EMPTY`; more than 2,000 → 422 `RUN_TOO_LARGE`.
- Results and their step snapshots start as UNKNOWN.

### D11. Environments and builds
**Default:**
- Matched by exact (case-sensitive) name, and created on first use under the project lock.
- A build keeps the `branch`, `commit` and `ciUrl` of the run that created it. Later values are ignored silently.
- Responses show `environment {id, name}` and `build {id, name, branch, commitSha, ciUrl}`, or null.
- No management API.

### D12. Run summary
Computed on read:
- `counts`: all 7 statuses, including zeros;
- `total`; `executed` = total − UNKNOWN;
- `passRate` = PASSED / executed × 100, rounded half-up to 1 decimal; null when `executed` is 0;
- `durationMs` = the sum of the non-null durations, or null;
- `byFeature[]`: `{featurePath, featureName, total, counts}`, in order of first appearance.

The run list shows `total`, `counts` and `passRate` for each run.

### D13. Errors and their order
**Problems** follow standards §3.

**New codes:**
- 401 `UPLOAD_TOKEN_INVALID`;
- 404 `UPLOAD_TOKEN_NOT_FOUND`, `RUN_NOT_FOUND`, `RESULT_NOT_FOUND`;
- 409 `UPLOAD_TOKEN_LIMIT`, `RUN_CLOSED`, `RUN_NOT_MANUAL`, `FEATURE_CHANGED`;
- 413 `UPLOAD_TOO_LARGE`; 415 `UNSUPPORTED_MEDIA_TYPE`;
- 422 `CUCUMBER_JSON_INVALID`, `UPLOAD_EMPTY`, `UPLOAD_TOO_MANY_TEST_CASES`, `FEATURE_NOT_PLANNABLE`, `RUN_EMPTY`, `RUN_TOO_LARGE`;
- 503 `ACCESS_CHECK_UNAVAILABLE`, `PROJECT_CHECK_UNAVAILABLE`, `SCENARIO_SERVICE_UNAVAILABLE`.

**Copied from scenario-service:** `UNAUTHENTICATED`, `FORBIDDEN`, `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `NOT_FOUND`, `PROJECT_ARCHIVED`, `CONFLICT`, `INTERNAL_ERROR`, and the lock-timeout code.

**Order on user paths:** 401 → 400 → access check (503/401/403) → 409 `PROJECT_ARCHIVED` → 404 → business 409/422 → scenario-service calls.
**Order on the upload:** 401 → 415 → 413 → 400 → 422 → C7 (503) → 409 `PROJECT_ARCHIVED` → write.
A run, result or token of another project is a 404.

### D14. Persistence and concurrency
- Standards §4. Database and role `report_service`; settings `REPORT_DB_URL`, `REPORT_DB_USERNAME`, `REPORT_DB_PASSWORD`.
- `V1__create_report_service_schema.sql` creates the ERD's `environments`, `builds`, `upload_tokens`, `test_runs`, `test_case_results` and `step_results`. It does **not** create `attachments`.
- Every write (token create and revoke, upload, plan, record, complete, cancel) first takes a transaction-scoped advisory lock keyed by `project_id`, as scenario-service does. Under the lock it re-reads the token state and the run status.
- Remote calls and parsing happen before the transaction. Inserts are batched.

### D15. Settings
- `JWT_SECRET`, `REPORT_DB_*`, `INTERNAL_SERVICE_KEY`;
- `AUTH_SERVICE_URL` (default `http://localhost:8081`), `SCENARIO_SERVICE_URL` (default `http://localhost:8082`).
- Each is checked after binding: a missing value fails start-up, naming the variable and never printing a value.
- `application-dev.yaml` imports the repository-root `.env`, as scenario-service's SE6a does, and holds no secret.
- `.env.example` gains empty keys for the new settings (RE5).

### D16. Testing
- Standards §5.
- In-process HTTP stubs for C5, C7 and scenario-service, answering according to the Reports fixture.
- Concurrency classes: AC-1.9, AC-2.14, AC-4.8.

### D17. Logging
- Standards §3. MDC holds `requestId`; `userId` on user paths; `tokenId` (never the token) on uploads.
- **Never logged:** token values or prefixes, the service key, `Authorization`, Cucumber JSON content, step texts, error messages, comments, query values.
- One INFO line per upload: run id, test-case count, duration.

## User stories and acceptance criteria
Every request is a direct call to report-service unless it says "through the gateway". `{token of X}` is as defined in scenario-service.

### US-1: Upload tokens (P0)
As a project admin, I want CI tokens I can issue, see and revoke, so that CI can upload without a person's password.
```gherkin
@US-1 @AC-1.1 @P0
Scenario: A token is shown once and stored only as a hash
  When Cai sends "POST /api/projects/{checkout}/upload-tokens" with {"name":"GitHub Actions"}
  Then the response is 201 with "id", "name" "GitHub Actions", a "token" of 47 characters starting "rct_", "tokenPrefix" equal to its first 12 characters, "createdBy" Cai's id, "expiresAt" null, "lastUsedAt" null, "revokedAt" null
  And upload_tokens holds the lower-case SHA-256 hex of the token and no column contains the token text

@US-1 @AC-1.2 @P0
Scenario: The list never shows a token
  Given Cai created "GitHub Actions" and then "Jenkins", and revoked "Jenkins"
  When Ana sends "GET /api/projects/{checkout}/upload-tokens"
  Then the response is 200 with exactly ["Jenkins","GitHub Actions"] (newest first), each with "tokenPrefix" and no "token" or hash member
  And "Jenkins" has a "revokedAt" and "revokedBy" Cai's id

@US-1 @AC-1.3 @P0
Scenario: Revoke is final and idempotent
  When Cai sends "POST .../upload-tokens/{T1.id}/revoke" twice
  Then both responses are 200 with the same "revokedAt"
  And a CI upload of the CI sample with {T1} is a Problem 401 / UPLOAD_TOKEN_INVALID

@US-1 @AC-1.4 @P1
Scenario Outline: Invalid token requests store nothing
  When Cai sends "POST /api/projects/{checkout}/upload-tokens" with <body>
  Then the response is a Problem <status> / <code> and the number of tokens is unchanged
  Examples:
    | body                                             | status | code              |
    | {"name":""}                                      | 400    | VALIDATION_FAILED |
    | {"name":"   "}                                   | 400    | VALIDATION_FAILED |
    | {"name":"<101 × a>"}                             | 400    | VALIDATION_FAILED |
    | {"name":"CI\u0007"}                              | 400    | VALIDATION_FAILED |
    | {"name":42}                                      | 400    | MALFORMED_REQUEST |
    | {"name":"CI","expiresAt":"2020-01-01T00:00:00Z"} | 400    | VALIDATION_FAILED |

@US-1 @AC-1.5 @P1
Scenario: At most 10 active tokens per project
  Given Checkout has 10 active tokens, 1 revoked and 1 expired
  When Cai creates {"name":"Eleventh"}
  Then the response is a Problem 409 / UPLOAD_TOKEN_LIMIT
  And after Cai revokes one active token, creating "Eleventh" is 201

@US-1 @AC-1.6 @P0
Scenario Outline: Only project admins manage tokens
  When <user> sends <operation> on Checkout's upload tokens
  Then the response status is <status>
  Examples:
    | user | operation | status |
    | Ana  | create    | 201    |
    | Cai  | list      | 200    |
    | Cai  | revoke    | 200    |
    | Gus  | create    | 403    |
    | Hal  | list      | 403    |
    | Dan  | revoke    | 403    |
    | Ivy  | list      | 403    |
    | Eve  | create    | 403    |

@US-1 @AC-1.7 @P1
Scenario: Archived project: no new tokens, but list and revoke work
  Given Checkout is archived
  Then Cai's create is a Problem 409 / PROJECT_ARCHIVED, his list is 200 and his revoke of {T1} is 200

@US-1 @AC-1.8 @P1
Scenario Outline: Unknown tokens
  When Cai sends "POST /api/projects/{checkout}/upload-tokens/<id>/revoke"
  Then the response is a Problem 404 / UPLOAD_TOKEN_NOT_FOUND
  Examples:
    | id                                   |
    | 00000000-0000-4000-8000-000000000000 |
    | {B1.id}                              |

@US-1 @AC-1.9 @P1
Scenario: Two concurrent creates at the limit
  Given Checkout has 9 active tokens
  When Cai and Ana each create a token at the same moment
  Then exactly one gets 201 and the other a Problem 409 / UPLOAD_TOKEN_LIMIT, never a 500

@US-1 @AC-1.10 @P0
Scenario: Token secrecy
  When Cai creates a token, CI uploads the CI sample with it, and Cai revokes it, with log capture at DEBUG
  Then no log line contains the token, its prefix, its hash or an Authorization value
```

### US-2: CI upload of Cucumber JSON (P0)
As a CI job, I want to post my Cucumber JSON with a project token, so that every pipeline run is recorded without a person.
```gherkin
@US-2 @AC-2.1 @P0
Scenario: Upload creates a completed automated run
  When CI sends "POST /api/ci/runs?name=Nightly&environment=staging&build=1.4.2%2B517&branch=main&commit=9FCEB02" with "Authorization: Bearer {T1}" and the CI sample
  Then the response is 201 with "Location" "{runs}/<id>" and a run with "source" "AUTOMATED", "status" "COMPLETED", "name" "Nightly", "createdBy" null, "startedAt" null and "finishedAt" equal to "createdAt"
  And "environment" has name "staging", and "build" has name "1.4.2+517", branch "main" and commitSha "9fceb02"
  And "summary" has total 4, counts PASSED 2, FAILED 1, PENDING 1 and the other 4 statuses 0, "executed" 4 and "passRate" 50.0
  And {T1}'s "lastUsedAt" equals the run's "createdAt"

@US-2 @AC-2.2 @P0
Scenario: Results are snapshots in upload order
  Given the upload of AC-2.1
  When Dan sends "GET {runs}/<id>/results"
  Then by position: "Pay by card" PASSED, "Pay with voucher SAVE10" PASSED, "Pay with voucher OLD5" FAILED, "Change address" PENDING
  And each has "featureId", "featureVersion", "scenarioId" and "exampleRowId" null, and "attempt" 1
  And "Pay by card" has "featurePath" "features/checkout/payment.feature", "featureName" "Payment", "line" 12, "tags" ["@checkout","@smoke"] and "durationMs" 6
  And its detail lists STEP "Given" "a basket" PASSED 1 ms, STEP "When" PASSED 2 ms, STEP "Then" PASSED 3 ms and AFTER_HOOK PASSED 0 ms
  And OLD5's "When" step has "errorMessage" "expected rejected but was accepted", and its "Then" step is SKIPPED

@US-2 @AC-2.3 @P0
Scenario Outline: Test case status is the worst row status
  When CI uploads one scenario whose rows have statuses <rows>
  Then that result's status is <status>
  Examples:
    | rows                                 | status    |
    | passed, passed                       | PASSED    |
    | passed, skipped                      | SKIPPED   |
    | pending, skipped                     | PENDING   |
    | undefined, skipped                   | UNDEFINED |
    | passed, ambiguous                    | AMBIGUOUS |
    | passed, failed, skipped              | FAILED    |
    | before hook failed, skipped, skipped | FAILED    |
    | (no steps, no hooks)                 | UNKNOWN   |

@US-2 @AC-2.4 @P0
Scenario Outline: Only an active upload token authenticates an upload
  When CI uploads the CI sample with Authorization <authorization>
  Then the response is a Problem 401 / UPLOAD_TOKEN_INVALID with "WWW-Authenticate: Bearer", and the number of runs is unchanged
  Examples:
    | authorization                     |
    | (none)                            |
    | Bearer                            |
    | Bearer rct_<43 random characters> |
    | Bearer {T2}                       |
    | Bearer {T3}                       |
    | Bearer {token of Cai}             |
    | Basic {T1}                        |

@US-2 @AC-2.5 @P0
Scenario: An upload token is not a user credential
  When "GET {runs}" carries "Authorization: Bearer {T1}"
  Then the response is a Problem 401 / UNAUTHENTICATED

@US-2 @AC-2.6 @P0
Scenario: No uploads into an archived project
  Given C7 answers Checkout with "archived" true
  When CI uploads the CI sample with {T1}
  Then the response is a Problem 409 / PROJECT_ARCHIVED, the number of runs is unchanged and {T1}'s "lastUsedAt" is unchanged

@US-2 @AC-2.7 @P0
Scenario Outline: The project check fails closed
  Given the C7 stub <behaviour>
  When CI uploads the CI sample with {T1}
  Then the response is a Problem 503 / PROJECT_CHECK_UNAVAILABLE, nothing is stored, and one WARN line names the failure type without URL, key or token
  Examples:
    | behaviour                           |
    | is not listening                    |
    | answers 500                         |
    | answers after 3 s                   |
    | answers 200 with an unreadable body |
    | answers 200 with another projectId  |
    | answers 401 (wrong key)             |
    | answers 404                         |

@US-2 @AC-2.8 @P1
Scenario Outline: Invalid Cucumber JSON stores nothing
  When CI uploads <body> with {T1}
  Then the response is a Problem <status> / <code> <detail> and the number of runs is unchanged
  Examples:
    | body                                                 | status | code                  | detail                                                 |
    | the text "not json"                                  | 400    | MALFORMED_REQUEST     |                                                        |
    | {"uri":"a.feature"}                                  | 422    | CUCUMBER_JSON_INVALID | with an errors entry pointer "$"                        |
    | the CI sample with "Passed" as the first step status | 422    | CUCUMBER_JSON_INVALID | with pointer "$[0].elements[0].steps[0].result.status"  |
    | the CI sample without the first "uri"                | 422    | CUCUMBER_JSON_INVALID | with pointer "$[0].uri"                                 |
    | the CI sample with the first scenario "name" 42      | 422    | CUCUMBER_JSON_INVALID | with pointer "$[0].elements[1].name"                    |
    | the CI sample with an element "type" "outline"       | 422    | CUCUMBER_JSON_INVALID | with pointer "$[0].elements[1].type"                    |
    | the CI sample with a "duration" of -1                | 422    | CUCUMBER_JSON_INVALID | with a pointer ending ".result.duration"                |
    | the CI sample with "\u0000" in a step name           | 422    | CUCUMBER_JSON_INVALID | with a pointer ending ".name"                           |
    | []                                                   | 422    | UPLOAD_EMPTY          |                                                        |
    | one feature with only a background element           | 422    | UPLOAD_EMPTY          |                                                        |

@US-2 @AC-2.9 @P1
Scenario Outline: Size and media type limits
  When CI uploads <request> with {T1}
  Then the response is a Problem <status> / <code> and the number of runs is unchanged
  Examples:
    | request                                       | status | code                       |
    | a valid body of 20,971,521 bytes              | 413    | UPLOAD_TOO_LARGE           |
    | the same body chunked, without Content-Length | 413    | UPLOAD_TOO_LARGE           |
    | the CI sample as "Content-Type: text/plain"   | 415    | UNSUPPORTED_MEDIA_TYPE     |
    | a body with 5,001 scenario elements           | 422    | UPLOAD_TOO_MANY_TEST_CASES |

@US-2 @AC-2.10 @P1
Scenario Outline: Query parameters are validated
  When CI uploads the CI sample with {T1} and query <query>
  Then the response is a Problem 400 / VALIDATION_FAILED with an errors entry for <field>
  Examples:
    | query                        | field       |
    | name=%20%20                  | name        |
    | environment=<101 × e>        | environment |
    | commit=xyz                   | commit      |
    | ciUrl=ftp://ci.example.com/1 | ciUrl       |
    | ciUrl=not-a-url              | ciUrl       |

@US-2 @AC-2.11 @P1
Scenario: Environments and builds are reused by name; the first build metadata wins
  Given the upload of AC-2.1
  When CI uploads again with "environment=staging&build=1.4.2%2B517&branch=release"
  Then both runs have the same "environment.id" and "build.id", and the build's "branch" is still "main"
  And an upload with "environment=Staging" creates a second environment

@US-2 @AC-2.12 @P1
Scenario: What phase 1 ignores or shortens
  When CI uploads the CI sample with an "embeddings" array (a 2 MB PNG) on a step, "match" and "start_timestamp" fields, and an "error_message" of 70,000 code points
  Then the response is 201, no attachment is stored, and the stored "errorMessage" is its first 65,536 code points

@US-2 @AC-2.13 @P1
Scenario: Tags are the feature's then the element's, each once
  When CI uploads a feature tagged "@checkout" with a scenario tagged "@checkout @smoke"
  Then the result's "tags" are ["@checkout","@smoke"]

@US-2 @AC-2.14 @P1
Scenario: A revoke racing an upload
  When Cai revokes a fresh token while CI uploads the CI sample with it, repeated 20 times
  Then each upload is either 201 with "createdAt" before the token's "revokedAt", or a Problem 401 / UPLOAD_TOKEN_INVALID with nothing stored; never a 500
```

### US-3: Plan a manual run (P0)
As a test lead, I want a run built from chosen features, so that testers work through exactly what Cucumber would execute.
```gherkin
@US-3 @AC-3.1 @P0
Scenario: Plan a manual run from two features
  When Gus sends "POST {runs}" with {"name":"Release 1.4 regression","featureIds":["{payment}","{address}"],"environment":"staging"}
  Then the response is 201 with "source" "MANUAL", "status" "PLANNED", "createdBy" Gus's id, "summary" total 4 with counts UNKNOWN 4, and "passRate" null
  And its results by position are "Pay by card" (scenarioId {s-card}, exampleRowId null), "Pay with voucher SAVE10" ({s-voucher}, {r-save10}), "Pay with voucher OLD5" ({s-voucher}, {r-old5}) and "Change address" ({s-address})
  And the first three have "featureId" {payment} and "featureVersion" 2; the fourth has {address} and 1
  And "Pay by card"'s detail has 3 UNKNOWN steps: "Given" "a basket with these items:" with dataTable [["item","price"],["Mug","8.00"]], "When" "the customer pays by card", and "Then" "the receipt is:" with docString "Total: 8.00"

@US-3 @AC-3.2 @P1
Scenario: A single tag narrows the run
  When Gus plans {"name":"Smoke","featureIds":["{payment}","{address}"],"tag":"@smoke"}
  Then the run has "tagExpression" "@smoke" and exactly the results "Pay by card" and "Change address"

@US-3 @AC-3.3 @P1
Scenario Outline: Invalid plans store nothing
  When Gus plans <body>
  Then the response is a Problem 400 / <code> and the number of runs is unchanged
  Examples:
    | body                                                  | code              |
    | {"name":"R","featureIds":[]}                          | VALIDATION_FAILED |
    | {"name":"R","featureIds":[<21 distinct ids>]}         | VALIDATION_FAILED |
    | {"name":"R","featureIds":["{payment}","{payment}"]}   | VALIDATION_FAILED |
    | {"name":"R","featureIds":["{payment} "]}              | VALIDATION_FAILED |
    | {"name":"R","featureIds":["{payment}"],"tag":"smoke"} | VALIDATION_FAILED |
    | {"name":"","featureIds":["{payment}"]}                | VALIDATION_FAILED |
    | {"name":7,"featureIds":["{payment}"]}                 | MALFORMED_REQUEST |

@US-3 @AC-3.4 @P1
Scenario: Features that can't be planned
  When Gus plans {"name":"R","featureIds":["{payment}","{legacy}","{invoice}","00000000-0000-4000-8000-000000000000"]}
  Then the response is a Problem 422 / FEATURE_NOT_PLANNABLE with "featureIds" ["{legacy}","{invoice}","00000000-0000-4000-8000-000000000000"]

@US-3 @AC-3.5 @P1
Scenario: Empty and oversized runs
  Then Gus's plan of Payment with "tag" "@nothing" is a Problem 422 / RUN_EMPTY
  And a plan whose stubbed features hold 2,001 test cases is a Problem 422 / RUN_TOO_LARGE

@US-3 @AC-3.6 @P1
Scenario: A feature saved during planning
  Given the scenario stub reports Payment at version 2 on the first list and 3 on the second
  When Gus plans Payment
  Then the response is a Problem 409 / FEATURE_CHANGED and the number of runs is unchanged

@US-3 @AC-3.7 @P0
Scenario Outline: scenario-service problems fail closed
  Given the scenario stub <behaviour>
  When Gus plans Payment
  Then the response is a Problem <status> / <code> and the number of runs is unchanged
  Examples:
    | behaviour                           | status | code                         |
    | is not listening                    | 503    | SCENARIO_SERVICE_UNAVAILABLE |
    | answers 500 for test cases          | 503    | SCENARIO_SERVICE_UNAVAILABLE |
    | answers after 6 s                   | 503    | SCENARIO_SERVICE_UNAVAILABLE |
    | answers 200 with an unreadable body | 503    | SCENARIO_SERVICE_UNAVAILABLE |
    | answers 403                         | 503    | SCENARIO_SERVICE_UNAVAILABLE |
    | answers 401                         | 401    | UNAUTHENTICATED              |

@US-3 @AC-3.8 @P1
Scenario: The caller's identity is forwarded unchanged
  When Gus plans Payment with "X-Request-Id: plan-1"
  Then the scenario stub received exactly 3 requests (list, test cases, list), each with Gus's "Authorization" header and "X-Request-Id" "plan-1"
```

### US-4: Record manual results (P0)
As a tester, I want to set each test case's status with an optional comment, and close the run, so that the team knows what passed.
```gherkin
@US-4 @AC-4.1 @P0
Scenario: The first recorded result starts the run
  Given Gus planned the run of AC-3.1
  When Hal sends "PUT {runs}/<id>/results/<Pay by card>" with {"status":"PASSED"}
  Then the response is 200 with "status" "PASSED", "executedBy" Hal's id and a "finishedAt"
  And the run is "IN_PROGRESS" with a "startedAt"

@US-4 @AC-4.2 @P0
Scenario: Re-recording replaces the status; UNKNOWN resets it
  When Hal records {"status":"FAILED","comment":"Voucher accepted"} on OLD5, then {"status":"UNKNOWN","comment":null}
  Then OLD5 has "status" "UNKNOWN" and "comment" null, and the summary counts it as UNKNOWN

@US-4 @AC-4.3 @P1
Scenario Outline: Invalid recordings change nothing
  When Hal records <body> on "Pay by card"
  Then the response is a Problem 400 / <code> and the result is unchanged
  Examples:
    | body                                        | code              |
    | {"status":"passed"}                         | VALIDATION_FAILED |
    | {"status":"PENDING"}                        | VALIDATION_FAILED |
    | {"status":"AMBIGUOUS"}                      | VALIDATION_FAILED |
    | {}                                          | VALIDATION_FAILED |
    | {"status":"PASSED","comment":"<2,001 × c>"} | VALIDATION_FAILED |
    | {"status":"PASSED","comment":"a\u0007"}     | VALIDATION_FAILED |
    | {"status":1}                                | MALFORMED_REQUEST |

@US-4 @AC-4.4 @P0
Scenario: Complete closes the run
  Given Hal recorded 2 results
  When Hal sends "POST {runs}/<id>/complete" twice
  Then both answers are 200 with "status" "COMPLETED" and the same "finishedAt", and the 2 unrecorded results stay UNKNOWN
  And a later recording is a Problem 409 / RUN_CLOSED, and so is a cancel

@US-4 @AC-4.5 @P1
Scenario: Cancel closes a planned run
  When Gus cancels a PLANNED run twice
  Then both answers are 200 with "status" "CANCELLED", and a complete is then a Problem 409 / RUN_CLOSED

@US-4 @AC-4.6 @P1
Scenario Outline: Automated runs can't be changed by hand
  Given the automated run of AC-2.1
  When <user> sends <operation> on it
  Then the response is a Problem 409 / RUN_NOT_MANUAL
  Examples:
    | user | operation                      |
    | Hal  | record PASSED on "Pay by card" |
    | Hal  | complete                       |
    | Gus  | cancel                         |

@US-4 @AC-4.7 @P1
Scenario Outline: Unknown runs and results
  When Hal records PASSED at <path>
  Then the response is a Problem 404 / <code>
  Examples:
    | path                                                    | code             |
    | {runs}/00000000-0000-4000-8000-000000000000/results/<r> | RUN_NOT_FOUND    |
    | {runs}/<a Billing run>/results/<its result>             | RUN_NOT_FOUND    |
    | {runs}/<run A>/results/<a result of run B>              | RESULT_NOT_FOUND |

@US-4 @AC-4.8 @P1
Scenario: Complete racing a recording
  When Hal completes a run while Cai records a result on it, repeated 20 times on fresh runs
  Then each recording is either 200 with "finishedAt" before the run's "finishedAt", or a Problem 409 / RUN_CLOSED; never a 500
```

### US-5: Read runs and the summary (P0)
As anyone on the project, I want runs with a summary and their results, so that I can see the state of the software.
```gherkin
@US-5 @AC-5.1 @P0
Scenario: Runs are listed newest first with their summary
  Given the AC-2.1 upload, then the AC-3.1 run, then a third run
  When Dan sends "GET {runs}?page=0&size=2"
  Then the response is 200 with "page" 0, "size" 2, "totalItems" 3 and items [third, AC-3.1 run], each with "source", "status", "environment", "build", "total", "counts" and "passRate"
  And page 1 holds only the AC-2.1 run

@US-5 @AC-5.2 @P0
Scenario: The run view carries the summary per feature
  When Dan views the AC-2.1 run
  Then "summary.durationMs" is the sum of the 4 result durations
  And "summary.byFeature" is [payment.feature: "featureName" "Payment", total 3, PASSED 2, FAILED 1, others 0; address.feature: total 1, PENDING 1, others 0]

@US-5 @AC-5.3 @P1
Scenario: Results can be filtered by status
  When Dan sends "GET {runs}/<AC-2.1 run>/results?status=FAILED"
  Then exactly "Pay with voucher OLD5" is returned, with "totalItems" 1

@US-5 @AC-5.4 @P1
Scenario Outline: Invalid read parameters
  When Dan sends "GET <path>"
  Then the response is a Problem 400 / VALIDATION_FAILED
  Examples:
    | path                              |
    | {runs}?size=0                     |
    | {runs}?size=101                   |
    | {runs}?page=-1                    |
    | {runs}/<id>/results?status=BROKEN |
    | {runs}/<id>/results?size=501      |

@US-5 @AC-5.5 @P2
Scenario: Pass rate rounding
  Given a manual run with 3 test cases recorded PASSED, PASSED and FAILED
  Then its "passRate" is 66.7 and "executed" is 3
```

### US-6: Secure and fail-closed (P0)
```gherkin
@US-6 @AC-6.1 @P0
Scenario Outline: Role × operation on Checkout
  When <user> sends <operation>
  Then the response status is <status>
  Examples:
    | user | operation                      | status |
    | Dan  | list runs / view run / results | 200    |
    | Hal  | list runs                      | 200    |
    | Dan  | record a result                | 403    |
    | Hal  | record a result                | 200    |
    | Hal  | complete a run                 | 200    |
    | Hal  | plan a run                     | 403    |
    | Hal  | cancel a run                   | 403    |
    | Gus  | plan a run                     | 201    |
    | Gus  | cancel a run                   | 200    |
    | Gus  | create an upload token         | 403    |
    | Cai  | create an upload token         | 201    |
    | Ivy  | list runs                      | 403    |
    | Eve  | view run                       | 403    |
    | Ivy  | plan a run                     | 403    |

@US-6 @AC-6.2 @P0
Scenario Outline: Archived project is read-only
  Given C5 answers Checkout with "archived" true
  When <user> sends <operation>
  Then the response is <result>
  Examples:
    | user | operation       | result                           |
    | Dan  | view run        | 200                              |
    | Gus  | plan a run      | a Problem 409 / PROJECT_ARCHIVED |
    | Hal  | record a result | a Problem 409 / PROJECT_ARCHIVED |
    | Hal  | complete a run  | a Problem 409 / PROJECT_ARCHIVED |
    | Gus  | cancel a run    | a Problem 409 / PROJECT_ARCHIVED |

@US-6 @AC-6.3 @P0
Scenario Outline: The access check fails closed and is never cached
  Given the C5 stub <behaviour>
  When Dan sends "GET {runs}"
  Then the response is a Problem <status> / <code>
  Examples:
    | behaviour                                     | status | code                     |
    | is not listening                              | 503    | ACCESS_CHECK_UNAVAILABLE |
    | answers 500                                   | 503    | ACCESS_CHECK_UNAVAILABLE |
    | answers after 3 s                             | 503    | ACCESS_CHECK_UNAVAILABLE |
    | answers role "OWNER"                          | 503    | ACCESS_CHECK_UNAVAILABLE |
    | answers another projectId                     | 503    | ACCESS_CHECK_UNAVAILABLE |
    | answers 401                                   | 401    | UNAUTHENTICATED          |
    | answers 403                                   | 403    | FORBIDDEN                |
    | answered READER, then 403 on the next request | 403    | FORBIDDEN                |

@US-6 @AC-6.4 @P0
Scenario Outline: Only valid JWTs authenticate user paths
  When "GET {runs}" carries <authorization>
  Then the response is a Problem 401 / UNAUTHENTICATED with "WWW-Authenticate: Bearer", and C5 was not called
  Examples:
    | authorization                                      |
    | (none)                                             |
    | an expired token of Dan                            |
    | an HS256 token of Dan                              |
    | a token of Dan with "sub" "{Dan.id}" in upper case |

@US-6 @AC-6.5 @P1
Scenario Outline: Strict ids
  When Dan sends "GET <path>"
  Then the response is a Problem 400 / MALFORMED_REQUEST, and C5 was not called
  Examples:
    | path                             |
    | /api/projects/{checkout}%20/runs |
    | {runs}/{run id in upper case}    |
    | {runs}/123                       |

@US-6 @AC-6.6 @P1
Scenario: Through the gateway (manual run per standards §6)
  Given the gateway, auth-service, scenario-service and report-service run with throwaway settings
  Then a CI upload to "http://localhost:8080/api/ci/runs" with an upload token is 201
  And Gus's plan, and Dan's "GET /api/projects/{checkout}/runs", succeed through the gateway
  And "GET /api/projects/{checkout}/runsx" with a token is the gateway's 404
```

### US-7: Operable service (P1)
```gherkin
@US-7 @AC-7.1 @P1
Scenario Outline: Start-up refuses missing or bad settings without printing values
  When report-service starts with <setting> <state>
  Then start-up fails with a message naming <setting> and containing no setting value
  Examples:
    | setting              | state              |
    | REPORT_DB_PASSWORD   | missing            |
    | REPORT_DB_URL        | "${REPORT_DB_URL}" |
    | JWT_SECRET           | 32 bytes of Base64 |
    | INTERNAL_SERVICE_KEY | missing            |
    | INTERNAL_SERVICE_KEY | "not base64!"      |
    | INTERNAL_SERVICE_KEY | 16 bytes of Base64 |

@US-7 @AC-7.2 @P1
Scenario: Correlated, content-free logs
  When CI uploads the CI sample, and Hal records a result with a comment, with log capture at DEBUG and "X-Request-Id: r-77"
  Then every line for those requests has requestId "r-77", with one outcome line per service call
  And no line contains a step text, "expected rejected", the comment, a query value, a token, the service key or a JWT

@US-7 @AC-7.3 @P1
Scenario: The schema is exactly the ERD's phase-1 part
  When Flyway has migrated an empty database
  Then the tables are exactly environments, builds, upload_tokens, test_runs, test_case_results and step_results (plus flyway_schema_history), with the ERD's columns, nullability, named unique indexes, CHECKs, and fk_ constraints with ON DELETE RESTRICT, and no column default
```

### US-8: auth-service project status for services, C7 (P0)
```gherkin
@US-8 @AC-8.1 @P0
Scenario: A service with the key reads a project's status
  Given auth-service runs with INTERNAL_SERVICE_KEY set to the test key
  When a service sends "GET /internal/service/projects/{checkout}" with "X-Service-Key: <test key>"
  Then the response is 200 with exactly {"projectId":"{checkout}","orgId":"{acme}","archived":false}
  And after Ana archives Checkout, "archived" is true

@US-8 @AC-8.2 @P0
Scenario Outline: Without the right key there is no answer
  When a service sends "GET /internal/service/projects/{checkout}" with <credentials>
  Then the response is a Problem 401 / UNAUTHENTICATED
  Examples:
    | credentials                                                          |
    | no X-Service-Key                                                     |
    | "X-Service-Key: <another 32-byte key>"                               |
    | "Authorization: Bearer {token of Ana}" only                          |
    | the right key, but auth-service runs with INTERNAL_SERVICE_KEY unset |

@US-8 @AC-8.3 @P1
Scenario Outline: Unknown and malformed project ids
  When a service with the key sends "GET /internal/service/projects/<id>"
  Then the response is a Problem <status> / <code>
  Examples:
    | id                                   | status | code              |
    | 00000000-0000-4000-8000-000000000000 | 404    | NOT_FOUND         |
    | {checkout in upper case}             | 400    | MALFORMED_REQUEST |

@US-8 @AC-8.4 @P0
Scenario: Nothing else in auth-service changes
  Then every existing auth-service test (backend-swe's and qa's) passes unmodified, with INTERNAL_SERVICE_KEY both unset and set
  And "GET /internal/projects/{checkout}/access" with "X-Service-Key" and no JWT is still 401

@US-8 @AC-8.5 @P1
Scenario: The key is checked and never shown
  Then auth-service start-up with INTERNAL_SERVICE_KEY "short" fails, naming INTERNAL_SERVICE_KEY without the value
  And no auth-service log line, at any level, contains the key or the X-Service-Key header
```

## Changes to existing code (approved by the user on 2026-10-26)
Prefixes: RE (report-service), AE (auth-service), GW (gateway).

| ID | Change | Files | Why | Risk | Depends | Task |
|---|---|---|---|---|---|---|
| RE1 | `build.gradle`: delete the `com.google.protobuf` plugin line (5), `spring-boot-starter-grpc-client` (24) and `spring-boot-starter-grpc-client-test` (30) | `report-service/build.gradle` | D2 | Low | — | T2 |
| RE2 | `build.gradle`: add exactly scenario-service's lines for `starter-webmvc`, `starter-restclient`, `starter-flyway`, `starter-aspectj`, jjwt 0.12.5 (api, impl, jackson), runtimeOnly `postgresql` and `flyway-database-postgresql`; test lines `starter-webmvc-test`, `starter-restclient-test`, `spring-boot-testcontainers`, `testcontainers-postgresql`. Versions only for jjwt; verify against the Boot 4.1 BOM; no devtools. | same | D14, D15, D17 | Low | — | T2 |
| RE3 | Replace `application.properties` with `application.yaml` (name, port 8083, the D5, D6, D14, D15 and D17 settings) and a new `application-dev.yaml` (`.env` import, dev URL and username, `# DEV ONLY`, no secret) | `report-service/src/main/resources/*` | One format; no committed secret | Low | — | T2 |
| RE4 | `ReportServiceApplicationTests`: annotations only (the shared test meta-annotation) | `report-service/src/test/java/org/reportservice/ReportServiceApplicationTests.java` | Fails today: no datasource | Low | RE2 | T2 |
| RE5 | Repository-root `.env.example` (the user's file): name report-service in the header lines; add commented `REPORT_DB_PASSWORD=` and `INTERNAL_SERVICE_KEY=` (hint: `openssl rand -base64 32`), no values | `.env.example` | D15 | Low | — | T2 |
| AE1 | `SecurityConfig.java`: add one `@Bean @Order(1)` `SecurityFilterChain` with `securityMatcher("/internal/service/**")`: stateless, CSRF off, the service-key filter, `anyRequest().authenticated()`, the existing entry point. The existing chain's body is unchanged. | `auth-service/.../configuration/SecurityConfig.java` | D5 | **Medium** | — | T4 |
| AE2 | `application.yaml`: `app.internal-service-key: ${INTERNAL_SERVICE_KEY:}` (empty disables C7) | `auth-service/src/main/resources/application.yaml` | D5 | Low | — | T4 |
| AE3 | New auth-service files: a properties record and check (unset = disabled; when set: Base64 of at least 32 bytes, no `${`), a service-key filter (constant-time comparison), `InternalProjectStatusController`, a service method, a response record, and new tests (not under `qa/`). If records need registering, name the one existing registration line. | new files under `auth-service/src/{main,test}/java/com/example/authservice/**` | D5 | Medium | AE1, AE2 | T4 |
| AE4 | auth-service contract 1.4.0: C7 and a `serviceKey` security scheme (additive) | `docs/api/auth-service.openapi.yaml` | Contract first | Low | — | T1 |
| AE5 | auth-service README "Settings": `INTERNAL_SERVICE_KEY` | `auth-service/README.md` | Operators | Low | — | T4 |
| GW1 | Route `reports`: `Path=/api/reports/**,/api/projects/*/runs,/api/projects/*/runs/**,/api/projects/*/upload-tokens,/api/projects/*/upload-tokens/**,/api/ci/runs`, and update its comment | `api-gateway/src/main/resources/application.yaml` (45–49) | D3 | Low–medium | Batch B | T11 |
| GW2 | Same file: public operation `POST /api/ci/runs` | same (91–99) | D3, D5 | **Medium** (new public path) | GW1 | T11 |
| GW3 | `ScenarioServiceRoutesTest:64`: `"/runs"` becomes `"/runsx"` (backend-swe's test; GW1 makes the old row false) | `api-gateway/src/test/java/org/apigateway/ScenarioServiceRoutesTest.java` | Keeps the test's intent | Low | GW1 | T11 |
| GW4 | New `ReportServiceRoutesTest`. The five patterns reach the stub unchanged. `POST /api/ci/runs` with `Bearer rct_…` and no JWT reaches the stub with the header unchanged. `GET /api/ci/runs` and `POST /api/ci/runs/x` without a token get 401. `/api/projects/{id}/runsx` gets 404. | new file in `api-gateway/src/test/java/org/apigateway/` | Proves GW1, GW2 | Low | GW1, GW2 | T11 |
| GW5 | Gateway contract 0.2.4: route table, `x-routes` `reports`, public operations | `docs/api/api-gateway.openapi.yaml` | Docs | Low | — | T11 |

**Will not be touched:**
- **report-service:** `ReportServiceApplication.java`, `settings.gradle`, the wrapper, `gradlew*`, `.gitignore`, `.gitattributes`, `bin/`, and every build line not named in RE1 or RE2.
- **scenario-service:** all of it, and its contract.
- **auth-service:** everything outside AE1–AE3 and AE5, including C5, `JwtAuthenticationFilter`, the entities, the migrations and every existing test.
- **api-gateway:** everything outside GW1–GW4.
- **Repository root:** `.env` (never opened) and `.gitignore`.

## Engineering guardrails
Standards §2–§7 apply in full. In addition:
- **Packages** under `org.reportservice`:
  - `controller`, `service` and `service/impl`, `repository` (with the advisory lock), `model`, `enums`, `dto/request`, `dto/response`, `exception`;
  - `security`: JWT, upload-token authentication, the access checker and the C5 client;
  - `client`: the C7 and scenario-service clients;
  - `ingest`: the Cucumber JSON reader. Pure: no Spring beans except itself, no JPA, no HTTP;
  - `configuration`, `aop`; `support` (tests only).
- **One owner per concern:**
  - only `ingest` reads Cucumber JSON;
  - only the access checker calls C5, and only `client` calls C7 and scenario-service;
  - only `ProjectRole.isAtLeast` compares roles;
  - only the token authenticator hashes and looks up tokens.
- **Copy, don't import:** JWT validation, Problems, strict input, the settings check, the logging aspect and the advisory lock come from scenario-service's code.
- **No library beyond RE2.** Cucumber JSON is read with Boot's Jackson; no Cucumber library.

## Tasks for backend-swe
Every task: test first; "Done when" = its ACs are green, plus `clean build` green with counts and the context count, plus a §8 handoff.
Common stop conditions: you'd need a file outside Allowed files; any existing test fails; a dependency or framework fact differs from this spec.

### Batch A: foundation, security, cross-service (review point A)
**T1. Contracts (docs).**
- **Allowed files:** new `docs/api/report-service.openapi.yaml` 1.0.0; AE4.
- **ACs:** all, as shapes: every path, status, code and member (`featureIds`, `errors[].pointer`), the D9 subset, and both bearer schemes.
- **Done when:** pm has reviewed the contract, before T5 starts.
- **Stop if:** an AC needs a shape change.

**T2. Build, settings, schema, entities, test support.**
- **Allowed files:** RE1–RE5; new `V1__create_report_service_schema.sql`, `model/*`, `enums/*`, `repository/*`, `configuration/*` (settings records and checks, `Clock`, the datasource dependency), `support/*`, schema and settings tests, new `report-service/README.md`.
- **Must not:** create `attachments`; add DB defaults; activate `dev`.
- **ACs:** AC-7.1, AC-7.3.
- **Test first:** the schema test.
- **Stop if:** jsonb, Flyway or any RE2 artifact differs from scenario-service's verified setup; or generated protobuf sources are still referenced.

**T3. Security, errors, input, logging, C5.**
- **Allowed files:** new `security/*`, `exception/*`, `aop/*`; configuration (security chain with two authentication paths, strict JSON, binder advice, MDC filters, `RestClient` beans); tests.
- **Must not:** cache or retry C5; call it inside a transaction.
- **ACs:** AC-2.5, AC-6.3 to AC-6.5, AC-7.2 (partly).
- **Test first:** the C5 failure matrix.
- **Stop if:** separating `/api/ci/**` from the JWT paths changes the Problem entry points.

**T4. auth-service C7.**
- **Allowed files:** exactly AE1–AE3 and AE5.
- **Must not:** touch C5, `JwtAuthenticationFilter`, entities, migrations or existing tests.
- **ACs:** AC-8.1 to AC-8.5.
- **Test first:** AC-8.2.
- **Done when:** the full auth-service build is green twice, once with the key unset and once with it set.
- **Stop if:** the double-registered `JwtAuthenticationFilter` (RC-10) changes answers on `/internal/service/**`; an existing test needs a change; or any existing path's 401/403 order changes.

**Review point A:** contracts, schema against the ERD, the security matrix, and auth-service's unchanged suite.

### Batch B: tokens and the CI upload (review point B)
**T5. Upload tokens.**
- **Allowed files:** new token controller, service, records and tests; `ErrorCode` (constants only) and the advice (additive only).
- **Must not:** return the token anywhere except create; store anything but the hash.
- **ACs:** AC-1.1 to AC-1.10.
- **Test first:** AC-1.1, AC-1.6.

**T6. Cucumber JSON reader (`ingest`).**
- **Allowed files:** new `ingest/*` and its tests.
- **Must not:** use Spring, JPA or HTTP types in `ingest`; reject unknown fields.
- **ACs:** AC-2.3, AC-2.8, AC-2.12, AC-2.13, and the mapping part of AC-2.2.
- **Test first:** the rollup outline.
- **Stop if:** a real Cucumber-JVM 7 or Cucumber-JS sample (one of each, source named) falls outside D9's subset.

**T7. Upload endpoint and C7 client.**
- **Allowed files:** new upload controller and service, `client/ProjectStatusClient`, tests; T3's security chain file (the upload path only); `ErrorCode` and advice (additive).
- **Must not:** buffer past the limit; open a transaction before parsing and C7.
- **ACs:** AC-2.1, AC-2.2, AC-2.4, AC-2.6, AC-2.7, AC-2.9 to AC-2.11, AC-2.14.
- **Test first:** AC-2.4, AC-2.7.
- **Stop if:** a 20 MiB upload takes more than 5 s at p95 locally (the gateway's timeout is 10 s); or bounded reading needs a library.

**Review point B:** token secrecy, the upload matrix, and the concurrency classes run 3 times.

### Batch C: manual runs and reading (review point C)
**T8. scenario-service client and planning.**
- **Allowed files:** new `client/ScenarioServiceClient`, the run controller (plan handler), the planning service, records and tests; `ErrorCode` and advice (additive).
- **Must not:** call scenario-service through the gateway or inside a transaction; change scenario-service.
- **ACs:** AC-3.1 to AC-3.8.
- **Test first:** AC-3.7.
- **Stop if:** contract 1.0.4 lacks a field D6 needs.

**T9. Recording and lifecycle.**
- **Allowed files:** the run controller (record, complete and cancel handlers), new services, records and tests; `ErrorCode` and advice (additive).
- **ACs:** AC-4.1 to AC-4.8, AC-6.1, AC-6.2.
- **Test first:** AC-4.4, AC-6.1.
- **Stop if:** run status needs a lock other than the project lock.

**T10. Reads and summary.**
- **Allowed files:** read handlers, the query service, repository queries, records and tests.
- **ACs:** AC-5.1 to AC-5.5.
- **Test first:** AC-5.2.
- **Stop if:** the summary needs more than one aggregate query per page.

**Review point C.**

### Batch D: gateway, docs, end to end (review point D)
**T11. Gateway.**
- **Allowed files:** exactly GW1–GW5.
- **ACs:** GW4's assertions, and AC-6.6's routing.
- **Stop if:** any gateway test other than GW3's line fails; or gateway FU-4 isn't in the code (it lands with the hardening batch, user decision Q8).

**T12. README, contract sync, end to end.**
- **Allowed files:** `report-service/README.md` and the three contracts (sync only).
- **ACs:** AC-6.6, as a manual run per standards §6.
- **Done when:** the upload and planning timings are reported.

**Review point D**, then qa.

## Contracts
1. **C1:** new `report-service.openapi.yaml` 1.0.0, binding for the ACs.
2. **C2:** JWT, as scenario-service's C2.
3. **C3:** errors per standards §3, plus D13.
4. **C4:** C5 (auth-service 1.3.0), with scenario-service D4's consumer rules.
5. **C5:** gateway headers (gateway C3).
6. **C6:** scenario-service 1.0.4, unchanged: the feature list and test cases (D6).
7. **C7:** new, auth-service 1.4.0: `GET /internal/service/projects/{projectId}` with `X-Service-Key`, answering 200 `{projectId, orgId, archived}`, 400, 401 or 404.
8. **C8:** the Cucumber JSON subset (D9), documented in C1.

## Cross-service dependencies
| Provider | Provides | Consumer | Blocks? |
|---|---|---|---|
| auth-service | C5 (built); C7 (T4) | report-service | T4 before T7's end-to-end run; tests use stubs |
| scenario-service | Public reads, 1.0.4 | planning | No |
| gateway | GW1–GW5; FU-4 (in the hardening batch, Q8) | clients, CI | T11 only |
| hardening batch | Runs first (the user's order) | this spec | Batch A starts after it; T11 rebases on its gateway changes |

## Non-functional requirements
- **Security:** deny by default. Only `POST /api/ci/runs` is public at the gateway. Tokens are 256-bit and hashed. The service key is compared in constant time. No secrets or content in logs.
- **Integrity:** one transaction per upload or plan, so there are no partial runs. Snapshots are immutable, except a manual result's status, comment, `executedBy` and `finishedAt`.
- **Performance (informational):** run list ≤ 200 ms p95 with 500 runs; planning 20 features ≤ 2 s; a 20 MiB upload ≤ 5 s (T7 stop condition).
- **Compatibility:** no existing path or response changes; auth-service and the gateway only gain additions.

## Assumptions
- A1. Projects are never hard-deleted.
- A2. Cucumber-JVM 7 and Cucumber-JS durations are nanoseconds. T6 verifies this against samples.
- A3. Validation (400) runs before the access check.
- A4. At most 5,000 results per run, so no streaming is needed.

## Follow-ups and refactoring candidates
None yet.

## Acceptance
Pending.
