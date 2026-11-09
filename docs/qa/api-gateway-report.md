# QA report: api-gateway (phase 1)

- Spec: `docs/specs/api-gateway.md` (status Changes requested, last updated 2026-10-12, 50 ACs)
- Contract: `docs/api/api-gateway.openapi.yaml` (version 0.2.0)
- Test plan: `docs/qa/api-gateway-test-plan.md`
- Commit tested: `aa2234c` + the uncommitted working tree. The first round ran on 2026-10-12, 09:30–11:32. The
  re-verification after the fix round (T13–T19) ran 12:31–12:56 (+05:00).
- Current verdict: **Signed off with known issues** (see the re-verification below).

## Re-verification (fix round), 2026-10-12

### Verdict

**Signed off with known issues.** Tested on `aa2234c` + the working tree as of 2026-10-12 12:56 (+05:00): backend-swe's
T13–T19 (last main-code change 12:25) plus the qa test additions listed below. This verdict supersedes the first-round
verdict further down.

Reason:

- all 50 ACs pass, including all 12 P0 ACs and the three manual cross-service checks;
- there is no open S1 or S2 bug;
- `cd api-gateway && ./gradlew clean build` is green: 411 tests, 0 failed, 4 skipped. The 4 skipped tests are exactly
  D10's;
- BUG-1, 2, 4, 5, 7, 10, 11, 12 and 13 are closed, and BUG-3 is closed by decision.

Known issues that stay open (none blocks sign-off):

| Bug    | Sev | Status                                                                                            |
|--------|-----|---------------------------------------------------------------------------------------------------|
| BUG-6  | S4  | Deferred (FU-1)                                                                                   |
| BUG-8  | S4  | Deferred (FU-2)                                                                                   |
| BUG-9  | S4  | Deferred (FU-3)                                                                                   |
| BUG-14 | S3  | New. Pre-existing, in the area D6 defers to FU-4. pm should attach it to FU-4 or pull it forward. |
| BUG-15 | S4  | New. pm decides whether D7's wording requires it.                                                 |
| BUG-16 | S4  | New                                                                                               |

No P1 AC fails, so no pm waiver is needed.

### Tree state

- `git rev-parse --short HEAD`: `aa2234c`. No commits were made.
- **Concurrent git activity (not by qa).** At the start (12:31), `git status --short` matched the first round, except
  that `.claude/` was no longer listed: the tracked `.gitignore` had gained `.claude` and `.gitignore`. Between 13:01
  and 13:02, after all test runs, someone other than qa did two things:
    - edited `.gitignore` again (it now also lists `*.iml`);
    - staged the whole tree (`git status` now shows `A`, `M`, `R` and `D` in the index).

  The staged changes include deletions from the index of `.gitignore`, `Cucumber.iml` and the `.gitignore` files of
  api-gateway, auth-service, scenario-service and report-service; the files are still on disk.
    - `HEAD` is still `aa2234c`, with no commit.
    - No file under `api-gateway/src`, `api-gateway/build.gradle`, the README, `docs/api`, `docs/specs` or
      `auth-service/src` changed after the final build (checked with `find -newer` at 12:53), so the tested tree is the
      tree as staged.
    - **Before committing:** the staged copies of `docs/qa/api-gateway-report.md` and `docs/qa/api-gateway-test-plan.md`
      are older than the final files (`AM`), so re-stage both. Also confirm that removing the per-service `.gitignore`
      files from the repository is intentional: other clones would then lose the `build/` and `.gradle` ignore rules.
      These changes are outside the gateway's allowed files (G2), and qa didn't make them.
- `api-gateway/src/main/resources/application.yaml` no longer contains `spring.profiles.active`. A jar started without a
  profile logs `No active profile set, falling back to 1 default profile: "default"`.

### Acceptance-criteria verification matrix (all 50)

"BE" is backend-swe's test and "QA" is qa's test. Every listed test passed in the final run (R5). The P0 rows are
AC-1.1–1.6, AC-2.1–2.3, AC-2.8, AC-8.1 and AC-8.6.

| AC      | Priority | Test(s)                                                                                                                                                                                                                                                                                                                                                                                                                                                        | Result | Bug                                    |
|---------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|----------------------------------------|
| AC-1.1  | P0       | BE `RoutingTest#loginIsForwardedToAuthServiceAndItsResponseIsReturnedUnchanged`                                                                                                                                                                                                                                                                                                                                                                                | Pass   |                                        |
| AC-1.2  | P0       | BE `RoutingTest#eachPathPrefixIsForwardedToItsOwningService` (6 rows)                                                                                                                                                                                                                                                                                                                                                                                          | Pass   |                                        |
| AC-1.3  | P0       | BE `RoutingTest#methodPathQueryHeadersAndBodyAreForwardedWithoutRewriting` (JSON content type, as reworded); QA `QaRequestFidelityTest#customHeadersAndUnicodeBodiesAreForwarded`, `#formEncodedPostBodyKeepsItsContent`                                                                                                                                                                                                                                       | Pass   | FU-2 deviations (BUG-8)                |
| AC-1.4  | P0       | BE `RoutingTest#errorResponsesFromAServiceArePassedThroughUnchanged` (4); QA `QaRequestFidelityTest#responseHeadersArePassedThrough`, `QaDownstreamResponseFailureTest#aValidCloseDelimitedResponseIsPassedThrough`, `#theNextResponseAfterAFailureIsClean`                                                                                                                                                                                                    | Pass   |                                        |
| AC-1.5  | P0       | BE `RoutingTest#aPathThatMatchesNoRouteReturns404AndReachesNoService` (6), `GatewayErrorResponseTest#aPathThatMatchesNoRouteGetsA404Problem…` (6)                                                                                                                                                                                                                                                                                                              | Pass   | variant BUG-6 (deferred)               |
| AC-1.6  | P0       | Manual (README), 12:52. Register through 8080 gave 201 with `id`. Login gave 200 with a non-empty `accessToken`: 279 characters, header `{"alg":"HS512"}`, `sub` equal to the registered `id`, `exp - iat` = 86400.                                                                                                                                                                                                                                            | Pass   |                                        |
| AC-1.7  | P1       | BE `RoutingTest#formEncodedBodiesStayInTheBodyAndNeverMoveIntoTheUrl` (4 rows); QA `QaRequestFidelityTest#nonJsonBodiesStayInTheBodyUnchanged` (4: UTF-8 with charset and valueless fields, upper-case media type, a 227 KB form, multipart); former BUG-5 reproductions                                                                                                                                                                                       | Pass   | BUG-5 closed                           |
| AC-2.1  | P0       | BE `JwtAuthenticationTest#publicAuthOperationsAreForwardedWithoutAToken` (3)                                                                                                                                                                                                                                                                                                                                                                                   | Pass   |                                        |
| AC-2.2  | P0       | BE `JwtAuthenticationTest#aProtectedRequestWithAValidToken…`; QA `QaTokenForgeryTest#theValidTokenIsAccepted`, `QaCredentialAmbiguityTest#aSingleLowerCaseSchemeIsStillAccepted`                                                                                                                                                                                                                                                                               | Pass   |                                        |
| AC-2.3  | P0       | BE `JwtAuthenticationTest#aProtectedRequestWithoutValidCredentialsIsRejectedWith401` (8); QA `QaTokenForgeryTest` (17 forged, 8 malformed, query/cookie), `QaPublicOperationBypassTest#protectedPathsWithoutATokenAre401`, `QaUnknownPathsTest#anonymousUnknownPathsGet401`                                                                                                                                                                                    | Pass   |                                        |
| AC-2.4  | P1       | BE `JwtAuthenticationTest#aSignedUnexpiredTokenWithoutASubjectIsRejected`; QA `QaTokenForgeryTest#tokensWithoutANonEmptySubjectAreRejected` (3)                                                                                                                                                                                                                                                                                                                | Pass   |                                        |
| AC-2.5  | P1       | BE `JwtAuthenticationTest#publicOperationsIgnoreAnInvalidTokenAndForwardItUnchanged` (2); QA `QaCredentialAmbiguityTest#publicOperationsForwardEveryAuthorizationHeaderUnevaluated` (two headers on login and register, forwarded unchanged and in order)                                                                                                                                                                                                      | Pass   |                                        |
| AC-2.6  | P1       | BE `JwtAuthenticationTest#onlyTheListedAuthOperationsArePublic` (3); QA `QaPublicOperationBypassTest` (38 rows)                                                                                                                                                                                                                                                                                                                                                | Pass   |                                        |
| AC-2.7  | P2       | BE `JwtAuthenticationTest#a401FromTheGatewayTellsTheClientToUseABearerToken`; QA `QaContractConformanceTest#unauthorizedMatchesTheContract` and every QA 401 assertion                                                                                                                                                                                                                                                                                         | Pass   |                                        |
| AC-2.8  | P0       | Manual, 12:52. `POST /api/orgs` with the token auth-service issued gave **403** with an empty body, which is not 401. Access entry: `requestId=ac-2-8 method=POST path=/api/orgs status=403 durationMs=54 service=auth-service userId=<U>`, where U is the registered `id`. auth-service logged the Known issue 2 NPE (`OrganizationController.java:40`). Controls: no token gave 401, a tampered signature gave 401.                                          | Pass   |                                        |
| AC-2.9  | P1       | BE `JwtAuthenticationTest#aProtectedRequestWithMoreThanOneAuthorizationHeaderIsRejected` (3); QA `QaCredentialAmbiguityTest#moreThanOneAuthorizationHeaderLineIsRejected` (5 raw rows: different name case, an empty copy before and after, `Basic` + `Bearer`, three copies)                                                                                                                                                                                  | Pass   | BUG-11 closed                          |
| AC-2.10 | P2       | BE `JwtAuthenticationTest#aSignedTokenWhoseSubjectIsUnsafeForHeadersOrLogsIsRejected` (4); QA `QaCredentialAmbiguityTest#unsafeOrNonStringSubjectsAreRejected` (10: tab, DEL, NUL, bare CR, NBSP, number, array, object, boolean, null), `#printableAsciiSubjectsAreAccepted` (5 edge subjects accepted)                                                                                                                                                       | Pass   | BUG-12 closed                          |
| AC-3.1  | P1       | BE `IdentityHeaderFilterTest#theGatewayTellsTheServiceWhichUserMadeTheRequest`; QA `QaTokenForgeryTest#theIdentityHeaderFollowsTheTokenSubject`, `QaCredentialAmbiguityTest#printableAsciiSubjectsAreAccepted`                                                                                                                                                                                                                                                 | Pass   |                                        |
| AC-3.2  | P1       | BE `IdentityHeaderFilterTest#aForgedIdentityHeaderOnAProtectedRequestIsReplaced`; QA `QaHeaderAbuseTest` (2)                                                                                                                                                                                                                                                                                                                                                   | Pass   |                                        |
| AC-3.3  | P1       | BE `IdentityHeaderFilterTest#aForgedIdentityHeaderOnAPublicRequestIsRemoved` (2); QA `QaHeaderAbuseTest#spoofedCopiesOnAPublicRequestAreRemoved`                                                                                                                                                                                                                                                                                                               | Pass   |                                        |
| AC-4.1  | P1       | BE `DownstreamUnavailableTest#aServiceThatIsNotRunningProduces503Quickly`                                                                                                                                                                                                                                                                                                                                                                                      | Pass   |                                        |
| AC-4.2  | P1       | BE `DownstreamTimeoutTest#aServiceSlowerThanTheResponseTimeoutProduces504`                                                                                                                                                                                                                                                                                                                                                                                     | Pass   |                                        |
| AC-4.3  | P1       | BE `DownstreamTimeoutTest#theGatewayNeverRetriesAFailedRequest`; QA `QaDownstreamRobustnessTest#aDroppedPostIsNotRetried`                                                                                                                                                                                                                                                                                                                                      | Pass   |                                        |
| AC-4.4  | P1       | BE `DownstreamUnavailableTest#otherRoutesKeepWorkingWhileOneServiceIsDown`                                                                                                                                                                                                                                                                                                                                                                                     | Pass   |                                        |
| AC-4.5  | P2       | BE `GatewayErrorResponseTest#aServiceThatDropsTheConnectionProduces502`; QA `QaDownstreamRobustnessTest#aNonHttpReplyIs502`                                                                                                                                                                                                                                                                                                                                    | Pass   |                                        |
| AC-4.6  | P1       | BE `DownstreamResponseFailureTest#aResponseBodySlowerThanTheResponseTimeoutProduces504`; QA `QaDownstreamResponseFailureTest#aStalledBodyWithAContentLengthIs504`, `QaDownstreamRobustnessTest#aSlowBodyIsNotAnInternalError`                                                                                                                                                                                                                                  | Pass   | BUG-4 closed; committed bodies: BUG-14 |
| AC-4.7  | P2       | BE `DownstreamResponseFailureTest#anIncompleteOrInvalidDownstreamResponseProduces502` (2); QA `QaDownstreamResponseFailureTest` (status 000/099/600/1000/`abc`, cut and invalid chunks, a cut 1.5 s into a 2 s timeout gives 502, the Problem carries no downstream header, 60 concurrent requests), `QaDownstreamRobustnessTest#aBodyCutOffMidWayIsNotSuccess`, `#anInvalidStatusCodeIs502`                                                                   | Pass   | BUG-4, BUG-7 closed; BUG-16            |
| AC-4.8  | P1       | BE `GatewayErrorResponseTest#onlyASafeReadIsEverSentAgainAfterTheServiceDropsTheConnection` (5 rows, `during(3 s)`); QA `QaDownstreamRobustnessTest#aDroppedGetIsSentAtMostTwice`                                                                                                                                                                                                                                                                              | Pass   | BUG-3 closed by decision               |
| AC-5.1  | P1       | BE `GatewayErrorResponseTest#a401…`, `#a404…`, `DownstreamUnavailableTest#a503…`, `DownstreamTimeoutTest#a504…`; QA `QaContractConformanceTest` (exact field set for 503 and 504)                                                                                                                                                                                                                                                                              | Pass   |                                        |
| AC-5.2  | P1       | BE `DownstreamUnavailableTest#gatewayErrorsDoNotRevealInternalDetails`; QA `QaLeakageTest#errorBodiesRevealNothingInternal` (5)                                                                                                                                                                                                                                                                                                                                | Pass   |                                        |
| AC-6.1  | P1       | BE `CorsPolicyConfigurationTest#theGatewayAnswersAPreflightRequestFromAnAllowedOrigin`; QA `QaCorsAdversarialTest` (2)                                                                                                                                                                                                                                                                                                                                         | Pass   |                                        |
| AC-6.2  | P1       | BE `CorsPolicyConfigurationTest` (2); QA `QaCorsAdversarialTest` (13)                                                                                                                                                                                                                                                                                                                                                                                          | Pass   |                                        |
| AC-6.3  | P1       | BE `CorsPolicyConfigurationTest#anAllowedOriginCanReadTheResponseAndItsRequestId`                                                                                                                                                                                                                                                                                                                                                                              | Pass   |                                        |
| AC-6.4  | P1       | BE `CorsPolicyConfigurationTest#anAllowedOriginCanReadA401FromTheGateway`; QA `QaCorsAdversarialTest#gatewayErrorsCarryCorsHeadersForAllowedOrigins`, `QaDownstreamResponseFailureTest#theTimeoutProblemAfterHeadersCarriesTheCorsGrant`, `#theProblemAfterHeadersCarriesOnlyTheGatewaysHeaders`                                                                                                                                                               | Pass   |                                        |
| AC-6.5  | P1       | BE `CorsAllowedOriginsTest#allowedOriginsComeFromConfiguration` (5, row 4 now passes); QA `QaConfigurationTest#invalidSettingsStopStartup`                                                                                                                                                                                                                                                                                                                     | Pass   | BUG-1 closed                           |
| AC-6.6  | P2       | BE `CorsPolicyConfigurationTest#corsHeadersSetByAServiceAreNotDuplicated`; QA `QaCorsAdversarialTest#downstreamCorsHeadersAreDropped`                                                                                                                                                                                                                                                                                                                          | Pass   |                                        |
| AC-7.1  | P1       | BE `RequestIdFilterTest#theGatewayAssignsARequestIdWhenTheClientSendsNone` (now checks all values); QA `QaHeaderAbuseTest#forwardedResponsesCarryExactlyOneRequestId`, `QaContractConformanceTest#passedThroughResponsesCarryTheRequestId`                                                                                                                                                                                                                     | Pass   |                                        |
| AC-7.2  | P1       | BE `RequestIdFilterTest#aWellFormedClientRequestIdIsKept`; QA `QaHeaderAbuseTest#theLongestValidRequestIdIsKept`                                                                                                                                                                                                                                                                                                                                               | Pass   |                                        |
| AC-7.3  | P1       | BE `RequestIdFilterTest#aMalformedClientRequestIdIsReplaced` (4, now checks all values); QA `QaHeaderAbuseTest` (8)                                                                                                                                                                                                                                                                                                                                            | Pass   |                                        |
| AC-7.4  | P1       | BE `AccessLogFilterTest#theGatewayWritesExactlyOneAccessLogEntryPerRequest` (3); QA `QaAccessLogTest` (4)                                                                                                                                                                                                                                                                                                                                                      | Pass   |                                        |
| AC-7.5  | P1       | BE `AccessLogFilterTest#secretsNeverAppearInGatewayLogs`; QA `QaLeakageTest#credentialsNeverAppearInLogs`; manual (12:52): no token or password in the gateway log                                                                                                                                                                                                                                                                                             | Pass   |                                        |
| AC-7.6  | P1       | BE `RequestIdFilterTest#theResponseCarriesExactlyOneRequestIdTheGateways` (2); QA `QaDownstreamResponseFailureTest#rawDownstreamRequestIdsInAnyLetterCaseAreDropped` (raw lower-case `x-request-id` plus `X-REQUEST-ID`, checked on the wire)                                                                                                                                                                                                                  | Pass   | BUG-10 closed                          |
| AC-7.7  | P1       | BE `AccessLogFilterTest#invitationTokensAreMaskedWhateverFormThePathTakes` (5); QA `QaLeakageTest#invitationTokensInNonCanonicalPathsAreNotLogged` (5, the former BUG-2 reproduction), `#invitationTokensNextToDotSegmentsOrPathParametersAreMasked` (12 `..`, `%2e` and `;` rows); manual on the real stack (`%69nvitations`, masked)                                                                                                                         | Pass   | BUG-2 closed; BUG-15                   |
| AC-7.8  | P2       | BE `AccessLogDownstreamFailureTest#theAccessEntryNamesTheTargetServiceWhenThatServiceFails` (3)                                                                                                                                                                                                                                                                                                                                                                | Pass   |                                        |
| AC-8.1  | P0       | BE `RoutingTest#serviceLocationsComeFromConfiguration`; QA `QaConfigurationTest#theAuthServiceUrlEnvironmentVariableIsUsed`                                                                                                                                                                                                                                                                                                                                    | Pass   |                                        |
| AC-8.2  | P1       | BE `ServerPortTest` (3); manual: the gateway bound 8080 with no port setting                                                                                                                                                                                                                                                                                                                                                                                   | Pass   |                                        |
| AC-8.3  | P1       | BE `JwtSecretStartupTest` (3, "not set" now passes), `JwtSecretDecoderTest` (2); QA `QaConfigurationTest#unusableSecretsStopStartup` (5), `#exactly64BytesIsAccepted`, `#theBaseConfigurationActivatesNoProfile`. Boot jar (12:52), no profile: with `JWT_SECRET` unset, exit 1 and "APPLICATION FAILED TO START … Reason: must be set through the JWT_SECRET environment variable"; not Base64, exit 1; 32 bytes, exit 1. The secret value was never printed. | Pass   | BUG-1 closed                           |
| AC-8.4  | P1       | BE `HealthEndpointTest`; QA `QaUnknownPathsTest#healthMatchesTheContract`                                                                                                                                                                                                                                                                                                                                                                                      | Pass   | BUG-13 closed (contract)               |
| AC-8.5  | P1       | BE `ActuatorExposureTest#otherManagementEndpointsAreNotExposed` (4); QA `QaUnknownPathsTest` (19)                                                                                                                                                                                                                                                                                                                                                              | Pass   |                                        |
| AC-8.6  | P0       | Manual, 12:50–12:51. Both run with `--spring.profiles.active=dev` and no port setting. auth-service: "The following 1 profile is active: "dev"" and "Tomcat started on port 8081". Gateway: the same profile line and "Tomcat started on port 8080". No `Application run failed`, no ComponentScan or compatibility warning. `:8081/v3/api-docs` gave 200 and `:8080/actuator/health` gave `{"status":"UP"}` with 200.                                         | Pass   |                                        |

