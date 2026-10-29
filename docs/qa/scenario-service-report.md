# QA report: scenario-service phase 1

Written by the main session from qa's final report. The harness blocked qa from writing this file itself; the content is qa's and only the formatting is adjusted.

## Verdict

**Signed off with known issues.** Tested on commit `7be734a` plus the uncommitted phase 1 working tree, 2026-10-18/19.

The sign-off rule is met: every P0 AC passes, no S1 or S2 bug is open, and every AC has a result. No AC row fails, so no P1 failure needs pm's acceptance. pm should still decide whether BUG-1 and BUG-2 (both S3) must be fixed before production.

## AC verification matrix

| Result | Count |
|---|---|
| Pass | 91 (P0 21/21, P1 47/47, P2 23/23) |
| Fail | 0 |
| Blocked | 0 |
| Not tested | 0 |

The tests behind each AC are in the "Tests" column of `docs/qa/scenario-service-test-plan.md`. Every automated test carries `@Tag("AC-x.y")`. AC-7.11 is manual and passed in 2 runs.

Some ACs pass on every listed row but have a related bug for inputs outside those rows:

| AC | Related bug |
|---|---|
| AC-1.4 | BUG-1 |
| AC-3.2, AC-3.4 | BUG-3 |
| AC-7.3 | BUG-4 |
| AC-7.9 | BUG-5 |

## Suites run

- **scenario-service, `./gradlew clean build`:** 784 tests, 0 failed, 1 skipped (the accepted on-demand measurement). That is 483 backend tests plus 301 QA tests. The QA classes reuse the shared test context.
- **Concurrency classes, `./gradlew test --rerun`, 3 times:** ConcurrentCreateTest, ConcurrentSaveTest, QaConcurrencyTest and ProjectLockTest. Green every time (5 + 5 + 25 + 2 tests), no flakes.
- **api-gateway, `./gradlew clean build`:** 425 tests, 0 failed, 4 skipped (the D10 baseline). ScenarioServiceRoutesTest: 14 tests, 0 failed.
- **auth-service, `./gradlew clean build`:** 2,023 tests, 0 failed. No regression.
- **ERD, `npx @dbml/cli` 10.3.1 `dbml2sql --postgres`:** generated without error. The generated SQL was applied to PostgreSQL 17 and diffed against Flyway V1.
  - Columns, nullability, lengths, (absent) defaults and delete rules all match.
  - The only differences are documented or generator artefacts: DBML enums versus `varchar(32)` plus CHECK (ERD-D12), `DEFERRABLE` on foreign keys added by dbml2sql, the partial index's `WHERE` clause (DBML can't express it), and column order in the expression indexes.
  - V1 has 4 CHECKs, Flyway history shows V1 succeeded, and both databases use the builtin `C.UTF-8` locale.

## Bugs

| ID | Severity | Title | Owner |
|---|---|---|---|
| BUG-scenario-service-1 | S3 | An `# encoding:` pragma with an unknown or illegal charset returns 500 instead of 422 | backend-swe |
| BUG-scenario-service-2 | S3 | Saving a maximum-size feature takes about 1 minute and holds the project write lock | backend-swe; limits are a pm question |
| BUG-scenario-service-3 | S4 | D9 match keys are concatenated strings and can collide | backend-swe |
| BUG-scenario-service-4 | S4 | The JDK HTTP client retries the access check (C5) once | backend-swe |
| BUG-scenario-service-5 | S4 | URLs rejected before Spring MVC get no Problem body | backend-swe |

### BUG-scenario-service-1: an `# encoding:` pragma with an unknown or illegal charset returns 500

- Severity: S3 Major
- Affected: `POST /api/projects/{projectId}/features` and `PUT .../features/{featureId}`
- Linked AC: D8 / AC-1.4 (invalid text must be 422 `GHERKIN_INVALID`); this input class is not listed in the ACs
- Commit tested: `7be734a` plus the working tree
- Steps to reproduce:
  1. As Gus, `POST /api/projects/{checkout}/features` with `{"path":"features/e.feature","source":"# encoding: foo\nFeature: Café\n"}`.
  2. Repeat with `# encoding: -`.
