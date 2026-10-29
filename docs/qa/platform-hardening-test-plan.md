# Test plan: platform-hardening (review point B, whole batch)

Spec: `docs/specs/platform-hardening.md` (D1–D11, E1–E15, US-1 to US-6). Standards: `docs/engineering/standards.md`.
Commit under test: `566fc1b` plus working tree (T7, T8, the auth-service test-JVM `server.address` line, gateway contract 0.3.1). qa, 2026-10-29.

## Scope and approach

- **Every AC** has an automated test, either backend-swe's (audited against the Gherkin) or qa's, plus the extra rows below. D3/D4/D7 are proven on real servers (D9). MockMvc results don't count for them.
- **Mutation check:** 25 targeted mutants of the changed code, on `/tmp` copies: the error controllers, instance and `requestId`, the ERROR-dispatch permit, the 503 mapping including E5, the single C5 attempt (E7, E8), the E13 flag and the E14 valve, and the D5 texts.
- **Adversarial (new qa classes):**
  - more firewall forms (`;jsessionid`, `%2e%2e`, `%252e`, `%3B`, `..;`, `//`, `/./`);
  - `Accept` negotiation;
  - client `X-Request-Id` (valid and invalid);
  - TRACE with a token, a cookie and a custom header;
  - other unsupported methods;
  - direct `/error` with other methods, a query, an expired token, `;x=1`, and HEAD;
  - pool exhaustion under a 12-request burst;
  - a database that refuses new connections;
  - committed aborts: an invalid chunk, a non-streaming stall, complete large bodies, and concurrent mixed downloads;
  - an extra C5 row: reset before the request is read.
- **Tolerances re-checked:** `QaAccessCheckFailureMatrixTest` (the BUG-4 exemption removed); `QaUnknownPathsTest` (`theErrorPathIsAnUnroutedPath` re-enabled, health sub-paths re-pointed to FU-5); `QaStrictInputTest` (outdated BUG-5 comment); the MockMvc range tolerances (kept, because MockMvc has no error dispatch).
- **Runs:**
  - a full `clean build` of all three services;
  - the concurrency classes and the T2 pool tests 3 times;
  - one manual G10a run through the gateway: throwaway `postgres:17`, no `dev`, throwaway secrets, own PIDs. It covers the D6 container rows, a firewall row and TRACE through the gateway and directly, a D7 database refusal, and E7 in a real `java -jar` start-up (one C5 connection).

## AC map