**Counts:** 50 pass, 0 fail, 0 blocked, 0 not tested. By priority: P0 12/12, P1 32/32, P2 6/6.

### Suites run (re-verification)

Before every Gradle run, `ps` showed no other Gradle build active. The only other Gradle processes were IntelliJ's idle
Gradle 9.8.0 daemon (pid 64322, JDK 27) and its compiler worker (pid 66943). qa didn't touch them. Ports 8080, 8081 and
18080–18083 were free before the manual and exploratory runs.

| #  | Time        | Command                                                                                                                                                                                                                                                              | Result                                                                                                                                                                                                            |
|----|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| R1 | 12:31–12:36 | `cd api-gateway && ./gradlew clean build` (tree as handed over)                                                                                                                                                                                                      | BUILD SUCCESSFUL. JUnit XML: **358 tests, 0 failed, 4 skipped** in 32 classes. The skipped tests are only D10's: BUG-6 × 2 (one is the parameterized container) and BUG-8 × 2. This confirms backend-swe's claim. |
| R2 | 12:42       | `./gradlew test -I <scratch init script setting junit.jupiter.conditions.deactivate=org.junit.*DisabledCondition> --tests '…QaUnknownPathsTest' --tests '…QaRequestFidelityTest'`                                                                                    | 45 tests, 6 failed. Every D10 test still fails when force-run (details under "Bug status"), so none is re-enabled.                                                                                                |
| R3 | 12:46       | `./gradlew test --tests` for the 4 new or changed QA classes                                                                                                                                                                                                         | BUILD SUCCESSFUL, 73 tests, 0 failed, 2 skipped (D10)                                                                                                                                                             |
| R4 | 12:47–12:50 | Mutation check of the new AC tests: 8 mutants, each in a scratch copy under `/tmp` (not the repo), `./gradlew test --offline --tests <affected classes>`                                                                                                             | Every mutant was detected by at least one backend-swe test and one qa test. See "Do the new tests prove the fixes?".                                                                                              |
| R5 | 12:53–12:54 | `cd api-gateway && ./gradlew clean build` (final)                                                                                                                                                                                                                    | BUILD SUCCESSFUL. **411 tests, 0 failed, 4 skipped** (D10 only) in 34 classes. backend-swe: 171 tests, 0 failed. qa: 240 tests, 0 failed, 4 skipped.                                                              |
| R6 | 12:55       | `cd auth-service && ./gradlew build`                                                                                                                                                                                                                                 | BUILD FAILED. `AuthServiceApplicationTests > initializationError`, "Unable to find a @SpringBootConfiguration". **Baseline** (Known issue 4), unchanged.                                                          |
| R7 | 12:50–12:53 | Manual: `./gradlew bootRun --args='--spring.profiles.active=dev'` in auth-service, then in api-gateway; README steps for AC-8.6, AC-1.6 and AC-2.8                                                                                                                   | All pass (see matrix)                                                                                                                                                                                             |
| R8 | 12:52       | Boot jar, with no profile and `env -u JWT_SECRET -u SPRING_PROFILES_ACTIVE`, plus the two other AC-8.3 rows                                                                                                                                                          | Startup fails in all 3 cases, and the secret is never printed                                                                                                                                                     |
| R9 | 12:38–12:56 | Exploratory: boot jar on 18080 (QA-only random `JWT_SECRET`, `GATEWAY_RESPONSE_TIMEOUT=2s`, `CORS_ALLOWED_ORIGINS=http://localhost:5173`), Python raw-socket stubs on 18081–18083, `curl` and a Python concurrency probe. Also the no-T15 mutant jar for comparison. | Findings BUG-14, 15, 16. All former bug reproductions now behave as specified (see "Bug status"). 200 concurrent mixed requests: 200 correct.                                                                     |

**Failure classification.** R1 and R5 had no failures. R2's six failures are the deferred D10 reproductions (BUG-6,
BUG-8). They are expected, deterministic, and the same as in the first round. There are no regressions. No flaky tests
were observed: every new qa test passed in all its runs (R3, R5, and the unmutated classes in R4).

### Bug status

| Bug    | Sev | First-round status | Re-verification                             | Evidence                                                                                                                                                                                                                                                                          |
|--------|-----|--------------------|---------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| BUG-1  | S1  | Open               | **Closed**                                  | `QaConfigurationTest#theBaseConfigurationActivatesNoProfile`, `JwtSecretStartupTest` "not set" and `CorsAllowedOriginsTest` row 4 pass. On the jar with no profile and no `JWT_SECRET`, startup fails. An earlier jar start (R9, with `JWT_SECRET`) logs `default` profile.       |
| BUG-2  | S3  | Open               | **Closed**                                  | 5 former reproduction rows pass, plus 12 new AC-7.7 rows. On the real stack, `/api/auth/%69nvitations/inv-QAENCODED/accept` is logged as `path=/api/auth/%69nvitations/***/accept`, and the token occurs 0 times in the log. Residual adversarial forms: BUG-15.                  |
| BUG-3  | S3  | Open               | **Closed by decision** (D6 amended, AC-4.8) | AC-4.8 tests pass. GET is sent at most twice, and POST, PUT, PATCH and DELETE once.                                                                                                                                                                                               |
| BUG-4  | S3  | Open               | **Closed** for responses not yet committed  | `aBodyCutOffMidWayIsNotSuccess` gives 502 and `aSlowBodyIsNotAnInternalError` gives 504, with no `Unexpected gateway error`. WARN without stack trace (`Downstream call failed: route=scenarios status=502 cause=IOException`). Committed responses: BUG-14 (FU-4).               |
| BUG-5  | S3  | Open               | **Closed**                                  | Former reproductions pass. The curl `-d` PATCH reaches the stub with query `notify=false&page=2` and body `{"name":"Acme QA"}`.                                                                                                                                                   |
| BUG-6  | S4  | Open               | **Open, deferred (FU-1)**                   | Force-run (R2): `/error` gives 500 (`expected: 404 but was: 500`); `/actuator/health/{liveness,readiness,ping}` give `Content-Type … but was: null`. Stays `@Disabled` per D10.                                                                                                   |
| BUG-7  | S4  | Open               | **Closed**                                  | `anInvalidStatusCodeIs502` passes. Status lines 000, 099, 100, 199, 600, 999, 1000, 99999 and `abc` all give the 502 Problem (R9).                                                                                                                                                |
| BUG-8  | S4  | Open               | **Open, deferred (FU-2)**                   | Force-run (R2): `but was: "next=%2Fx%2Fy&filter=a%2Cb&at=a%3Ab%40c&flag="` and `["application/json;charset=UTF-8"]`. backend-swe's claim is confirmed. Stays `@Disabled`.                                                                                                         |
| BUG-9  | S4  | Open               | **Open, deferred (FU-3)**                   | Unchanged (R9). `POST /api/auth/./login` gives 400 with Boot JSON and `X-Request-Id`. `POST /actuator/health` gives 405 `application/problem+json` without `type` and `requestId`. `TRACE` gives 405 Boot JSON with no `X-Request-Id`. Matches the contract's phase-1 deviations. |
| BUG-10 | S4  | Open               | **Closed**                                  | AC-7.6 tests pass. The exploratory reproduction now shows one `X-Request-Id: client-rid`, and the look-alike `X-Request-Id-Echo` is kept.                                                                                                                                         |
| BUG-11 | S4  | Open               | **Closed**                                  | AC-2.9 tests pass. Both orders of valid and expired get 401, and the stub receives nothing.                                                                                                                                                                                       |
| BUG-12 | S4  | Open               | **Closed**                                  | AC-2.10 tests pass. `sub` = `abc\r\nX-Injected: 1` gets 401, with one access entry `requestId=crlf-sub-1 … status=401` and no injected line.                                                                                                                                      |
| BUG-13 | S4  | Open               | **Closed**                                  | Contract 0.2.0 documents both media types. Observed: `application/vnd.spring-boot.actuator.v3+json` by default, and `application/json` with `Accept: application/json`.                                                                                                           |
| BUG-14 | S3  | —                  | **New, open**                               | See below                                                                                                                                                                                                                                                                         |
| BUG-15 | S4  | —                  | **New, open**                               | See below                                                                                                                                                                                                                                                                         |
| BUG-16 | S4  | —                  | **New, open**                               | See below                                                                                                                                                                                                                                                                         |