- Expected: 422 `GHERKIN_INVALID` with a `parseErrors` entry, or 201 if pm decides the text is acceptable.
- Actual: 500 `INTERNAL_ERROR`, plus one ERROR log line with a stack trace.
- Root cause: gherkin's `EncodingParser.parseEncodingPragma` calls `Charset.forName`, which throws `UnsupportedCharsetException` or `IllegalCharsetNameException`. `GherkinAdapter.parse` doesn't catch it.
- Related, for pm: a valid pragma such as `ISO-8859-1` re-decodes the UTF-8 text, so the derived name becomes `CafÃ©` while the stored text says `Café`. Cucumber does the same with the same bytes.
- Evidence: QA probe in an integration context; status 500 for both pragmas.
- Suggested owner: backend-swe (catch it and map it to a parse error at 1:1), plus a pm decision on pragmas.

### BUG-scenario-service-2: a maximum-size feature takes about a minute to save and holds the project lock

- Severity: S3 Major
- Affected: create and save
- Linked AC: unspecified. Relevant spec text: NFR "save of a 1,000-line feature within 1 s", D14 lock, gateway timeout 10 s.
- Commit tested: `7be734a` plus the working tree
- Steps to reproduce:
  1. Create a valid feature of 999,970 characters: `Feature: W` followed by 37,447 copies of `  Scenario: s<i>\n    * a\n`.
  2. About 8 s later, send another create in the same project.
- Expected: no stated limit. Other writers in the project should not fail, and a client should not see a failure for a write that commits.
- Actual:
  - Direct create takes 57.9 s and save takes 56.8 s. A view returns 12.6 MB in 1.2 s.
  - The concurrent write gets 409 `CONFLICT` after 5.0 s (`lock_timeout`).
  - Through the gateway the client gets 504 after 10 s, but the create still commits; a retry then gets `FEATURE_PATH_TAKEN`.
  - The broken pipe is logged at ERROR with a stack trace (`HttpMessageNotWritableException`).
- Cause: one INSERT per row, with no JDBC batching.
- Evidence: QA probe in an integration context, and manual run 2.
- Suggested owner: backend-swe (bulk or batched inserts). pm to decide on a size or element-count limit.

### BUG-scenario-service-3: D9 match keys are concatenated strings and can collide

- Severity: S4 Minor
- Affected: `service/impl/FeatureStructure`, on save
- Linked AC: AC-3.2 and AC-3.4 (D9)
- Commit tested: `7be734a` plus the working tree
- Steps to reproduce:
  1. Create a feature with `Rule: a` containing `Scenario: b|1|SCENARIO|c`, and `Rule: a|1|SCENARIO|b` containing `Scenario: c`. Save it with any step changed.
  2. Create an outline whose Examples header is `| a, b | c |`. Save it with the header changed to `| a | b, c |` and the same row cells.
- Expected:
  1. The save returns 200.
  2. The row gets a new id, because the header changed.
- Actual:
  1. Every save of that feature returns 409 `CONFLICT`, because both scenarios get the same id.
  2. The row id is kept, because the header is compared through `List.toString()`.
- Suggested owner: backend-swe (structured keys, for example records, instead of string concatenation).

### BUG-scenario-service-4: the access check is retried once by the JDK HTTP client