| AC | P | Automated (class#method) | qa extra |
|---|---|---|---|
| AC-1.1 | P0 | scenario `SingleAccessCheckAttemptTest#aTransportFailureIsOneAttempt` (4 rows), `OneAccessCheckAttemptSettingTest` | `QaAccessCheckFailureMatrixTest#transportFailuresFailClosed` (6 rows, count restored, new reset row); manual E7 start-up check |
| AC-1.2 | P1 | `SingleAccessCheckAttemptTest#aNormalAnswerIsOneRequest` | matrix redirect row (`exactly one call`) |
| AC-2.1 | P0 | auth `ErrorDispatchProblemTest#firewallRejectionsAreProblems` | `QaPlatformHardeningRealServerTest#moreFirewallFormsAreProblems`, `#theProblemIgnoresTheAcceptHeader`; manual |
| AC-2.2 | P0 | `ErrorDispatchProblemTest#traceIsAProblemThatEchoesNothing` | `QaPlatformHardeningRealServerTest#traceEchoesNothing`; manual |
| AC-2.3 | P0 | `ErrorDispatchProblemTest#aDirectErrorRequestIsAnUnroutedPath` | `#directErrorVariants` (6 rows) |
| AC-2.4 | P1 | `ErrorDispatchProblemTest#containerRejectionsStayAsAccepted` | manual (direct) |
| AC-2.5 | P1 | `ErrorDispatchProblemTest#existingProblemsAreUnchanged` | full suite |
| AC-3.1 | P0 | `ConnectionUnavailableTest#connectionUnavailableIsA503Problem` (5 rows) | `#aBurstWhileThePoolIsExhaustedIsAll503`, `#aDatabaseThatRefusesConnectionsIs503`; manual refusal |
| AC-3.2 | P1 | `ConnectionUnavailableTest#loggedOnceWithoutDetail` | burst: no token, password, email or stack trace |
| AC-3.3 | P1 | `ConnectionUnavailableTest#theServiceRecoversByItself` | recovery in both qa D7 tests, plus no leaked connection |
| AC-4.1 | P0 | scenario `ErrorDispatchProblemTest#firewallRejectionsAreProblems` | `QaRejectionsRealServerTest#moreFirewallFormsAreProblems`, `#theProblemIgnoresTheAcceptHeader` |
| AC-4.2 | P0 | `ErrorDispatchProblemTest#traceIsAProblemThatEchoesNothing` | `QaRejectionsRealServerTest#traceEchoesNothing` |
| AC-4.3 | P0 | `ErrorDispatchProblemTest#aDirectErrorRequestIsAnUnroutedPath` | `#directErrorVariants` (6 rows) |
| AC-4.4 | P1 | `ErrorDispatchProblemTest#containerRejectionsStayAsAccepted` | manual (direct) |
| AC-5.1 | P0 | gateway `GatewayErrorDispatchTest#firewallRejectionsAreD6Problems` | `QaGatewayRejectionsTest#moreFirewallFormsAreD6Problems` (asserts `instance`), `#theProblemIgnoresTheAcceptHeader`, `#requestIdsOnRejections`; manual |
| AC-5.2 | P0 | `GatewayErrorDispatchTest#anUnsupportedMethodIsAD6Problem` | `QaGatewayRejectionsTest#otherUnsupportedMethodsAreD6Problems` |
| AC-5.3 | P1 | `GatewayErrorDispatchTest#traceIsAProblemThatEchoesNothing` | `QaGatewayRejectionsTest#traceEchoesNothing` (4 paths); manual |
| AC-5.4 | P0 | `GatewayErrorDispatchTest#aDirectErrorRequestIsAnUnroutedPath` | `QaUnknownPathsTest#theErrorPathIsAnUnroutedPath` (re-enabled), `QaGatewayRejectionsTest#directErrorVariants`, `#headErrorIs404WithoutBody` |
| AC-5.5 | P1 | `GatewayErrorDispatchTest#containerRejectionsStayAsAccepted` | manual |
| AC-6.1 | P0 | `CommittedResponseAbortValveTest` (unit) | `QaCommittedResponseAbortTest#aFailureAfterCommitAbortsTheClientConnection` (3 rows), `QaCommittedResponseAbortAdversarialTest` (4 tests); manual chunked and length |
| AC-6.2 | P1 | none by backend-swe | `QaCommittedResponseAbortTest#aFailureAfterCommitIsLoggedOnceAtWarnWithTheStatusTheClientGot` (3 rows), adversarial tests |
| AC-6.3 | P0 | regression `DownstreamResponseFailureTest` (AC-4.6, AC-4.7) | `QaUncommittedFailureGuardTest#anUncommittedFailureIsStillThe502Problem` |

## Testability findings for pm

1. **E16 is cited by the main session but not recorded in the spec.** `auth-service/build.gradle` gained `systemProperty 'server.address', '127.0.0.1'` (test JVM). E15's text covers only `support/*` test helpers, and build.gradle isn't one.
2. **Contract contradiction (gateway 0.3.1).** Line 114 says "exactly one `X-Request-Id` on every response, including gateway-generated errors". Lines 32–33 and 335 say the TRACE 405 has none. The second statement matches what qa observed.
3. **D7 scope.** A connection that the database terminates while it's in use isn't "connection unavailable" by D7's definition. qa's automated refusal test waits out HikariCP's 500 ms alive-bypass window. In the manual run, a request sent right after the backends were terminated also got the 503: HikariCP validated the pooled connections, logged its own WARN for each dead connection, and then reported the refusal. No 500 was observed, so this needs no AC change.