### New bugs

New bugs have no automated reproduction in the repo yet. A failing test would turn the build red, and only pm can
authorise D10-style disabling. qa will add the reproductions once pm has triaged the bugs: either enabled, if the bug is
fixed, or disabled under a D10 extension, if it is deferred.

#### BUG-api-gateway-14: A downstream failure after the response is committed isn't aborted; a chunked 200 gets the Problem JSON appended and reads as complete

- Severity: S3 Major (response integrity). It is pre-existing, and its area is deferred to FU-4 by D6.
- Affected: api-gateway, `exception/GatewayExceptionHandler` (`handleDownstreamFailure` → `discardDownstreamResponse`
  when `response.isCommitted()`). Every route, for responses larger than Tomcat's 8 KB response buffer, or with a
  streaming media type, which Spring Cloud Gateway flushes per chunk.
- Linked AC: D6 "Failures after the response has started (follow-up FU-4) … It must abort the client connection instead,
  so a truncated body can't be read as complete. This isn't tested in phase 1." Contract
  `components.responses.BadGateway` ("includes failures after the response headers arrived"). AC-4.6 and AC-4.7 pass
  because their bodies are small.
- Commit tested: `aa2234c` + working tree (main code as of 12:25)
- Steps to reproduce:
    1. `cd api-gateway && ./gradlew bootJar`, then start `java -jar build/libs/api-gateway-0.0.1-SNAPSHOT.jar` with
       `JWT_SECRET=<QA-only 64-byte Base64>`, `SERVER_PORT=18080`, `SCENARIO_SERVICE_URL=http://localhost:18082` and
       `GATEWAY_RESPONSE_TIMEOUT=2s`.
    2. On 18082, run a raw socket stub that answers `GET /api/scenarios/big-cut-chunked` with `HTTP/1.1 200 OK`,
       `Content-Type: application/json`, `Transfer-Encoding: chunked`, then 25 chunks of 4096 bytes, and then closes
       without the terminating chunk.
    3.
    `curl -s -o body -w '%{http_code} %{size_download}\n' -H 'Authorization: Bearer <valid>' http://localhost:18080/api/scenarios/big-cut-chunked; echo "exit=$?"; tail -c 120 body`
- Expected: per D6 (FU-4), the client connection is aborted, so the truncation is detectable. Logging is WARN without a
  stack trace, and the access entry reflects what the client got.
- Actual:
    - **Chunked:** `200 102627`, `exit=0`. The body is the 100 KB of downstream data followed by
      `{"detail":"The service handling this request returned an invalid response.",…,"status":502,"title":"Bad Gateway",…}`,
      and the chunked stream is terminated properly. The client can't tell that the body is truncated and corrupted. The
      access entry says `status=200`, while the WARN says `status=502`.
    - **`Content-Length: 200000`, 100,000 bytes sent:** the client gets 200 with 100,000 bytes and the connection is
      closed (`curl` exit 18), so the truncation is detectable. But the access entry says `status=500` (the client got
      200), followed by three Tomcat ERROR entries with stack traces:
      `ResourceAccessException: The downstream response body could not be read` caused by `EOFException`, then
      `AuthorizationDeniedException: Access Denied` on the `/error` dispatch, then
      `Exception Processing [ErrorPage[errorCode=0, location=/error]]`. No downstream URL appears in them.
    - **`text/event-stream` slower than the timeout:** 200, 20 bytes, `curl` exit 18, access `status=500`, the same
      ERROR entries.
    - **Threshold:** a `Content-Length` body cut after ≤ 8,192 bytes gets the correct 502 Problem. From 9,000 bytes on,
      the response is already committed.
    - **Pre-existing:** a scratch build without T15's `DownstreamResponseFailureConfiguration` (mutant M2) appends a *
      *500** Problem to the same chunked 200. T15 didn't introduce the defect; it only changed the appended status from
      500 to 502.
- Evidence: exploratory run R9 (reproduced twice). No repo test, because D6 excludes this from phase-1 testing.
- Suggested owner: pm first: attach this to FU-4 as its first acceptance test, and decide whether it must be fixed
  before any service returns bodies over 8 KB or streams (report-service). Then backend-swe. Direction: when the
  response is already committed, don't return a body. Let the failure abort the connection, so the chunked stream is
  never terminated cleanly, and log once at WARN.

#### BUG-api-gateway-15: Three adversarial path forms still log the invitation token: a double-encoded segment, an encoded semicolon, and
`..;`

- Severity: S4 Minor
- Affected: api-gateway, `filter/AccessLogFilter.maskedPath` (access log)
- Linked AC: D7 / TF-4: "No invitation token appears in any log line … whatever the path's percent-encoding, letter
  case, duplicate slashes or dot segments, and whether the request is forwarded or rejected". AC-7.7's 5 rows and qa's
  12 extra rows pass.
- Commit tested: `aa2234c` + working tree
- Steps to reproduce (gateway on 18080 as in R9; on 8080 the result is the same):
    1.
    `curl --path-as-is -s -o /dev/null -w '%{http_code}\n' -X POST -H 'X-Request-Id: m77-15' 'http://localhost:18080/api/auth/%2569nvitations/inv-QaDouble1Secret/accept'`
    2. The same with `/api/auth/invitations%3Bv=1/inv-QaEncSemi1Secret/accept` and
       `/api/auth/invitations/..;/inv-QaDotSemi1Secret/accept`.
    3. `grep -c inv-Qa…Secret` the gateway log.
- Expected: by a literal reading of D7, the token segment is masked.
- Actual: all three get 400 from the request firewall and are never forwarded. The access entry logs the raw path, for
  example `requestId=m77-15 method=POST path=/api/auth/%2569nvitations/inv-QaDouble1Secret/accept status=400`. Each
  token occurs once in the log.
- Assessment: `maskedPath` decodes once and strips `;` parameters before decoding, which is how Tomcat and Spring
  resolve paths. For these three forms, no server would treat the segment as the accept operation's token:
    - `%2569` decodes to `%69nvitations`;
    - `%3B` is data, not a parameter delimiter;
    - `..;` removes `invitations`.

  Only a broken or malicious client sends them, and the firewall rejects them all. The risk is low.
- Evidence: exploratory run R9 (`probe77`). The other 14 forms in the same probe were masked.
- Suggested owner: pm, to decide between two options:
    - accept the current behaviour and narrow D7's wording to "any form that a server resolves to the invitation path";
    - require hardening, for example masking every segment that follows any segment whose fully decoded,
      parameter-stripped, lower-case form contains `invitations`.

  Then backend-swe, if pm asks for hardening.

#### BUG-api-gateway-16: A 502 or 504 after the headers arrived repeats every multi-valued gateway header: three
`Vary` lines become nine

- Severity: S4 Minor
- Affected: api-gateway, `configuration/DownstreamResponseFailureConfiguration#headersOf` with
  `exception/GatewayExceptionHandler#discardDownstreamResponse` (T15). Responses: the 502 and 504 Problems for failures
  after the downstream headers arrived.
- Linked AC: unspecified. AC-4.7 and AC-6.4 pass. D6 says the Problem response carries the gateway's own headers.
- Commit tested: `aa2234c` + working tree
- Steps to reproduce:
    1. A stub answers `GET /api/scenarios/cut-at/500` with `200`, `Content-Length: 100` and 11 body bytes, then closes
       after 0.5 s.
    2.
    `curl -s -D - -o /dev/null -H 'Authorization: Bearer <valid>' -H 'Origin: http://localhost:5173' http://localhost:18080/api/scenarios/cut-at/500`
- Expected: the same headers as any other gateway error, with `Vary: Origin`, `Vary: Access-Control-Request-Method` and
  `Vary: Access-Control-Request-Headers` once each. This is what a 502 for a connection dropped before the headers
  carries.
- Actual: 9 `Vary` lines, each of the three values three times. `X-Request-Id` and `Access-Control-Allow-Origin` appear
  once, because they are single-valued. Probable cause, from code reading: `headersOf` iterates
  `response.getHeaderNames()`, which on Tomcat returns a name once per header line, and adds all values of that name
  each time.
- Impact: nothing functional today, because duplicate `Vary` members are harmless. Any future multi-valued gateway
  header (for example `Set-Cookie` or `Link`) would be multiplied the same way.
- Evidence: exploratory run R9 (`h.500`). backend-swe's
  `theProblemResponseCarriesNoHeaderFromTheFailedDownstreamResponse` doesn't check the restored headers.
- Suggested owner: backend-swe (iterate distinct header names).

### Do the new tests prove the fixes? (mutation check, R4)

Each mutant reintroduced one defect in a scratch copy of `api-gateway` under `/tmp`; the repository wasn't touched. "
Killed by" lists the tests that failed.

| Mutant | Reintroduced defect                                  | Killed by backend-swe tests                                  | Killed by qa tests                                                                                                                                                                                      |
|--------|------------------------------------------------------|--------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| M1     | T14 reverted: masking by the old raw-path regex      | AC-7.7 (5 rows), `theInvitationTokenIsMaskedInEveryPathForm` | 5 former BUG-2 rows, 9 of 12 new AC-7.7 rows (the 3 others are also masked by the old regex)                                                                                                            |
| M2     | T15 configuration removed                            | AC-4.6, AC-4.7 (2 rows), WARN test, header test              | former BUG-4/BUG-7 tests, 9 `QaDownstreamResponseFailureTest` tests. Status lines 000, 099, 1000 and `abc` give 502 even without T15, because the JDK client rejects them itself; 600 and 999 need T15. |
| M3     | Every body failure classified as 502 (no time check) | AC-4.6                                                       | 2 (504 with `Content-Length`, CORS on 504)                                                                                                                                                              |
| M4     | No `response.reset()` before the Problem             | AC-4.7 row 1, header test                                    | 4. Without the reset, the failed response's `Content-Length: 100` breaks framing, so the reset is load-bearing.                                                                                         |
| M5     | `FormContentFilter` re-enabled                       | AC-1.7 rows 1–4                                              | former BUG-5 rows, 3 of 4 new AC-1.7 rows (multipart is unaffected by that filter)                                                                                                                      |
| M6     | Downstream `X-Request-Id` kept                       | AC-7.6 (2), unit test                                        | raw AC-7.6 test                                                                                                                                                                                         |
| M7     | First `Authorization` header wins (no count check)   | AC-2.9 rows 1 and 3                                          | 4 of 5 new AC-2.9 rows                                                                                                                                                                                  |
| M8     | Any non-blank `sub` accepted                         | AC-2.10 (4 rows), 7 validator unit rows                      | 5 of 10 new AC-2.10 rows (the non-string rows are rejected by jjwt anyway)                                                                                                                              |

backend-swe's claim "every new AC test failed before its fix" holds per AC and per test method. It doesn't hold for
every row: AC-2.9 row 2 (expired, then valid) and qa's "empty, then valid" row pass even without the fix, because the
first header is the one that was checked. That follows from the spec's data, not from a flaw in the test. AC-4.8 and
AC-7.8 needed no code change, as the spec expected.

### Review of the fix-round changes

- **T13 (BUG-1).** Only the `profiles:` / `active: dev` lines were removed from `application.yaml`. The README "Run
  locally" section has the IntelliJ "Active profiles: dev" and `SPRING_PROFILES_ACTIVE` options and the "never in
  `application.yaml`" warning.
    - README observation: the README says to look for `Application run failed`. When `JWT_SECRET` is missing, Boot's
      failure analyzer prints `APPLICATION FAILED TO START` instead, and `Application run failed` doesn't appear (R8).
      Someone grepping for the README's marker would miss this startup failure. Suggestion for backend-swe: mention both
      markers. No bug filed.
- **T14 (BUG-2).** `maskedPath` resolves segments the way a server does: it strips `;` parameters, percent-decodes,
  compares in lower case, and resolves empty, `.` and `..` segments. Every realistic form tested is masked (see the
  matrix). The residual forms are BUG-15. The access entry format is unchanged. AC-7.4 and the old `QaAccessLogTest`
  pass.