- Severity: S4 Minor
- Affected: `AccessCheckClient` (the `JdkClientHttpRequestFactory` setup)
- Linked AC: D4 "No retries"; AC-7.3 still passes
- Commit tested: `7be734a` plus the working tree
- Steps to reproduce:
  1. Let auth-service accept the connection and close it without answering (QA raw server on the stub's port).
- Expected: one C5 request, then 503.
- Actual: 503 within about 2 s, but 2 connections reach auth-service. A connect to an unreachable address took 2.09 s in one run, which is consistent with a second attempt.
- Evidence: `QaAccessCheckFailureMatrixTest`, row "closes the connection at once". The connection-count assertion is skipped for that row only, with a comment pointing to this bug.
- Suggested owner: backend-swe. QA restores the connection-count assertion once it's fixed.

### BUG-scenario-service-5: requests rejected before Spring MVC get no Problem body

- Severity: S4 Minor
- Affected: direct port 8082
- Linked AC: D16 / AC-7.9 (one error format)
- Commit tested: `7be734a` plus the working tree
- Steps to reproduce: `GET` with any of these in the URL:
  - `%00`, `%2F`, or a raw `{` or `|`: rejected by Tomcat;
  - `;x=1`, `//`, or `%2E%2E`: rejected by Spring Security's firewall.
- Expected: a 400 Problem.
- Actual: 400 in every case, but with Tomcat's HTML page or Boot's `/error` JSON (`timestamp`, `status`, `error`, `path`). No Tomcat version and no secret is shown.
- Gateway: the same URLs sent through the gateway get the gateway's own non-Problem 400, an already known gateway issue.
- Suggested owner: backend-swe (an `ErrorController` that renders Problems). The Tomcat-level cases may simply be accepted.

## Pre-existing failures

None. All builds are green, with no regression in auth-service or api-gateway.

## Manual end-to-end run (G10a)

**Setup:**
- Throwaway `postgres:17` container `qa-scn-e2e-pg` on `127.0.0.1:55432`, builtin `C.UTF-8`.
- The three boot jars without a profile: gateway on 8080, auth-service on 8081, scenario-service on 8082.
- A second scenario-service on 8092, pointed at the unreachable address `10.255.255.1`.
- A generated 64-byte JWT secret and generated database passwords, held only in the run script's memory and the processes' environments. Nothing was written to a file or printed.
- Processes stopped by their recorded PIDs; the container was removed with its volume.

**Results:**
- **AC-7.11:**
  - Gus creates Payment through the gateway: 201, and `Location` equals `/api/projects/{p}/features/{id}`.
  - Gus reads 4 test cases.
  - `/api/projects/1` is the gateway's 404 Problem without `code`.
  - After Ana removes Gus from Checkout, his next request is 403 `FORBIDDEN`.
  - After auth-service stops, the next request is 503 `ACCESS_CHECK_UNAVAILABLE` (in 0.01–0.03 s); a write is also 503.
- **Spec success scenario:**
  - Version 2 keeps the 4 old test-case ids and adds a fifth.
  - A stale save is 409 `VERSION_CONFLICT` with `currentVersion` 2.
  - Broken text is 422 at 5:7.
  - The READER can list but gets 403 on create.
- **Gateway behaviour:**
  - The tag filter works; a repeated tag and a tag with a space are 400.
  - The catalogue is correct.
  - `/api/scenarios/42` reaches scenario-service's 404 `NOT_FOUND`.
  - `requestId` is echoed, and the request id appears in both the gateway and scenario-service logs.
- **Direct spellings (AC-7.12/7.14 over real Tomcat):** 7 of 7 are 400 `MALFORMED_REQUEST` Problems.
- **Lower-case `bearer` with the real auth-service:** 200.
- **Latency, 50 features of about 100 lines, through the gateway with the real C5:** list p95 71–72 ms, view p95 46–52 ms (target 200 ms). This supports the accepted parse-on-read design.
- **Unreachable auth-service:** 503 in 1.07 s and 2.09 s across the two runs.
- **Log scans (4 logs):** no JWT secret, database password, token or user password in any of them. scenario-service's log has no feature content and no email.

## QA tests added

All 8 classes are in `scenario-service/src/test/java/org/scenarioservice/qa/`, 301 tests in total:

| Class | Tests |
|---|---|
| QaAccessCheckFailureMatrixTest | 31 |
| QaAuthorizationIdorTest | 25 |
| QaConcurrencyTest | 25 |
| QaGherkinEdgeCasesTest | 37 |
| QaJwtAndLeakageTest | 24 |
| QaPathAndTagRulesTest | 71 |
| QaStrictInputTest | 70 |
| QaVersioningAndIdsTest | 18 |

What they cover is listed in the test plan, rows QA-01 to QA-24.

## Test audit

Mutation check: 24 mutants on a `/tmp` copy, deleted afterwards.
- **Backend tests alone:** 17 killed, 7 survived.
- **Survivors:**
  - M08: list ordered case-sensitively. AC-2.2's data (`Address` vs `checkout`) can't tell the difference.
  - M09: `VERSION_CONFLICT` checked before `FEATURE_ARCHIVED`.
  - M11: an old example row can be matched twice.
  - M12: scenario kind ignored when matching ids.
  - M13: Rule name ignored when matching ids.
  - M20: column 0 not raised to 1.
  - M21: example rows pooled regardless of header.
- **With the QA tests added:** 6 of the 7 are killed. M20 is equivalent, because the parser already turns column 0 into "absent", which the adapter reports as 1.

Other weaknesses in the backend tests:
- AC-1.11's last line ("test cases are []") isn't asserted. QA added it.
- AC-1.2 doesn't check the steps of "Pay by card" or the Background's step count.
- AC-1.7's 1,000,001 code points are all ASCII, so they can't catch code-point counting errors. AC-1.17 row 4 covers that for paths, and QA covers it for sources.
- JsonPath's json-smart parser silently drops U+007F, so a source round-trip check through JsonPath could hide corruption. QA uses Jackson for those checks.
- No test asserts the number of C5 calls (no retry) when auth-service fails.

## Assessment of the accepted deviations

All of them hold up:
- **Batch A deviations 3–7:** fine. The ERD is as built (the dbml2sql diff shows only the documented differences). The probes are under `src/test` only, and `src/main` never refers to them. The test token appears only in MockMvc's failure print, never in application logs.
- **`ApiExceptionHandler` additions:** additive only, and they produce correct Problems.
- **Parse-on-read views:** fast at 50 features (see the latency results), but slow for huge features (BUG-2).
- **D9 row pooling:** verified by QA-17.
- **`keyword_text` widened:** the en-tx dialect is stored correctly.
- **`SCENARIO_OUTLINE` for a Scenario with Examples:** verified.
- **Raw `MultiValueMap` tag binding:** a repeated tag is rejected, even through the gateway.
- **Duplicate id mapping in the test-case service:** consistent in every QA test, but it is a drift risk against `FeatureViews`.
- **md5 unique indexes:** a 1,024-code-point path of 4-byte characters is stored, and a lengthening `İ` path still collides.
- **Skipped measurement test:** replaced by qa's manual measurement.
- **AC-8.12 row 4 placeholder:** as accepted.

## Gaps and risks

- **Risks, not bugs:**
  - With Spring Security at DEBUG, request URLs and query strings (tag values) are logged.
  - Unicode normalization and full case folding aren't applied to paths.
  - Tags can contain Unicode whitespace, so the catalogue lists tags that the filter refuses.
- **Not tested:** auth-service's real C5 under load.

## Open questions for pm

1. Accept BUG-1 and BUG-2 (both S3) for phase 1, or require fixes first?
2. Set a size or element-count limit for features (BUG-2)?
3. Reject or accept `# encoding:` pragmas other than UTF-8?
4. Tags containing Unicode whitespace are listed but can't be filtered. Accept, or change D19 #5?
5. Should path uniqueness also cover Unicode normalization and full case folding?
6. Change the contract wording "Authorization forwarded unchanged" (the scheme is normalized to `Bearer`)?
7. Add a README note on framework DEBUG logs?

pm can close FU-16 (X3) on acceptance. The QA tests are read-only for backend-swe (fix round rule F).

## Re-verification (fix round 1), 2026-10-19

### Verdict
**Signed off with known issues.**

Commit: HEAD `7be734a` plus the fix-round working tree; contract 1.0.4.
- **ACs:** 94 of 94 pass: 21 P0, 49 P1 and 24 P2. That includes the new AC-1.19, AC-1.20 and AC-3.15.
- **BUG-1, BUG-2 and BUG-3:** fixed and verified with the original reproductions.
- **New finding:** one S3, BUG-scenario-service-6. A 10,000-element feature made mostly of distinct tags still saves slowly under the project lock.
- **AC-1.20 interpretation:** AC-1.20 passes with the data its tester note prescribes (5,000 scenarios with one step each). If pm reads its "a valid source with 10,000 elements" as covering *any* such source, BUG-6 makes AC-1.20 a P1 failure, which needs pm's explicit acceptance.
- **Open bugs:** no S1 or S2. Open: BUG-6 (S3), and BUG-4 and BUG-5 (S4), which are already follow-ups SFU-1 and SFU-2.

### Bug status
| Bug | Severity | Status | Evidence |
|---|---|---|---|
| BUG-scenario-service-1 | S3 | **Fixed, verified** | The `# encoding:` values `foo`, `-`, `ISO-8859-1`, `latin1`, `UTF-16`, `ascii`, `windows-1252`, a pragma after `# language:`, after blank lines and after a BOM: each is 422 with exactly `[{1, 1, "Only UTF-8 encoding is supported"}]` and nothing stored. `UTF-8`, `utf-8`, `UTF8`, `utf8` and a pragma after the Feature line: 201 with name `Café`. Through the gateway: 422 at 1:1. |
| BUG-scenario-service-2 | S3 | **Fixed for the scenario-and-step shape and the original reproduction; see BUG-6** | The 999,970-character text is 400 in 0.16–0.27 s. A 10,000-element create takes 0.70 s and a save 0.61 s through the gateway (1.03 s and 0.39 s in tests), and a create sent 1 s later is 201. Over-limit create and save are 400 `source`, and the version is unchanged. Example-row and Rule shapes at 10,000 elements: create about 1.0 s, save 0.4–0.6 s, a concurrent create gets 201. |
| BUG-scenario-service-3 | S4 | **Fixed, verified** | Both AC-3.15 rows: the save is 200 and the two ids stay distinct; a moved comma in the header gives a new row id. A feature-level scenario named like a Rule and an unnamed Rule keep 3 distinct, stable ids. |
| BUG-scenario-service-4 | S4 | Open, follow-up SFU-1 | Unchanged. The QA connection-count assertion stays skipped for that one row. |
| BUG-scenario-service-5 | S4 | Open, follow-up SFU-2 | Unchanged. The QA test asserts only 400 and no C5 call. |
| BUG-scenario-service-6 | S3 | **New** | See below. |

### BUG-scenario-service-6: a 10,000-element feature made of distinct tags holds the project lock for about 20 s
- Severity: S3 Major
- Affected: scenario-service, `POST` and `PUT /api/projects/{projectId}/features…`, in `service/impl/FeatureStructure.Writer.catalogueTag`
- Linked AC: D21 #2 ("a 10,000-element save must finish within 3 s … can't make another writer reach `lock_timeout`"); AC-1.20 (see the interpretation note in the verdict)
- Commit tested: `7be734a` plus the fix-round working tree
- Steps to reproduce:
  1. As a MEMBER, `POST {checkout-features}` with path `features/tags.feature` and the source: `@t0 @t1 … @t9999` on one line, then `Feature: Tags` (exactly 10,000 elements, all tag assignments).
  2. 1 s later, `POST {checkout-features}` with another path and `Feature: O`.
- Expected: 201 within 3 s, and the second create is 201.
- Actual:
  - **Through the gateway (production `lock_timeout` 5 s):** the client gets 504 after 10.0 s; the create logs "Slow call … returned in 21369 ms" and still commits. The second create is 409 `CONFLICT` after 5.0 s. The broken pipe is logged at ERROR, which is SFU-5.
  - **In the test context:** create 24.9 s and save 18.0 s.
  - **A more realistic shape**, 3,333 scenarios each with its own tag (9,999 elements): create 4.2 s, save 2.5 s. A create 1 s later got 409 with the test `lock_timeout` of 1 s.
- Cause: under the lock, each new tag name runs its own `TagRepository.findByProjectIdAndName` native query, and that query flushes the pending tag insert. Tag rows are therefore neither batched nor loaded in one query. Saves of existing tags repeat the lookups.
- Evidence: a QA scratch probe (since deleted) and the manual G10a run of 2026-10-19.
- Suggested owner: backend-swe (load the project's matching tags in one query before or at the start of the write, then batch the new ones). pm to decide whether AC-1.20 covers this shape.

### Fix-round checks
- **D16 order around step 7a (QA, `QaFeatureSizeTest`):** a READER gets 403; an archived project gets 409 `PROJECT_ARCHIVED`; an unknown feature gets 404; an archived feature gets `FEATURE_ARCHIVED`; a stale base gets `VERSION_CONFLICT`; a parse error gets 422 (step 7 before 7a); size beats a taken path on create and on save (7a before 8); an invalid `path` field beats size (step 2). All as specified.
- **Element count (QA):** 10,000 elements are accepted and 10,001 rejected for example rows, Rules, tag assignments, Examples blocks and Backgrounds in Rules. Data-table rows (20,000) and doc-string lines (20,000) don't count, and a duplicated tag counts once. The message names "10,000" and quotes no text.
- **Mutation check of the fixes**, 9 mutants on a `/tmp` copy (deleted), all killed:
  - F1a and F1b (pragma check removed; the original BUG-1): EncodingPragmaTest, GherkinEncodingAndSizeTest, QaGherkinEdgeCasesTest.
  - F2a (limit removed) and F2b (off by one): FeatureSizeTest, QaFeatureSizeTest.
  - F2c (size checked before the archived and version checks): **only QA's** QaFeatureSizeTest and QaVersioningAndIdsTest. backend-swe has no save-order test for step 7a.
  - F2d (batching removed): FeatureSizeTest, through its timing assertion.
  - F2e (tags not counted): FeatureSizeTest, GherkinEncodingAndSizeTest, QaFeatureSizeTest.
  - F3a (header compared with `toString`) and F3b (the original joined key): IdMatchingTest, QaVersioningAndIdsTest.
  - Spot check with the QA tests removed: backend-swe's tests alone now kill M08 (ListAndViewFeaturesTest) and M09 (ArchiveRestoreTest).
- **QA tests strengthened:** 35 tests added.
  - A new class, `QaFeatureSizeTest` (17 tests).
  - AC-1.19 rows in `QaGherkinEdgeCasesTest` (+15).
  - AC-3.15 rows in `QaVersioningAndIdsTest` (+3).
  - Why: BUG-1 to BUG-3 were kept out of the build as reproductions while they were open. Now they're asserted as fixed.
  - No existing QA test accepted a buggy BUG-1, BUG-2 or BUG-3 outcome. The only QA tests that still tolerate a known bug are the BUG-4 and BUG-5 rows, which stay open as follow-ups.

### Suites run (2026-10-19)
- **scenario-service, `./gradlew clean build`:** 855 tests (519 backend-swe, 336 qa), 0 failed, 1 skipped (the accepted measurement). The QA classes add no Spring context.
- **Concurrency, `--rerun` 3 times:** ConcurrentCreateTest, ConcurrentSaveTest, QaConcurrencyTest, ProjectLockTest and FeatureSizeTest. Green every time.
- **api-gateway, `./gradlew clean build`:** 425 tests, 0 failed, 4 skipped.
- **auth-service, `./gradlew clean build`:** 2,023 tests, 0 failed.
- **Manual G10a re-check through the gateway:** 8/8 checks passed. Throwaway `postgres:17` container `qa-scn-e2e-pg` on `127.0.0.1:55432`; all three jars without a profile; generated secrets held in memory only. The secret scan of the three logs found no JWT secret and no password. Processes 81320–81322 were stopped by PID, and the container was removed with its volume.

## Re-verification (fix round 2), 2026-10-26

### Verdict
**Signed off with known issues.** Tested at commit `fc178b6` (T16 included), plus one QA test edit described below. The work ran on 2026-10-19 and was resumed on 2026-10-26.
- **ACs:** 94 of 94 pass: 21 P0, 49 P1, 24 P2. AC-1.20 now passes for every element shape, tags included, which is how pm reads it (T16).
- **BUG-6:** fixed and verified.
- **Open bugs:** no S1, S2 or S3. Open: BUG-4 and BUG-5 (S4), which are follow-ups SFU-1 and SFU-2.
- **Sign-off rule:** met.

### Bug status
| Bug | Severity | Status |
|---|---|---|
| BUG-scenario-service-1 (non-UTF-8 pragma gave 500) | S3 | Fixed, verified (fix round 1) |
| BUG-scenario-service-2 (maximum-size save took about 1 min under the lock) | S3 | Fixed, verified (fix rounds 1 and 2) |
| BUG-scenario-service-3 (joined match keys) | S4 | Fixed, verified (fix round 1) |
| BUG-scenario-service-4 (JDK client retries C5) | S4 | Open, follow-up SFU-1 |
| BUG-scenario-service-5 (non-Problem bodies before Spring MVC) | S4 | Open, follow-up SFU-2 |
| BUG-scenario-service-6 (10,000 distinct tags held the lock about 20 s) | S3 | **Fixed, verified** (T16) |

### BUG-6 re-verification
**Through the gateway, production settings** (fresh `postgres:17`, `lock_timeout` 5 s, no manual `ANALYZE`):

| Check | Fix round 1 | Now |
|---|---|---|
| Create, 10,000 distinct tags | 504 after 10 s; create ran 21.4 s | 201 in 0.86 s |
| Create sent 1 s later | 409 `CONFLICT` after 5 s | 201 in 0.15 s |
| Save, 5,000 kept and 5,000 new tags | – | 200 in 0.62 s |
| Create sent 1 s into that save | – | 201 in 0.13 s |
| Create, 3,333 scenarios with a tag each | 4.2 s | 201 in 0.38 s |

Also through the gateway:
- 10,001 tags is 400 on `source`.
- The catalogue lists 13,333 tags, each once.
- Filtering by a tag created in the batch works.
- The logs have no ERROR, no secret and no password.

**In the test suite** (3 full `clean build` runs): create 1.05–1.06 s, save 0.30 s, tagged scenarios 0.26–0.27 s.

**Mutation check** (put each change back on a `/tmp` copy, deleted afterwards): 4 of 4 killed.

| Mutant | Caught by |
|---|---|
| G1: per-tag lookup restored (the original BUG-6) | FeatureSizeTest, QaFeatureSizeTest |
| G2: catalogue not preloaded | 14 classes (duplicate tag rows) |
| G3: tags inserted after their assignments | 24 classes (foreign-key failure) |
| G4: names matched ignoring case | TagsTest, QaPathAndTagRulesTest |

G1 is still caught with the statistics refresh described below in place, so the refresh doesn't hide the real defect.

**Tag semantics are unchanged:**
- exact, case-sensitive names (`@Smoke`, `@smoke`, `@SMOKE`);
- U+200B kept and matched exactly;
- a 999-emoji tag stored under the md5 index;
- one catalogue row per distinct name across two features;
- code-point order in the catalogue;
- correct `featureCount`.

Covered by `QaPathAndTagRulesTest.catalogueReuseKeepsExactNames`, TagsTest and the filter tests.

### Investigation: the bimodal 0.3 s / 3.7 s timings (environmental)
In some full-suite runs on 2026-10-19, the 10,000-tag save in `QaFeatureSizeTest` took 3.6–5.1 s instead of 0.3 s, and the create took about 2 s, so a concurrent writer hit the 1 s test `lock_timeout`. It reproduced only when backend-swe's suite and `QaAccessCheckFailureMatrixTest` ran first. Evidence from temporary probes (since deleted):
1. **Not the JVM.** GC time during the slow save was 0–12 ms, the heap was under 260 of 512 MB, and the test thread used under 250 ms of CPU. Every round in the slow configuration was slow, so it isn't a cold JIT.
2. **Not the round-trip time, the statement count or the access check.** `SELECT 1` took about 0.4 ms in both configurations. Hibernate's statistics were identical: 14 prepared statements, 2 flushes, 6 queries, 15,001 batched inserts. `requireWrite` took 0–1 ms, and a raw 15,000-row JDBC batch insert took 85–109 ms in both.
3. **The database is busy.** Sampling `pg_stat_activity` every 50 ms showed the app's session `active`, with no wait event, inside `insert into tag_assignments …` for 73–83 samples (about 4 s). In fast runs the same session was mostly `idle in transaction`, meaning it was waiting on the client.
4. **Cause: stale planner statistics from the test resets.** In the slow configuration, `pg_class` recorded `reltuples=0, relpages=0` for `tags`, `features` and `tag_assignments` while `tags` held 10,000–15,000 rows; in fast runs it was `-1` (never analyzed). `FeatureData.reset()` deletes every row, autovacuum then records these tables as empty, and a pooled connection keeps the foreign-key check plans built for an empty table. A fresh generic plan uses `pk_tags` (index scan). Running `ANALYZE tags, features, tag_assignments`, which invalidates the cached plans, brought the same save from 4.6–5.0 s down to 0.33 s, with no code change.
5. **Classification: environmental, specific to the test suite. Not a code defect, so no bug is filed.**
   - The application never empties `tags` (catalogue rows are kept) or `features` (never hard-deleted), and autovacuum keeps their statistics current.
   - The production-like gateway run above, on a fresh database without a manual `ANALYZE`, is fast.
   - Residual risk, noted not filed: in a brand-new database, a burst of very large tagged saves in the first minute, before the first auto-analyze, could plan the same way briefly.
6. **Test change (QA-owned, `QaFeatureSizeTest`).** The three tag timing tests now call `ANALYZE tags, tag_assignments, features` before they start the clock, with a comment that cites this evidence. The 3 s bound and the "a create sent 1 s later is 201" assertion are unchanged, so the requirement isn't weakened. After the change, the full suite was green 3 runs in a row with stable timings.

### Suites run (2026-10-26)
- **scenario-service, `./gradlew clean build`, 3 runs:** 863 tests (523 backend-swe, 340 qa), 0 failed, 1 skipped (the accepted measurement), each time.
- **Concurrency, `--rerun` 3 times:** ConcurrentCreateTest, ConcurrentSaveTest, QaConcurrencyTest, ProjectLockTest, FeatureSizeTest and QaFeatureSizeTest. Green every time.
- **api-gateway and auth-service:** not re-run. `git status` shows no change outside scenario-service since `fc178b6`, and their last builds (2026-10-19) were green.
- **Manual G10a re-check through the gateway:** 8/8 passed. Container `qa-scn-e2e-pg` on `127.0.0.1:55432`; no `.env` and no `dev` profile; generated secrets held in memory only. Processes 62098–62100 were stopped by PID, and the container was removed with its volume.