- **T15 (BUG-4, BUG-7).**
    - **Time-based 502/504.**
        - How it works: a body read failure is 504 if `GATEWAY_RESPONSE_TIMEOUT` has elapsed since the
          `RequestHttpHeadersFilter` ran, and 502 otherwise.
        - Measured with a 2 s timeout: a drop at 0, 0.5, 1.5 or 1.9 s gives 502; a stall gives 504 at 2.01 s.
        - It is sound in practice, because the recorded start comes before the HTTP client's own timer starts, so a
          close by that timer always counts as a timeout.
        - It is a heuristic, though: the cause is inferred from elapsed time, because Spring's JDK client reports a body
          timeout as a plain `IOException: closed`. A drop observed late, because the gateway read slowly, can only
          happen once the response is committed (BUG-14 territory).
    - **`response.reset()` before commit.** Verified:
        - the Problem carries no `Set-Cookie`, `Cache-Control`, `X-Downstream`, downstream `Access-Control-*` or
          downstream `Content-Length`;
        - `X-Request-Id` appears exactly once;
        - the CORS grant for an allowed origin is restored on both 502 and 504.

      Mutant M4 shows the reset is needed for correct framing. Restored multi-valued headers are duplicated (BUG-16).
    - **Committed responses.** No reset happens, and a Problem body is still written (BUG-14).
    - **`RequestContextHolder` inside a header filter.**
        - All per-request state lives in servlet request attributes. The lambdas capture only the immutable timeout, and
          there are no static or mutable fields, so nothing can leak between requests.
        - Verified with 200 concurrent requests on keep-alive connections that mix cut bodies, invalid status lines and
          good responses (all 200 correct), with the qa test that runs 60 concurrent requests, and with a failure
          followed by a success on the same client connection.
        - `RequestContextHolder` is bound by `DispatcherServlet` on the request thread, and SCG Server MVC runs the
          exchange synchronously on that thread, so it is valid.
        - `(Long) servletRequest.getAttribute(EXCHANGE_START_ATTRIBUTE)` would throw a `NullPointerException` if the
          request filter ever didn't run. That can't happen today.
        - qa's opinion for pm is under "Questions for pm".
    - **Body stream wrapper.** It overrides `read()` and `read(byte[], int, int)`. `FilterInputStream` doesn't override
      `transferTo` in JDK 17 or JDK 27 (checked with `javap`), and SCG copies with `transferTo` or `read(byte[])`, so
      every read goes through the wrapper.
- **T16 (BUG-5).**
    - Disabling `FormContentFilter` breaks nothing else in the gateway:
        - no gateway class calls `getParameter*`, `getParameterMap` or `getQueryString` (grep of `src/main`);
        - security matching uses `PathPatternRequestMatcher` only;
        - there are no `@Controller` endpoints with request parameters;
        - `HiddenHttpMethodFilter` is off by default.
    - SCG's own `FormFilter` acts only on `POST` with a form content type (checked in the 5.0.2 bytecode: `isFormPost`).
      SCG builds the downstream query from `ServerRequest.params()`, which for PUT, PATCH and DELETE now holds only
      query parameters, because Tomcat parses bodies only for POST.
    - Verified: PUT, PATCH and DELETE form bodies with a charset, ISO-8859-1 bytes, UTF-8 text, valueless fields or an
      upper-case media type, a 227 KB form and a multipart PUT all arrive byte for byte, with the query unchanged. POST
      form bodies are still re-serialised, which is FU-2 as documented.
- **T17 (BUG-10).** Every letter case of the downstream `X-Request-Id` is dropped. That includes raw lower-case and
  upper-case copies on the wire, while a look-alike header (`X-Request-Id-Echo`) is kept. The AC-7.1 and AC-7.3 tests
  now assert all values (first-round weakness 4 is resolved).
- **T18 (BUG-11, BUG-12).**
    - Exactly one `Authorization` header is required on protected routes. Two headers with different name case, an empty
      copy (before or after), `Basic` plus `Bearer`, three copies, and a single comma-joined value all get 401 and
      aren't forwarded.
    - The scheme is still case-insensitive: one `bearer` or `BEARER` header is accepted and forwarded byte for byte,
      with `X-User-Id`.
    - Public operations (AC-2.5) still forward every `Authorization` header unchanged and in order, without counting or
      evaluating them, and set no `X-User-Id`.
    - `sub` must be printable ASCII. Non-string `sub` values (number, array, object, boolean, null) give 401 with no
      500. `!`, `~`, `a"b\c`, `abc=forged` and a 512-character `sub` are accepted.
    - The rejected `sub` and the token are never logged, at INFO or above.
- **T19 (BUG-13, contract 0.2.0).**
    - All 7 items are present: health media types; the `sub` format and several `Authorization` headers; the scheme
      letter case; exactly one `X-Request-Id`; the 502 and 504 wording, timeout coverage and the replay rule; the
      `Problem.status` phase-1 deviations; forwarding fidelity with the FU-2 deviations and security headers; the CORS
      403 for a disallowed method or header; `info.version: 0.2.0`.
    - The file parses as YAML with `openapi: 3.1.0`, checked with Python `yaml.safe_load`. No OpenAPI schema validator
      is installed, so full OpenAPI validation wasn't done.
    - The behaviour observed matches the contract, including the BUG-9 deviations it documents.

### Weaknesses in backend-swe's new and changed tests

qa didn't edit any of these tests.

1. **AC-7.6** (`RequestIdFilterTest#theResponseCarriesExactlyOneRequestIdTheGateways`). The JDK `HttpServer` stub
   canonicalises `x-request-id` to `X-request-id`, so the scenario's raw lower-case header isn't sent byte for byte, and
   only one downstream copy is tested end to end. The test's javadoc admits this. qa added a raw-socket test with
   lower-case and upper-case copies.
2. **AC-7.7.** Only the 5 spec rows run end to end. The `..` and `;` forms exist only in the `maskedPath` unit test, and
   one of those assertions is weak (`doesNotContain("inv-7Kq2")` for `/inv-7Kq2/../accept`). qa added 12 end-to-end
   rows.
3. **AC-2.9.** It uses only same-case header names sent through the JDK client, and asserts only the status and no
   forwarding, not the Problem body or `WWW-Authenticate`. Row 2 can't fail against the pre-fix logic (see the mutation
   check). qa added 5 raw rows.
4. **AC-2.10.** It covers string subjects only. qa added non-string `sub` types and more control characters.
5. **The T15 header test** (`theProblemResponseCarriesNoHeaderFromTheFailedDownstreamResponse`) doesn't check that the
   gateway's own headers are restored exactly once, which missed BUG-16. It also doesn't check the CORS grant or the
   framing. qa added both.
6. **No test of the `isCommitted()` branch.** That is expected under D6, but the branch currently writes a body (
   BUG-14). FU-4 needs a test that pins the abort.
7. **AC-4.6** has only a chunked slow body, and **AC-4.7** only 2 rows. qa added a stalled `Content-Length` body, the
   timing boundary (a cut before the timeout gives 502), and more invalid status lines and chunk errors.
8. **`support/RawSocketStub`.** It closes the connection after each reply but doesn't send `Connection: close`. With GET
   this is masked by the single GET replay. A future POST test through it could hit a closed pooled connection and get a
   spurious 502. qa's raw stubs send `Connection: close`.
9. **Positive.** Every new AC has at least one backend-swe test that fails when its fix is removed (R4). The new tests
   are honest, and the AC-7.8 test starts one gateway per row, as needed.

### qa changes in this round

These are qa's only edits. All are under `api-gateway/src/test/java/org/apigateway/qa/` and `docs/qa/`. No `@Disabled`
was added, D10's tests are unchanged and still skipped, and no `src/main`, configuration, `build.gradle`, spec,
contract, README or backend-swe test was touched.

| File                                   | Change                                                                                                                                                                                                                    |
|----------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `QaDownstreamResponseFailureTest.java` | New, 15 tests. AC-4.6, AC-4.7, AC-6.4, AC-7.6 and AC-1.4: raw downstream request IDs, invalid status lines, broken chunks, the timing boundary, the Problem's headers, concurrency, and a clean response after a failure. |
| `QaCredentialAmbiguityTest.java`       | New, 22 tests. AC-2.9, AC-2.5, AC-2.2, AC-2.10 and AC-3.1.                                                                                                                                                                |
| `QaLeakageTest.java`                   | Added `invitationTokensNextToDotSegmentsOrPathParametersAreMasked` (AC-7.7, 12 rows). Existing tests unchanged.                                                                                                           |
| `QaRequestFidelityTest.java`           | Added `nonJsonBodiesStayInTheBodyUnchanged` (AC-1.7, 4 rows). D10's two disabled tests are byte-for-byte unchanged.                                                                                                       |
| `RawStubServer.java`                   | Also records request heads (`requestHeaderValues`)                                                                                                                                                                        |
| `QaSupport.java`                       | Added `hs512Signed` and `claimsWithRawSubject` (tokens with non-string `sub` values)                                                                                                                                      |

### Questions for pm

1. **backend-swe Q1: should D6 and the contract state that a 502/504 after the headers arrived carries none of the
   failed response's headers?** qa recommends yes. Proposed rule: "A gateway-generated error never carries a header from
   a downstream response, also when the failure happens after the downstream headers arrived; it carries only the
   gateway's own headers (`X-Request-Id`, CORS for allowed origins, security headers)."
    - Why: without that rule, `Set-Cookie`, caching directives or `Content-Length` from a failed response could reach
      the client. Mutant M4 shows that a leftover `Content-Length` breaks the framing.
    - Both backend-swe's and qa's tests already assert it.
    - Add a step to AC-4.7 ("And the response has none of the stub's headers"), and state in D6 that a committed
      response (FU-4) can't meet the rule.
2. **backend-swe Q2: is `RequestContextHolder` inside an SCG header filter acceptable as a "supported extension point"?
   ** qa's technical opinion: **acceptable for phase 1, with conditions recorded as a risk.**
    - **For:**
        - `HttpHeadersFilter.ResponseHttpHeadersFilter` beans are SCG Server MVC's public, auto-collected extension
          point; SCG ships its own.
        - `RequestContextHolder` and `MvcUtils.CLIENT_RESPONSE_INPUT_STREAM_ATTR` are public APIs, and SCG's own body
          filters use that attribute.
        - SCG Server MVC runs the proxy exchange synchronously on the servlet thread that `DispatcherServlet` bound, so
          the lookup is valid.
        - All state lives in request attributes, so it is thread-safe (verified under concurrency).
        - Nothing replaces the proxy handler or reconfigures the HTTP client, so it stays within T15's stop condition.
    - **Against:**
        - It depends on an invariant that SCG doesn't document as a contract: header filters run on the request thread.
          If a future SCG version runs them on another thread, `currentRequestAttributes()` throws for every proxied
          response. That would surface at once as a 500 on every route, and the suite would catch it.
        - It uses a headers-in, headers-out filter for side effects: it swaps the body stream and snapshots the servlet
          response. That stretches the extension point's intent.
        - The more idiomatic alternative is an SCG Server MVC filter function (`HandlerFilterFunction`, applied as a
          default filter to every route). It receives both `ServerRequest` and `ServerResponse`, so it needs no
          thread-bound lookup. It needs a filter supplier to be usable from YAML routes, so it costs more.
    - **Recommendation:**
        - accept;
        - record the coupling as a risk to re-verify on every Spring Cloud upgrade (AC-4.6 and AC-4.7 are the tripwire);
        - optionally harden it by using `RequestContextHolder.getRequestAttributes()` and passing through unchanged when
          it returns null. pm decides; it isn't a blocker.
3. **BUG-14 and FU-4.** Should FU-4 take BUG-14's reproductions as its acceptance tests? And must it land before any
   service returns bodies over 8 KB or streams? qa's view: yes, before report-service downloads.
4. **BUG-15.** Accept the current masking and narrow D7's wording, or require hardening?
5. **Repository hygiene (for the user, through pm).** Are these changes intentional: the `.gitignore` edits (it now
   ignores `.claude`, `.gitignore` and `*.iml`), and the staged removal of `Cucumber.iml` and all five `.gitignore`
   files from the index? Either way, re-stage `docs/qa/*` before committing (see "Tree state").
6. **New-bug reproductions.** Should qa add BUG-14, 15 and 16 as disabled reproduction tests under an extension of D10,
   or wait for the fixes?

### Pre-existing failures (baseline, not caused by this feature)

- `auth-service ./gradlew build` fails with `AuthServiceApplicationTests > initializationError`, "Unable to find a
  @SpringBootConfiguration" (Known issue 4). It is unchanged (R6).
- auth-service Known issues 2 and 3 turn the AC-2.8 request into a 403 with an empty body (NPE at
  `OrganizationController.java:40`). This is not a gateway bug.
- D10's deferred reproductions (BUG-6, BUG-8) are skipped in the build and still fail when force-run (R2). They are not
  regressions.
- scenario-service and report-service builds weren't run: they are outside this feature, and their baseline failure is
  documented.

### Gaps and risks (re-verification)

- **FU-4 (BUG-14).** The committed-response path is the main open integrity risk. Phase-1 traffic is unlikely to hit it,
  because auth-service's responses are small JSON, but it will matter for report-service downloads.
- **SCG coupling (T15).** See question 2. Re-run AC-4.6 and AC-4.7 after every Spring Cloud upgrade.
- **Manual checks.** AC-1.6, AC-2.8 and AC-8.6 remain manual (D8). They passed on the final tree.
- **Not re-tested.** Load beyond the 200-request concurrency probe, and a connect timeout to a black-holed host (A11).
  The NFR latency wasn't re-measured; the fix round didn't change the forwarding path for successful responses except
  for one extra stream wrapper and two attribute writes.
- **Unchanged first-round risks:** absolute `Location` URLs; h2c upgrade headers; the shared static stubs in
  `GatewayIntegrationTest`.

### Clean-up

- Every process qa started was stopped: two `bootRun` services, the exploratory jars and the Python stubs. Ports 8080,
  8081 and 18080–18083 were free afterwards.
- qa's Gradle daemon was stopped with `./gradlew --stop`. That stops only 9.5.1 daemons; IntelliJ's 9.8.0 daemon wasn't
  touched.
- Scratch files were deleted: `/tmp/qa-explore` (QA-only secret, tokens), `/tmp/qa-manual` (the real token from AC-1.6),
  `/tmp/qa-mutants`, `/tmp/qa-scripts`, `/tmp/qa-scg`, `/tmp/qa-docs`, the build logs, and the first round's
  `/tmp/qa-d10-run*.log`.
- No secret or real token appears in this report.

---

## First round, 2026-10-12 09:30–11:32 (history, superseded)

Below is the first-round report as it stood after "D10 applied". It is kept unchanged except that its headings are one
level lower. Its "Not signed off" verdict and its bug statuses are superseded by the re-verification above.

First-round header:

- Spec: `docs/specs/api-gateway.md` (Ready, 2026-10-12)
- Contract: `docs/api/api-gateway.openapi.yaml`
- Test plan: `docs/qa/api-gateway-test-plan.md`
- Commit tested: `aa2234c` + uncommitted working tree, tested 2026-10-12 between 09:30 and 10:11 (+05:00). Two tree
  states are involved; see "Working-tree change during the QA run".

### Verdict

**Not signed off** (`aa2234c` + working tree as of 2026-10-12 10:11 +05:00).

Reason: an open **S1** (BUG-api-gateway-1). While QA was running, someone other than qa added
`spring.profiles.active: dev` to `api-gateway/src/main/resources/application.yaml` (file modified at 09:53:24). With
that line, every start runs the `dev` profile:

- the committed DEV ONLY JWT secret and the dev CORS origins become defaults;
- AC-8.3 and AC-6.5 (both P1) now fail;
- a gateway started without `JWT_SECRET` accepts tokens anyone can mint from the secret in the repository.

All P0 ACs pass. On the tree as it was before that line (state T0), backend-swe's suite was 117/117 green, including the
two rows that now fail. If BUG-1 is reverted and re-verified, the expected verdict is **Signed off with known issues**:
4 open S3 and 8 open S4 bugs, none blocking under the sign-off rule.

#### Working-tree change during the QA run

| State | When                                      | `application.yaml`                                            | Evidence                                                    |
|-------|-------------------------------------------|---------------------------------------------------------------|-------------------------------------------------------------|
| T0    | QA start (09:31) to 09:53                 | no `spring.profiles.active`                                   | first `./gradlew clean build` at 09:33: 117 tests, 0 failed |
| T1    | 09:53:24 onwards (still present at 10:11) | `spring.profiles.active: dev` added as the first `spring` key | final `./gradlew clean build`: 306 tests, 22 failed         |

Other activity in the repo during the run, not started by qa: at 10:08:59 a Homebrew Gradle 9.8.0 daemon (not this
project's 9.5.1 wrapper) started `org.apigateway.ApiGatewayApplication` from `api-gateway/build/classes`, listening on *
*port 8080**. It was still running when qa finished. qa did not stop it. Note that qa's final `./gradlew clean build` (
10:10) rebuilt the `build/` directory that this process's classpath points to.

`application-dev.yaml` in both services was also re-saved at 09:55:28. Its content is unchanged: the dev secrets still
match (compared by SHA-256, value not printed), and the git blob of auth-service's file is the same as at the start.
`git diff` does not show the T1 change because `application.yaml` is an untracked file.

`git status --short` (final):

```text
 M api-gateway/build.gradle
 D api-gateway/src/main/java/org/ApiGatewayApplication.java
 D api-gateway/src/main/java/org/router/GatewayRouter.java
 D api-gateway/src/main/resources/application.properties
 M api-gateway/src/test/java/org/apigateway/ApiGatewayApplicationTests.java
 M auth-service/src/main/resources/application-dev.yaml
 M auth-service/src/main/resources/application.yaml
?? .claude/
?? api-gateway/README.md
?? api-gateway/src/main/java/org/apigateway/
?? api-gateway/src/main/resources/application-dev.yaml
?? api-gateway/src/main/resources/application.yaml
?? api-gateway/src/test/java/org/apigateway/ActuatorExposureTest.java
?? api-gateway/src/test/java/org/apigateway/HealthEndpointTest.java
?? api-gateway/src/test/java/org/apigateway/RoutingTest.java
?? api-gateway/src/test/java/org/apigateway/ServerPortTest.java
?? api-gateway/src/test/java/org/apigateway/configuration/
?? api-gateway/src/test/java/org/apigateway/exception/
?? api-gateway/src/test/java/org/apigateway/filter/
?? api-gateway/src/test/java/org/apigateway/qa/
?? api-gateway/src/test/java/org/apigateway/security/
?? api-gateway/src/test/java/org/apigateway/support/
?? docs/
```

`git diff --stat` (tracked files only):

```text
 api-gateway/build.gradle                                  | 15 ++++++---------
 api-gateway/src/main/java/org/ApiGatewayApplication.java  | 13 -------------
 api-gateway/src/main/java/org/router/GatewayRouter.java   | 14 --------------
 api-gateway/src/main/resources/application.properties     |  1 -
 .../java/org/apigateway/ApiGatewayApplicationTests.java   |  3 ++-
 auth-service/src/main/resources/application-dev.yaml      |  4 ++++
 auth-service/src/main/resources/application.yaml          |  5 ++++-
 7 files changed, 16 insertions(+), 39 deletions(-)
```

qa's own changes: new files under `api-gateway/src/test/java/org/apigateway/qa/` and `docs/qa/` only.

### D10 applied (2026-10-12)

The edits below follow spec decision D10 and the D6 replay amendment (TF-2) in `docs/specs/api-gateway.md`. The user
approved the fix round. They are the only test changes, and the verdict above is unchanged until re-verification after
T13–T19.

| Test (`api-gateway/src/test/java/org/apigateway/qa/`)                                                      | Change                                                                                                                                                                                                                                                                                                                                                                                         | Authority                 |
|------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------|
| `QaUnknownPathsTest#healthSubPathsAre404Problems` (3 rows), `#theErrorPathIsAnUnroutedPath`                | Added `@Disabled("BUG-api-gateway-6 deferred: docs/specs/api-gateway.md FU-1")` and its import. `@Tag`, `@DisplayName` and body unchanged.                                                                                                                                                                                                                                                     | D10, FU-1                 |
| `QaRequestFidelityTest#theRawQueryStringIsForwardedUnchanged`, `#theContentTypeHeaderIsForwardedUnchanged` | Added `@Disabled("BUG-api-gateway-8 deferred: docs/specs/api-gateway.md FU-2")` and its import. `@Tag`, `@DisplayName` and body unchanged.                                                                                                                                                                                                                                                     | D10, FU-2                 |
| `QaDownstreamRobustnessTest#aDroppedGetIsNotRetried`, renamed `#aDroppedGetIsSentAtMostTwice`              | Retagged `AC-4.8` (was `BUG-api-gateway-3`). It now asserts AC-4.8's GET row: status 502, and 3 s later report-service has received at least 1 and at most 2 requests (`hasSizeBetween(1, 2)`, was `hasSize(1)`). Setup, request and timing are unchanged. This follows a spec decision and doesn't weaken the test: BUG-3 is closed by decision, and its bug entry below is kept for history. | D6 (amended), D10, AC-4.8 |

qa changed no other test, no `build.gradle`, no config and no `src/main` file.

After the edits, on `aa2234c` + the working tree at 11:28 (`application.yaml` still contains the BUG-1 line),
`cd api-gateway && ./gradlew test` was run twice in full and once on the affected classes. All three runs gave the same
failure set: BUILD FAILED, Gradle console "303 tests completed, 15 failed, 3 skipped". The JUnit XML and the HTML report
show 304 tests, 15 failed, 4 skipped. The difference is the disabled `@ParameterizedTest`. JUnit never expands its 3
rows, so it appears once as a skipped container in the XML and HTML report and isn't counted on the Gradle console. All
15 failures are reproductions of BUG-1, -2, -4, -5 and -7 (including the 2 backend-swe tests that BUG-1 breaks), the
same set as run 2 minus BUG-3, -6 and -8. The rewritten AC-4.8 test passes.

### Acceptance-criteria verification matrix

**How results are assigned.** An AC's result is the result of its Gherkin scenario(s) as written. QA variants that fail
beyond the scenario's data are filed as bugs ("variant" below) and raised as testability findings TF-1 to TF-10 in the
test plan. They don't change the AC result. In the test names below, "BE" is backend-swe's test and "QA" is qa's.

| AC     | Priority | Test(s)                                                                                                                                                                                                                                                                                                                                                                       | Result                                                                                                                                                                       | Bug                             |
|--------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------|
| AC-1.1 | P0       | BE `RoutingTest#loginIsForwardedToAuthServiceAndItsResponseIsReturnedUnchanged`                                                                                                                                                                                                                                                                                               | Pass                                                                                                                                                                         |                                 |
| AC-1.2 | P0       | BE `RoutingTest#eachPathPrefixIsForwardedToItsOwningService` (6 rows)                                                                                                                                                                                                                                                                                                         | Pass                                                                                                                                                                         |                                 |
| AC-1.3 | P0       | BE `RoutingTest#methodPathQueryHeadersAndBodyAreForwardedWithoutRewriting`; QA `QaRequestFidelityTest#customHeadersAndUnicodeBodiesAreForwarded`, `#formEncodedPostBodyKeepsItsContent`                                                                                                                                                                                       | Pass                                                                                                                                                                         | variants: BUG-5, BUG-8          |
| AC-1.4 | P0       | BE `RoutingTest#errorResponsesFromAServiceArePassedThroughUnchanged` (4); QA `QaRequestFidelityTest#responseHeadersArePassedThrough`                                                                                                                                                                                                                                          | Pass                                                                                                                                                                         |                                 |
| AC-1.5 | P0       | BE `RoutingTest#aPathThatMatchesNoRoute…` (6), `GatewayErrorResponseTest#aPathThatMatchesNoRouteGetsA404Problem…` (6)                                                                                                                                                                                                                                                         | Pass                                                                                                                                                                         | variant: BUG-6 (`/error`)       |
| AC-1.6 | P0       | Manual (README), 09:42: register → 201 (`id` returned), login → 200, `accessToken` non-empty (279 chars, header `{"alg":"HS512"}`, `sub` = registered id, `exp - iat` = 86400)                                                                                                                                                                                                | Pass                                                                                                                                                                         |                                 |
| AC-2.1 | P0       | BE `JwtAuthenticationTest#publicAuthOperationsAreForwardedWithoutAToken` (3)                                                                                                                                                                                                                                                                                                  | Pass                                                                                                                                                                         |                                 |
| AC-2.2 | P0       | BE `JwtAuthenticationTest#aProtectedRequestWithAValidToken…`; QA `QaTokenForgeryTest#theValidTokenIsAccepted`                                                                                                                                                                                                                                                                 | Pass                                                                                                                                                                         |                                 |
| AC-2.3 | P0       | BE `JwtAuthenticationTest#aProtectedRequestWithoutValidCredentialsIsRejectedWith401` (8); QA `QaTokenForgeryTest#forgedTokensAreRejected` (17), `#malformedAuthorizationHeadersAreRejected` (8), `#tokensOutsideTheAuthorizationHeaderAreIgnored`; `QaPublicOperationBypassTest#protectedPathsWithoutATokenAre401` (8); `QaUnknownPathsTest#anonymousUnknownPathsGet401` (12) | Pass                                                                                                                                                                         |                                 |
| AC-2.4 | P1       | BE `JwtAuthenticationTest#aSignedUnexpiredTokenWithoutASubjectIsRejected`; QA `QaTokenForgeryTest#tokensWithoutANonEmptySubjectAreRejected` (no/empty/blank `sub`)                                                                                                                                                                                                            | Pass                                                                                                                                                                         |                                 |
| AC-2.5 | P1       | BE `JwtAuthenticationTest#publicOperationsIgnoreAnInvalidTokenAndForwardItUnchanged` (2)                                                                                                                                                                                                                                                                                      | Pass                                                                                                                                                                         |                                 |
| AC-2.6 | P1       | BE `JwtAuthenticationTest#onlyTheListedAuthOperationsArePublic` (3); QA `QaPublicOperationBypassTest` (wrong method 8, near-miss 12, traversal/encoded 17, query trick 1)                                                                                                                                                                                                     | Pass                                                                                                                                                                         |                                 |
| AC-2.7 | P2       | BE `JwtAuthenticationTest#a401FromTheGatewayTellsTheClientToUseABearerToken`; QA `QaContractConformanceTest#unauthorizedMatchesTheContract` and every QA 401 assertion                                                                                                                                                                                                        | Pass                                                                                                                                                                         |                                 |
| AC-2.8 | P0       | Manual, 09:42: POST `/api/orgs` with the real token → **403**, empty body (≠ 401). Access entry: `requestId=ac-2-8 method=POST path=/api/orgs status=403 durationMs=48 service=auth-service userId=<U>`, U = registered id                                                                                                                                                    | Pass                                                                                                                                                                         |                                 |
| AC-3.1 | P1       | BE `IdentityHeaderFilterTest#theGatewayTellsTheServiceWhichUserMadeTheRequest`; QA `QaTokenForgeryTest#theIdentityHeaderFollowsTheTokenSubject`                                                                                                                                                                                                                               | Pass                                                                                                                                                                         |                                 |
| AC-3.2 | P1       | BE `IdentityHeaderFilterTest#aForgedIdentityHeaderOnAProtectedRequestIsReplaced`; QA `QaHeaderAbuseTest#spoofedCopiesOnAProtectedRequestAreReplaced`, `#spoofedHeaderWithAnInvalidTokenIsNotForwarded`                                                                                                                                                                        | Pass                                                                                                                                                                         |                                 |
| AC-3.3 | P1       | BE `IdentityHeaderFilterTest#aForgedIdentityHeaderOnAPublicRequestIsRemoved` (2); QA `QaHeaderAbuseTest#spoofedCopiesOnAPublicRequestAreRemoved`                                                                                                                                                                                                                              | Pass                                                                                                                                                                         |                                 |
| AC-4.1 | P1       | BE `DownstreamUnavailableTest#aServiceThatIsNotRunningProduces503Quickly`                                                                                                                                                                                                                                                                                                     | Pass                                                                                                                                                                         |                                 |
| AC-4.2 | P1       | BE `DownstreamTimeoutTest#aServiceSlowerThanTheResponseTimeoutProduces504`                                                                                                                                                                                                                                                                                                    | Pass                                                                                                                                                                         | variant: BUG-4 (slow body)      |
| AC-4.3 | P1       | BE `DownstreamTimeoutTest#theGatewayNeverRetriesAFailedRequest`; QA `QaDownstreamRobustnessTest#aDroppedPostIsNotRetried`                                                                                                                                                                                                                                                     | Pass                                                                                                                                                                         | variant: BUG-3 (GET is retried) |
| AC-4.4 | P1       | BE `DownstreamUnavailableTest#otherRoutesKeepWorkingWhileOneServiceIsDown`                                                                                                                                                                                                                                                                                                    | Pass                                                                                                                                                                         |                                 |
| AC-4.5 | P2       | BE `GatewayErrorResponseTest#aServiceThatDropsTheConnectionProduces502`; QA `QaDownstreamRobustnessTest#aNonHttpReplyIs502`                                                                                                                                                                                                                                                   | Pass                                                                                                                                                                         | variants: BUG-4, BUG-7          |
| AC-5.1 | P1       | BE `GatewayErrorResponseTest#a401…`, `#a404…`, `DownstreamUnavailableTest#a503…`, `DownstreamTimeoutTest#a504…`; QA `QaContractConformanceTest` (503/504 exact field set)                                                                                                                                                                                                     | Pass                                                                                                                                                                         |                                 |
| AC-5.2 | P1       | BE `DownstreamUnavailableTest#gatewayErrorsDoNotRevealInternalDetails`; QA `QaLeakageTest#errorBodiesRevealNothingInternal` (401, 401-expired, 404, 403, 502)                                                                                                                                                                                                                 | Pass                                                                                                                                                                         |                                 |
| AC-6.1 | P1       | BE `CorsPolicyConfigurationTest#theGatewayAnswersAPreflightRequestFromAnAllowedOrigin`; QA `QaCorsAdversarialTest#allowedPreflightMatchesThePolicy`, `#identityAndCookieHeadersAreNotGranted`                                                                                                                                                                                 | Pass                                                                                                                                                                         |                                 |
| AC-6.2 | P1       | BE `CorsPolicyConfigurationTest` (2); QA `QaCorsAdversarialTest#lookAlikeOriginsAreRefused` (10), `#actualRequestsFromDisallowedOriginsAreRefusedEvenWithAToken` (3)                                                                                                                                                                                                          | Pass                                                                                                                                                                         |                                 |
| AC-6.3 | P1       | BE `CorsPolicyConfigurationTest#anAllowedOriginCanReadTheResponseAndItsRequestId`                                                                                                                                                                                                                                                                                             | Pass                                                                                                                                                                         |                                 |
| AC-6.4 | P1       | BE `CorsPolicyConfigurationTest#anAllowedOriginCanReadA401FromTheGateway`; QA `QaCorsAdversarialTest#gatewayErrorsCarryCorsHeadersForAllowedOrigins`                                                                                                                                                                                                                          | Pass                                                                                                                                                                         |                                 |
| AC-6.5 | P1       | BE `CorsAllowedOriginsTest#allowedOriginsComeFromConfiguration` (5 rows); QA `QaConfigurationTest#invalidSettingsStopStartup` (wildcard rows)                                                                                                                                                                                                                                 | **Fail** on T1 (row 4 "without the dev profile, not set, `http://localhost:5173` → refused" returns 200). Passed on T0.                                                      | BUG-1                           |
| AC-6.6 | P2       | BE `CorsPolicyConfigurationTest#corsHeadersSetByAServiceAreNotDuplicated`; QA `QaCorsAdversarialTest#downstreamCorsHeadersAreDropped`                                                                                                                                                                                                                                         | Pass                                                                                                                                                                         |                                 |
| AC-7.1 | P1       | BE `RequestIdFilterTest#theGatewayAssignsARequestIdWhenTheClientSendsNone`; QA `QaHeaderAbuseTest#forwardedResponsesCarryExactlyOneRequestId`, `QaContractConformanceTest#passedThroughResponsesCarryTheRequestId`                                                                                                                                                            | Pass                                                                                                                                                                         | related: BUG-10                 |
| AC-7.2 | P1       | BE `RequestIdFilterTest#aWellFormedClientRequestIdIsKept`; QA `QaHeaderAbuseTest#theLongestValidRequestIdIsKept`                                                                                                                                                                                                                                                              | Pass                                                                                                                                                                         |                                 |
| AC-7.3 | P1       | BE `RequestIdFilterTest#aMalformedClientRequestIdIsReplaced` (4); QA `QaHeaderAbuseTest#malformedRequestIdsAreReplaced` (7), `#crlfInTheRequestIdIsNotInjected`                                                                                                                                                                                                               | Pass                                                                                                                                                                         |                                 |
| AC-7.4 | P1       | BE `AccessLogFilterTest#theGatewayWritesExactlyOneAccessLogEntryPerRequest` (3); QA `QaAccessLogTest` (403 CORS, 404 with token, firewall 400, 502)                                                                                                                                                                                                                           | Pass                                                                                                                                                                         |                                 |
| AC-7.5 | P1       | BE `AccessLogFilterTest#secretsNeverAppearInGatewayLogs`; QA `QaLeakageTest#credentialsNeverAppearInLogs`                                                                                                                                                                                                                                                                     | Pass                                                                                                                                                                         | variant: BUG-2                  |
| AC-8.1 | P0       | BE `RoutingTest#serviceLocationsComeFromConfiguration`; QA `QaConfigurationTest#theAuthServiceUrlEnvironmentVariableIsUsed`                                                                                                                                                                                                                                                   | Pass                                                                                                                                                                         |                                 |
| AC-8.2 | P1       | BE `ServerPortTest` (3); manual: gateway bound 8080 with no port setting (AC-8.6 run)                                                                                                                                                                                                                                                                                         | Pass                                                                                                                                                                         |                                 |
| AC-8.3 | P1       | BE `JwtSecretStartupTest` (3), `JwtSecretDecoderTest` (2); QA `QaConfigurationTest#unusableSecretsStopStartup` (5), `#exactly64BytesIsAccepted`                                                                                                                                                                                                                               | **Fail** on T1 (row "not set": the gateway starts on the committed dev secret). Passed on T0. QA's extra rows pass because an explicit `JWT_SECRET` overrides the dev value. | BUG-1                           |
| AC-8.4 | P1       | BE `HealthEndpointTest`; QA `QaUnknownPathsTest#healthMatchesTheContract`                                                                                                                                                                                                                                                                                                     | Pass                                                                                                                                                                         | contract: BUG-13                |
| AC-8.5 | P1       | BE `ActuatorExposureTest#otherManagementEndpointsAreNotExposed` (4); QA `QaUnknownPathsTest#otherManagementEndpointsAre404Problems` (18), `#shutdownIsNotExposed`                                                                                                                                                                                                             | Pass                                                                                                                                                                         |                                 |
| AC-8.6 | P0       | Manual, 09:41: auth-service "Tomcat started on port 8081", gateway "Tomcat started on port 8080", no `Application run failed`, no ComponentScan or compatibility warning; `:8081/v3/api-docs` → 200; `:8080/actuator/health` → `{"status":"UP"}` 200                                                                                                                          | Pass                                                                                                                                                                         |                                 |

**Counts:** 39 pass, 2 fail (AC-6.5, AC-8.3, both P1, both BUG-1), 0 blocked, 0 not tested. All 12 P0 ACs pass.

### Suites run

| # | Tree   | Command                                                                                                                                               | Result                                                                                                                                                                                 |
|---|--------|-------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | T0     | `cd api-gateway && ./gradlew clean build` (09:33)                                                                                                     | BUILD SUCCESSFUL. 117 tests, 0 failed, 0 skipped (19 classes)                                                                                                                          |
| 2 | T1     | `cd api-gateway && ./gradlew clean build` (final, 10:10)                                                                                              | BUILD FAILED. **306 tests, 22 failed**, 0 skipped. backend-swe: 117 tests, 2 failed (BUG-1). qa: 189 tests in 11 classes, 20 failed (1 × BUG-1, 19 × bug reproductions BUG-2 to BUG-8) |
| 3 | T1     | `./gradlew test --tests <the 7 classes with failures>`, run twice                                                                                     | 82 tests, 22 failed both times, identical failure set: **deterministic, not flaky**                                                                                                    |
| 4 | T1     | `./gradlew test --tests 'org.apigateway.configuration.CorsAllowedOriginsTest' --tests 'org.apigateway.configuration.JwtSecretStartupTest'`, run twice | 8 tests, 2 failed both times                                                                                                                                                           |
| 5 | —      | `cd auth-service && ./gradlew build`                                                                                                                  | BUILD FAILED. 1 test, 1 failed: `AuthServiceApplicationTests > initializationError` "Unable to find a @SpringBootConfiguration" (**baseline**, Known issue 4)                          |
| 6 | T0     | Manual: `./gradlew bootRun --args='--spring.profiles.active=dev'` in auth-service, then api-gateway; README steps for AC-8.6, AC-1.6, AC-2.8          | All pass (see matrix)                                                                                                                                                                  |
| 7 | T0 jar | Exploratory: boot jar on 18080 with `JWT_SECRET` (QA-only random value) and Python stubs on 18081–18083; `curl` probes                                | Findings BUG-2, 3, 4, 5, 7, 8, 9, 10, 11, 12                                                                                                                                           |
| 8 | T1 jar | Impact check for BUG-1: jar with no profile flag and no `JWT_SECRET`                                                                                  | starts with profile "dev"; a token minted from the committed dev secret is accepted                                                                                                    |
| 9 | T0 jar | NFR performance: 3 rounds × 200 sequential 1 KB POSTs on keep-alive, gateway (with token) vs direct to the stub                                       | p95 overhead **2.5–2.9 ms** (gateway p95 2.96–3.22 ms; direct p95 0.33–0.44 ms). Matches backend-swe's "about 3–4 ms" claim and is well within the 20 ms NFR                           |

Failure classification (run 2):

| Failing test                                                                                                  | Class | Classification                           |
|---------------------------------------------------------------------------------------------------------------|-------|------------------------------------------|
| `JwtSecretStartupTest` "JWT_SECRET not set"                                                                   | BE    | **Regression** (T0 → T1), BUG-1          |
| `CorsAllowedOriginsTest` row 4                                                                                | BE    | **Regression** (T0 → T1), BUG-1          |
| `QaConfigurationTest#theBaseConfigurationActivatesNoProfile`                                                  | QA    | **Regression**, BUG-1                    |
| `QaLeakageTest#invitationTokensInNonCanonicalPathsAreNotLogged` (5)                                           | QA    | New defect (present in T0 and T1), BUG-2 |
| `QaDownstreamRobustnessTest#aDroppedGetIsNotRetried`                                                          | QA    | New defect, BUG-3                        |
| `QaDownstreamRobustnessTest#aBodyCutOffMidWayIsNotSuccess`, `#aSlowBodyIsNotAnInternalError`                  | QA    | New defect, BUG-4                        |
| `QaRequestFidelityTest#formEncodedBodiesStayInTheBody` (3), `#theAc13RequestWithAFormContentTypeKeepsItsBody` | QA    | New defect, BUG-5                        |
| `QaUnknownPathsTest#healthSubPathsAre404Problems` (3), `#theErrorPathIsAnUnroutedPath`                        | QA    | New defect, BUG-6                        |
| `QaDownstreamRobustnessTest#anInvalidStatusCodeIs502`                                                         | QA    | New defect, BUG-7                        |
| `QaRequestFidelityTest#theRawQueryStringIsForwardedUnchanged`, `#theContentTypeHeaderIsForwardedUnchanged`    | QA    | New defect, BUG-8                        |

None of the failures are flaky. The reproduction tests are tagged `@Tag("BUG-api-gateway-n")`, and each should pass once
its bug is fixed. I didn't disable or weaken any test.

### Bugs

#### BUG-api-gateway-1:
`spring.profiles.active: dev` in the base config makes the DEV ONLY secret and dev CORS origins the default everywhere

- Severity: S1 Blocker (authentication bypass whenever `JWT_SECRET` is missing; P1 ACs fail)
- Affected: api-gateway, `src/main/resources/application.yaml` (startup and configuration; every protected route)
- Linked AC: AC-8.3, AC-6.5; D3 and D4 ("a dev-only value is committed only in the dev profile files", "the gateway
  refuses to start if the secret is missing"); guardrails G2/G4
- Commit tested: aa2234c + working tree T1 (line added 2026-10-12 09:53:24 by someone other than qa; not in
  backend-swe's handed-off state T0)
- Steps to reproduce:
    1.
    `cd api-gateway && ./gradlew test --tests 'org.apigateway.configuration.JwtSecretStartupTest' --tests 'org.apigateway.configuration.CorsAllowedOriginsTest' --tests 'org.apigateway.qa.QaConfigurationTest'`
    2. Or: `./gradlew bootJar`, then run
       `env -u JWT_SECRET -u SPRING_PROFILES_ACTIVE SERVER_PORT=18080 AUTH_SERVICE_URL=<stub> java -jar build/libs/api-gateway-0.0.1-SNAPSHOT.jar`.
    3. Mint an HS512 token from the `jwt.secret` value in `application-dev.yaml` with `sub` = `attacker-chosen-user`.
    4. Send `POST http://localhost:18080/api/orgs` with `Authorization: Bearer <that token>`.
- Expected: with no profile requested and no `JWT_SECRET`, startup fails and the message names `JWT_SECRET` (AC-8.3).
  With `CORS_ALLOWED_ORIGINS` unset and no dev profile, a preflight from `http://localhost:5173` is refused with 403 (
  AC-6.5). The base config activates no profile.
- Actual: the log shows `The following 1 profile is active: "dev"` and the gateway starts. The attacker token gets **200
  **, and the stub receives `X-User-Id: attacker-chosen-user`. A preflight from `http://localhost:5173` gets 200.
  Failing tests:
    - `org.apigateway.configuration.JwtSecretStartupTest` "JWT_SECRET not set":
      `[startup failure] Expecting actual not to be null`
    - `org.apigateway.configuration.CorsAllowedOriginsTest` row 4: `expected: 403 but was: 200`
    - `org.apigateway.qa.QaConfigurationTest#theBaseConfigurationActivatesNoProfile`: active profiles `[dev]`
- Evidence: diagnostic resolution of the configuration with an empty environment. Active profiles were `[dev]`.
  `jwt.secret` came from `Config resource 'class path resource [application-dev.yaml]'`. No system property or
  environment variable sets a profile. The same tests passed at 09:33 (run 1).
- Suggested owner: backend-swe (remove the line). The user or pm should confirm whether the change was intentional. For
  local runs, use `--spring.profiles.active=dev` or `SPRING_PROFILES_ACTIVE=dev` as the README already says.

#### BUG-api-gateway-2: Invitation tokens are logged in clear text when the accept path is percent-encoded or non-canonical

- Severity: S3 Major
- Affected: api-gateway, `filter/AccessLogFilter` (access log); `POST /api/auth/invitations/{token}/accept`
- Linked AC: AC-7.5 (variant beyond the scenario's data); D7 "the invitation token path segment is masked", "never
  logged: tokens"
- Commit tested: aa2234c + working tree (T0 and T1)
- Steps to reproduce:
    1. `cd api-gateway && ./gradlew test --tests 'org.apigateway.qa.QaLeakageTest'`
    2. Or, against the real stack:
       `curl --path-as-is -X POST -H 'Content-Type: application/json' -d '{}' -H 'X-Request-Id: qa-inv-enc' 'http://localhost:8080/api/auth/%69nvitations/inv-QAENCODED/accept'`
- Expected: the access entry shows `/api/auth/invitations/***/accept` (or otherwise masks the token), and the token
  appears nowhere in the gateway log.
- Actual: the masking regex works on the raw `getRequestURI()`. Five paths log the token in clear text:
    - `/api/auth/%69nvitations/<t>/accept`, `/api/%61uth/invitations/<t>/accept` and
      `/api/auth/inv%69tations/<t>/accept` are matched as the **public** accept operation and forwarded raw. The real
      auth-service processes them as `acceptInvitation`: the same `MethodArgumentNotValidException … argument [1]` as
      for the canonical path.
    - `/api/auth//invitations/<t>/accept` and `/api/auth/invitations/./<t>/accept` are rejected with 400 by the
      firewall, but the token is still logged. The double-slash form is a plausible frontend URL-joining bug.

  Real-stack log line:
  `requestId=qa-inv-enc method=POST path=/api/auth/%69nvitations/inv-QAENCODED/accept status=403 durationMs=5 service=auth-service`.
  The canonical path in the same run was masked.
- Evidence: `org.apigateway.qa.QaLeakageTest#invitationTokensInNonCanonicalPathsAreNotLogged` (5 rows fail). For
  example:
  `[log lines containing the invitation token inv-QaEncodedSecret1] Expecting empty but was: ["… org.apigateway.access : requestId=qa-mask-1 method=POST path=/api/auth/%69nvitations/inv-QaEncodedSecret1/accept …"]`.
  The `/./` row logs `path=/api/auth/invitations/***/inv-QaRejectedSecret5/accept`.
- Suggested owner: backend-swe. A likely direction is to mask on the decoded, normalised path or use the matched route
  pattern. pm: add the rows from TF-4.

#### BUG-api-gateway-3: A GET whose downstream connection is dropped is sent twice (the HTTP client retries)

- Severity: S3 Major
- Affected: api-gateway, the downstream HTTP client (JDK `HttpClient` via Spring Cloud Gateway MVC); every route,
  idempotent methods
- Linked AC: AC-4.3 (variant); D6 "No retries: a failed or timed-out request is never replayed"; contract
  `info.description` "The gateway never retries a request"
- Commit tested: aa2234c + working tree
- Steps to reproduce:
    1. `cd api-gateway && ./gradlew test --tests 'org.apigateway.qa.QaDownstreamRobustnessTest'`. The report-service
       stub accepts `GET /api/reports/42` and closes the connection without answering.
- Expected: the client gets 502 and the stub receives exactly 1 request.
- Actual: the client gets 502, but the stub records **2** `GET /api/reports/42` requests. A POST in the same situation
  is sent once, and the spec's POST + timeout scenario passes. backend-swe's AC-4.5 test uses the same stub behaviour (
  GET, connection closed before any answer), but it doesn't assert the request count, so it would not notice.
- Evidence: `QaDownstreamRobustnessTest#aDroppedGetIsNotRetried`:
  `[requests received by report-service] Expected size: 1 but was: 2`.
- Suggested owner: backend-swe (verify per P8 how to disable the JDK client's retry of idempotent requests, or document
  a decision). pm: TF-2.

#### BUG-api-gateway-4: A downstream failure after the response headers arrive is answered 500 "Unexpected gateway error" with an ERROR stack trace

- Severity: S3 Major
- Affected: api-gateway, `exception/GatewayExceptionHandler` and `exception/DownstreamFailure` (the body-copy phase);
  any route
- Linked AC: AC-4.2 and AC-4.5 (variants); D6: 502 for "connection dropped / invalid response", 504 for timeouts, and
  500 reserved for "unexpected failure inside the gateway"
- Commit tested: aa2234c + working tree
- Steps to reproduce:
    1. `./gradlew test --tests 'org.apigateway.qa.QaDownstreamRobustnessTest'` (response timeout 2 s, raw stub).
    2. Body cut: the stub sends `HTTP/1.1 200`, `Content-Length: 100` and 11 bytes, then closes.
    3. Slow body: the stub sends 200 headers at once, then 4 chunks 1 s apart.
- Expected: a 502 (body cut) or 504/502 (slow body) Problem. Not 500.
- Actual: **500** `{"detail":"Unexpected gateway error.",…}` in both cases. The log shows
  `ERROR … GatewayExceptionHandler : Unexpected gateway error` followed by
  `java.io.IOException: closed at …ResponseSubscribers$HttpResponseInputStream.read … AbstractProxyExchange.copyResponseBody`.
  The exploratory run with a 5 s stream and the 2 s timeout took 2133 ms, so the response timeout bounds the **whole
  exchange including the body**. That is backend-swe's Q3. For bodies larger than the servlet buffer, a committed 200
  with a truncated body is likely (inferred, not tested).
- Evidence: `QaDownstreamRobustnessTest#aBodyCutOffMidWayIsNotSuccess` (`expected: 502 but was: 500`),
  `#aSlowBodyIsNotAnInternalError` (`Expecting actual: 500 to be in: [502, 504]`).
- Suggested owner: backend-swe (classify body-phase I/O failures per D6). pm: decide on Q3 (is a total-exchange cap of
  10 s acceptable?) and TF-3.

#### BUG-api-gateway-5: Form-encoded request bodies are rewritten; on PUT, PATCH and DELETE the body is moved into the query string and emptied

- Severity: S3 Major
- Affected: api-gateway, request forwarding (Spring `FormContentFilter` plus Spring Cloud Gateway MVC form handling);
  every route
- Linked AC: AC-1.3 (variant); D2 "no rewriting"
- Commit tested: aa2234c + working tree
- Steps to reproduce:
    1. `./gradlew test --tests 'org.apigateway.qa.QaRequestFidelityTest'`
    2. Or:
       `curl -X PATCH -H 'Authorization: Bearer <valid>' -d '{"name":"Acme QA"}' 'http://localhost:8080/api/orgs/3b9d5c1e?notify=false&page=2'`.
       This is the AC-1.3 request sent with curl's default `-d` content type, `application/x-www-form-urlencoded`.
- Expected: the stub receives query `notify=false&page=2` and the original body.
- Actual:
    - The curl case reached the stub as
      `PATCH /api/orgs/3b9d5c1e?notify=false&%7B%22name%22%3A%22Acme%20QA%22%7D&page=2` with an empty body.
    - `PUT`/`PATCH`/`DELETE` with `name=Acme+QA` arrived with query `notify=false&page=2&name=Acme%20QA` and an empty
      body (`Content-Length: 0`).
    - A POST form body is re-serialised: `email=ana%40example.com&password=Secret123!` with `?x=1` arrived as body
      `&email=ana%40example.com&password=Secret123%21`.

  Form values, possibly credentials, end up in downstream URLs and downstream access logs. Multipart bodies and JSON
  bodies are unaffected.
- Evidence: `QaRequestFidelityTest#formEncodedBodiesStayInTheBody` (PATCH/PUT/DELETE:
  `expected: "notify=false&page=2" but was: "notify=false&page=2&name=Acme%20QA"`) and
  `#theAc13RequestWithAFormContentTypeKeepsItsBody` (
  `but was: "notify=false&%7B%22name%22%3A%22Acme%20QA%22%7D&page=2"`).
- Suggested owner: backend-swe. pm: TF-1 (are non-JSON bodies in scope of AC-1.3? If yes, this becomes a P0 failure).

#### BUG-api-gateway-6:
`/error` and actuator health sub-paths don't answer the D2/D6 404 Problem when called with a valid token

- Severity: S4 Minor
- Affected: api-gateway, `GET /error` (Spring Boot's error controller) and
  `GET /actuator/health/{liveness,readiness,ping}`
- Linked AC: AC-1.5 (variant); D2 "any other path gets … 404 with [a valid token]"; D6 404 Problem
- Commit tested: aa2234c + working tree
- Steps to reproduce:
    1. `./gradlew test --tests 'org.apigateway.qa.QaUnknownPathsTest'`
    2. Or: `curl -H 'Authorization: Bearer <valid>' http://localhost:8080/error`
- Expected: 404 `application/problem+json` with `requestId`.
- Actual:
    - `/error` returns **500** with Boot's default body `{"timestamp":"…","status":999,"error":"None","path":"/error"}`,
      which is not a Problem and has no `requestId`.
    - `/actuator/health/liveness`, `/readiness` and `/ping` return 404 with an **empty body** (`Content-Length: 0`).

  Without a token, all of them correctly get 401.
- Evidence: `QaUnknownPathsTest#theErrorPathIsAnUnroutedPath` (`expected: 404 but was: 500`),
  `#healthSubPathsAre404Problems` (3 rows: `Content-Type expected "application/problem+json" but was null`).
- Suggested owner: backend-swe. pm: TF-5.

#### BUG-api-gateway-7: An invalid downstream status code is passed to the client instead of 502

- Severity: S4 Minor
- Affected: api-gateway, response handling; any route
- Linked AC: AC-4.5 (variant); D6 "the response from the service was invalid → 502"; RFC 9110 §15 (codes outside 100–599
  are invalid)
- Commit tested: aa2234c + working tree
- Steps to reproduce:
    1. `./gradlew test --tests 'org.apigateway.qa.QaDownstreamRobustnessTest'`. The stub answers `HTTP/1.1 999 Weird`.
       In the exploratory run it answered `HTTP/1.1 999999 Weird`.
- Expected: 502 Problem.
- Actual: the client gets `HTTP/1.1 999` with an empty body, and the access log shows `status=999`.
- Evidence: `QaDownstreamRobustnessTest#anInvalidStatusCodeIs502` (`expected: 502 but was: 999`).
- Suggested owner: backend-swe.

#### BUG-api-gateway-8: Requests and responses are normalised in transit (the query is re-encoded,
`;charset=UTF-8` is appended, and more)

- Severity: S4 Minor
- Affected: api-gateway, request and response forwarding; every route
- Linked AC: AC-1.3 title ("forwarded without rewriting"), AC-1.4 and D6 ("passed through unchanged: status, headers and
  body")
- Commit tested: aa2234c + working tree
- Steps to reproduce:
    1. `./gradlew test --tests 'org.apigateway.qa.QaRequestFidelityTest'`.
    2. Exploratory: `curl -H 'Authorization: Bearer <valid>' 'http://localhost:8080/api/scenarios/1?q=/x/y'` and
       similar.
- Expected: the raw query and the request headers reach the service as sent.
- Actual:
    - Query strings are decoded, re-encoded **and re-ordered**: `filter=a,b&next=/x/y&at=a:b@c&flag` arrives as
      `next=%2Fx%2Fy&filter=a%2Cb&at=a%3Ab%40c&flag=`. Also `q=a+b` → `q=a%20b` and `q=%7E~` → `q=~~`. A valueless
      `flag` becomes `flag=`.
    - `Content-Type: application/json` arrives as `application/json;charset=UTF-8`.
    - Observed, not asserted:
        - `Content-Length` is replaced by `Transfer-Encoding: chunked`.
        - The JDK client adds `Accept-Encoding: gzip, deflate` and h2c upgrade headers (
          `Connection: Upgrade, HTTP2-Settings`, `Upgrade: h2c`).
        - gzip downstream responses are decoded and `Content-Encoding` is dropped.
        - Spring Security adds `Cache-Control: no-cache, no-store…`, `Pragma`, `Expires`, `X-Content-Type-Options`,
          `X-Frame-Options: DENY` and `X-XSS-Protection: 0` to responses that lack them. With the real auth-service
          these are not duplicated.

  The values are semantically equivalent for Spring services. Signed URLs, or servers that reject chunked requests or
  h2c upgrades, would break.
- Evidence: `QaRequestFidelityTest#theRawQueryStringIsForwardedUnchanged` (
  `expected: "filter=a,b&next=/x/y&at=a:b@c&flag" but was: "next=%2Fx%2Fy&filter=a%2Cb&at=a%3Ab%40c&flag="`),
  `#theContentTypeHeaderIsForwardedUnchanged` (`["application/json;charset=UTF-8"]` instead of `["application/json"]`).
- Suggested owner: pm first (TF-1: decide byte-for-byte or semantic). Then backend-swe.

#### BUG-api-gateway-9: Errors outside the D6 table have inconsistent shapes, and Tomcat-level rejections get no request ID or log entry

- Severity: S4 Minor
- Affected: api-gateway, edge error handling
- Linked AC: unspecified (D6 table and contract `Problem.status` list only 401/403/404/500/502/503/504)
- Commit tested: aa2234c + working tree
- Steps to reproduce: `curl --path-as-is` against the gateway with:
    1. `POST /api/auth/./login`
    2. `TRACE /api/orgs`
    3. `POST /actuator/health` with a valid token
    4. `POST /api/auth/login%2F..%2Forgs`
    5. `CONNECT /api/orgs`
- Expected: undefined. Proposal: Problem Details with `requestId` for every gateway-generated error.
- Actual:
    1. 400 with Boot's default JSON `{"timestamp","status":400,"error":"Bad Request","path"}`. The `X-Request-Id` header
       is present, there is no `requestId` in the body, and there is one access entry.
    2. 405 with Boot JSON and no access entry.
    3. 405 `application/problem+json` but **without `type` and `requestId`**.
    4. Tomcat 400 HTML page, no `X-Request-Id`, no access log entry.
    5. Tomcat 501 HTML.
- Evidence: exploratory run 7 (see Suites run).
- Suggested owner: pm (TF-6), then backend-swe.

#### BUG-api-gateway-10: A downstream `X-Request-Id` produces two, possibly conflicting, `X-Request-Id` headers

- Severity: S4 Minor
- Affected: api-gateway, response header handling
- Linked AC: unspecified (AC-7.1/D7 "returned on every response"; contract `XRequestId` "equals requestId"); backend-swe
  Q4
- Commit tested: aa2234c + working tree
- Steps to reproduce:
    1. Configure a stub that answers with `X-Request-Id: downstream-echo`.
    2.
    `curl -D - -H 'Authorization: Bearer <valid>' -H 'X-Request-Id: client-rid' http://localhost:8080/api/auth/echo-reqid`.
- Expected: exactly one `X-Request-Id`, carrying the gateway's value.
- Actual: the response carries both `X-Request-Id: client-rid` and `x-request-id: downstream-echo`. A browser's `fetch`
  would read `client-rid, downstream-echo`, which doesn't match the contract's pattern.
- Evidence: exploratory run 7. backend-swe's AC-7.1 and AC-7.3 tests use `firstValue` and would not catch this.
- Suggested owner: pm (TF-7: should the gateway drop downstream `X-Request-Id` as it drops CORS headers?), then
  backend-swe.

#### BUG-api-gateway-11: Several `Authorization` headers: the first is validated and all of them are forwarded

- Severity: S4 Minor
- Affected: api-gateway, `security/JwtAuthenticationFilter`; protected routes
- Linked AC: unspecified (D4); RFC 9110 doesn't allow repeating `Authorization`
- Commit tested: aa2234c + working tree
- Steps to reproduce:
    1.
    `curl -X POST -H 'Authorization: Bearer <valid>' -H 'Authorization: Bearer <expired>' http://localhost:8080/api/orgs`
- Expected: undefined. Proposal: reject a request with more than one `Authorization` header with 401.
- Actual: 200. The service receives **both** headers, and `X-User-Id` comes from the first. With the order reversed, the
  gateway returns 401. A service that reads the last value or joins the values would authenticate a different credential
  from the one the gateway checked. Related observation: the scheme is matched case-insensitively (`bearer …` is
  accepted, per RFC 6750), but auth-service only accepts `Bearer ` exactly. A lower-case client is authenticated at the
  edge and anonymous at the service.
- Evidence: exploratory run 7.
- Suggested owner: pm (TF-8), then backend-swe.

#### BUG-api-gateway-12: A signed token whose `sub` contains CR/LF causes a 500 and a forged access log line

- Severity: S4 Minor (requires a token signed with the shared secret)
- Affected: api-gateway, `filter/IdentityHeaderFilter` and `filter/AccessLogFilter`
- Linked AC: unspecified; guardrail P6 ("validate every untrusted header … `X-User-Id` before using or forwarding it");
  D7 log-injection intent
- Commit tested: aa2234c + working tree
- Steps to reproduce:
    1. Mint an HS512 token with the shared secret and `sub` = `abc\r\nX-Injected: 1`.
    2. `POST /api/orgs` with that token and `X-Request-Id: crlf-sub-1`.
- Expected: 401 (sub not a valid identity) or a sanitised identity; no log injection.
- Actual: 500 "Unexpected gateway error". The JDK client refuses the header (
  `IllegalArgumentException: invalid header value`), so nothing is injected downstream. The access log shows
  `… service=auth-service userId=abc` followed by a separate line `X-Injected: 1`. The ERROR log also prints the raw
  header value.
- Evidence: exploratory run 7.
- Suggested owner: backend-swe.

#### BUG-api-gateway-13: The contract documents `application/json` for health; the gateway answers
`application/vnd.spring-boot.actuator.v3+json` by default

- Severity: S4 Minor
- Affected: `docs/api/api-gateway.openapi.yaml#/paths/~1actuator~1health/get/responses/200`
- Linked AC: contract (AC-8.4)
- Commit tested: aa2234c + working tree
- Steps to reproduce:
    1. `curl -D - http://localhost:8080/actuator/health` (curl sends `Accept: */*`)
- Expected: `Content-Type: application/json` per the contract.
- Actual: `Content-Type: application/vnd.spring-boot.actuator.v3+json`. With `Accept: application/json`, the response is
  `application/json`. The body is correct.
- Evidence: exploratory check on the jar.
- Suggested owner: backend-swe (document both media types in the contract, or force `application/json`).

### Weaknesses found in backend-swe's tests

I didn't edit any of these tests. The findings are for backend-swe.

1. **AC-7.5** (`AccessLogFilterTest#secretsNeverAppearInGatewayLogs`) covers only the canonical invitation path, so it
   misses BUG-2. Its `maskedPath` unit test checks `//api/…` but not encoded or `/./` forms.
2. **AC-4.5** (`GatewayErrorResponseTest#aServiceThatDropsTheConnectionProduces502`) doesn't assert the request count.
   QA's equivalent test (same stub behaviour) shows the GET is sent twice (BUG-3). AC-4.3's test covers only POST +
   timeout.
3. **AC-1.3** sets `Content-Type: application/json`, uses a query with only unreserved characters, and doesn't assert
   the forwarded `Content-Type`. That is how BUG-5 and BUG-8 went unnoticed.
4. **AC-7.1 and AC-7.3** read `firstValue("X-Request-Id")`, so duplicate headers (BUG-10) would pass. AC-7.2 correctly
   uses `allValues`.
5. **AC-5.2** is asserted only on the 503 body. QA added 401, 403, 404 and 502; they pass.
6. **AC-1.5** has no row for `/error`, and `ActuatorExposureTest#noOtherHealthOrDiscoveryResourceIsExposed` asserts
   status 404 only, missing the empty bodies (BUG-6).
7. **AC-8.4** doesn't check `Content-Type` against the contract (BUG-13).
8. **AC-8.3** tag on `JwtSecretDecoderTest` (2 tests): these are unit tests of the decoder and can't prove "startup
   fails" or "the log doesn't contain the value". `JwtSecretStartupTest` does prove it.
9. **AC-2.3**: the HS256 alg-confusion case exists only as an untagged unit test (`JwtTokenValidatorTest`), not end to
   end through the gateway. QA added HS256, HS384, `alg NONE` and more; they pass.
10. **AC-8.2** row 1 checks the resolved `server.port`, not a socket bound to 8080. backend-swe documented this; the
    manual AC-8.6 run covers it. Acceptable.
11. **Claim check.**
    - "117 tests tagged" is inaccurate. 117 is the total number of test executions. AC tags sit on 47 test methods (92
      executions) across **38 distinct ACs**, so the "38 ACs automated" claim holds.
    - "AC-1.6, AC-2.8, AC-8.6 passed manually": reproduced, all pass.
    - "AC-2.8 is 403 because of Known issues 2+3": confirmed. auth-service logs
      `NullPointerException: Cannot invoke "…UserRepository.findById(Object)" because "this.userRepository" is null at OrganizationController.createOrganization(OrganizationController.java:40)`,
      and calling 8081 directly gives the same 403 with an empty body.
    - "p95 overhead about 3–4 ms": consistent with my 2.5–2.9 ms.
12. **Positive finding.** `JwtSecretStartupTest` and `CorsAllowedOriginsTest` are what caught BUG-1. These tests are
    valuable.

### Pre-existing failures (baseline, not caused by this feature)

- `auth-service ./gradlew build`: `AuthServiceApplicationTests > initializationError`, "Unable to find a
  @SpringBootConfiguration" (test package `org.authservice` vs `com.example.authservice`). Known issue 4; unchanged by
  T3.
- auth-service Known issue 1 (old non-Base64 secret): fixed by T3. With `dev`, login works through the gateway.
- auth-service Known issue 2 (`OrganizationController` NPE): observed in AC-2.8 (500 internally).
- auth-service Known issue 3 (`/error` not permitted): it turns the NPE into the 403 with an empty body that the gateway
  passes through unchanged. Not a gateway bug.
- auth-service Known issue 5 (`/actuator/**`, `/h2-console/**` open on 8081): still reachable directly. Through the
  gateway: anonymous → 401, valid token → 404 (verified on the real stack).
- scenario-service and report-service builds were not run. They are outside this feature, and their baseline failure ("
  Failed to determine a suitable driver class") is documented.

### Answers to backend-swe's open questions

1. **`service` in the access log on 502/503/504.** Observed `service=scenario-service` on 502 (`ProtocolException`) and
   `service=report-service` on 503 (`ConnectException`), and also on the 500 from BUG-4. 504 wasn't observed directly,
   but it takes the same path: the route attribute is set before the call. D7 says "absent if the request wasn't
   forwarded", which is ambiguous for a refused connection. Recommendation for pm: define `service` as "the route's
   target service whenever a route matched", because operators need it most on 5xx. Then amend D7.
2. **Kubernetes probe groups and the `/actuator` links page left off.** With a valid token, `/actuator` and `/actuator/`
   return the 404 Problem (links page off). `/actuator/health/liveness`, `/readiness` and `/ping` return 404, but with
   an empty body (BUG-6). Without a token, everything except `GET /actuator/health` returns 401. This matches D7's "only
   health, no details". Recommendation: keep probes off until Helm wiring is specced. pm should confirm.
3. **The JDK client's response timeout also cuts streaming bodies.** Confirmed: the timeout bounds the whole exchange
   including the body, at 2133 ms with a 2 s setting. The client gets a **500** and the log an ERROR stack trace (
   BUG-4). pm decides whether a total cap is acceptable for phase 1 (streaming is out of scope). It should at least map
   to 504 or 502 and log at WARN. Per-route timeouts will be needed for large report downloads.
4. **Duplicate `X-Request-Id` when a downstream echoes it.** Confirmed: two headers, and they can conflict (BUG-10).
   Recommendation: the gateway drops the downstream `X-Request-Id`, as it already does for downstream CORS headers, and
   C3 says services must not set it on responses.

### Gaps and risks

- **The tree changed mid-run (BUG-1).** Everything after 09:53 ran on T1, and the manual checks ran on T0. The two
  states differ only in default profile activation, which doesn't change the explicit `dev` runs. Re-run
  `./gradlew clean build` after BUG-1 is resolved.
- AC-1.6, AC-2.8 and AC-8.6 are manual only (D8). There is no cross-service harness; Docker and Testcontainers would
  need approval and are out of scope.
- The connect timeout to a black-holed host is untested (A11).
- Large bodies failing mid-stream (a committed 200 with a truncated body) are inferred, not tested (BUG-4).
- **Absolute `Location` URLs.** Downstream absolute URLs pass through unchanged. Because the gateway replaces `Host` and
  strips client `X-Forwarded-*`, a service that builds absolute URLs from the request will emit internal hosts such as
  `http://localhost:8081/…`. auth-service doesn't do this today; it's a risk for future services.
- **h2c upgrade headers.** The JDK client sends them on every downstream request. Harmless for Tomcat and Python, but
  some proxies or servers reject them.
- Load and concurrency weren't tested beyond the NFR's sequential 200 requests.
- QA tests that reproduce bugs keep the build red (20 QA failures plus 2 backend failures) until the bugs are fixed or
  pm rules them out of scope. If pm decides a behaviour is acceptable, qa will update the corresponding test and record
  why.
