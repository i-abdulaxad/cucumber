# report-service

Test runs and their results: CI uploads of Cucumber JSON with per-project upload tokens, manual runs planned from
scenario-service features, and run summaries (spec: `docs/specs/report-service.md`, phase 1). The API contract is
`docs/api/report-service.openapi.yaml`.

**Status (review point D, 2026-11-02):** every operation of contract 1.0.2 is served, also through the gateway
(routes GW1 and GW2), pending qa verification.

## API overview

All paths are project-scoped and need a user JWT, except the CI upload, which needs an upload token. Through the
gateway use `http://localhost:8080`; directly, port 8083.

| Operation | Method and path | Minimum project role |
|---|---|---|
| Upload tokens | `POST`, `GET /api/projects/{projectId}/upload-tokens`; `POST .../upload-tokens/{tokenId}/revoke` | `ADMIN` |
| CI upload | `POST /api/ci/runs?name=&environment=&build=&branch=&commit=&ciUrl=` with `Authorization: Bearer <upload token>` | an active token of the project |
| Plan a manual run | `POST /api/projects/{projectId}/runs` | `MEMBER` |
| Record a result | `PUT /api/projects/{projectId}/runs/{runId}/results/{resultId}` | `TESTER` |
| Complete / cancel | `POST .../runs/{runId}/complete` (`TESTER`), `POST .../runs/{runId}/cancel` (`MEMBER`) | see left |
| Read | `GET .../runs?page=&size=`, `GET .../runs/{runId}`, `GET .../runs/{runId}/results?status=&page=&size=`, `GET .../runs/{runId}/results/{resultId}` | `READER` |

- An archived project is read-only: writes are 409 `PROJECT_ARCHIVED`, except token list and revoke.
- Path ids are lower-case canonical UUIDs. Errors are RFC 9457 Problems with a stable `code` (contract C1).

## Uploading from CI

1. A project `ADMIN` creates a token (`POST /api/projects/{projectId}/upload-tokens` with `{"name":"GitHub Actions"}`)
   and stores the `token` from the answer as a CI secret; it is shown only once (only its SHA-256 is stored). At most
   10 tokens are active per project; revoke is final.
2. CI posts Cucumber's `json` formatter output (Cucumber-JVM 7 or Cucumber-JS 13):

   ```sh
   curl --fail-with-body -H "Authorization: Bearer $REPORT_UPLOAD_TOKEN" -H 'Content-Type: application/json' \
     -H 'Expect: 100-continue' --data-binary @target/cucumber.json \
     'http://localhost:8080/api/ci/runs?name=Nightly&environment=staging&build=1.4.2%2B517&branch=main&commit=9fceb02'
   ```

   The answer is 201 with the completed run and its summary.
- **Limits:** `application/json` in UTF-8 without content coding (otherwise 415); at most 20 MiB (otherwise 413; a
  larger `Content-Length` is answered before the body is read, so send `Expect: 100-continue`, as curl does for large
  bodies); at most 5,000 test cases. Unknown JSON fields are ignored; what is read is checked strictly (422
  `CUCUMBER_JSON_INVALID` with JSONPath pointers). Embeddings are not stored in phase 1.
- **Measured** (2026-11-02, local, through the gateway): a 2-test-case upload p95 41 ms; a 15.8 MB upload with
  5,000 test cases 861 ms (the stop limit is 5 s; the gateway's timeout is 10 s). Planning a run of one feature (3
  test cases, 3 scenario-service calls) p95 147 ms.

## Calls to other services

- **auth-service C5** (`/internal/projects/{projectId}/access`) for every user path, with the caller's token, and
  **C7** (`/internal/service/projects/{projectId}`) for the CI upload, with `X-Service-Key` (`INTERNAL_SERVICE_KEY`,
  which auth-service must share). Both: connect 1 s, read 2 s, no cache, fail closed with 503.
- **scenario-service** (public reads, contract 1.0.4) for planning: the feature list, each feature's test cases, the
  list again; connect 1 s, read 5 s. A feature saved meanwhile is 409 `FEATURE_CHANGED`.
- Every call is one attempt on one connection: the JDK HTTP client's retry is turned off JVM-wide at start-up (RE6),
  and no client follows redirects. A valid `X-Request-Id` is forwarded on every call.

## Logging

One outcome line per service call (the logging aspect), with `requestId`, `userId` on user paths and `tokenId` on
uploads; one INFO line per upload (run id, test-case count, duration). Never logged: tokens or their prefixes and
hashes, the service key, `Authorization`, Cucumber JSON content, step texts, error messages, comments or query values.
Even at root DEBUG, `application.yaml` keeps Hibernate's entity dumps and the framework loggers that print URLs,
bodies or headers at INFO (RE8, RE9).

## Running locally

Requirements: JDK 17 (the Gradle toolchain), PostgreSQL 17 with the database `report_service` (see "Dev database"
below), auth-service on `http://localhost:8081` (access check and project status) and scenario-service on
`http://localhost:8082` (planning).

One-time setup: copy `.env.example` to `.env` in the repository root and fill in the values (the file shows how to
generate them). `.env` is gitignored; never commit it. report-service reads three keys from it:

- `JWT_SECRET`: the HS512 secret shared with auth-service, scenario-service and the gateway (standard Base64 of at
  least 64 bytes);
- `REPORT_DB_PASSWORD`: the password of the `report_service` database role;
- `INTERNAL_SERVICE_KEY`: the key report-service sends to auth-service's project status endpoint (standard Base64 of
  at least 32 bytes); auth-service must have the same value.

With the `dev` profile (local URL and username, the three keys from `.env`, and SQL logged through
`org.hibernate.SQL` with request and user ids and `?` placeholders only):

```sh
cd report-service
./gradlew bootRun --args='--spring.profiles.active=dev'
```

- Port: **8083** (`server.port`). Normal clients call it through the API gateway on port 8080; direct calls on 8083
  are for local development only.
- Never use the `dev` profile outside a developer machine.

Without the `dev` profile, set all five variables yourself, for example:

```sh
REPORT_DB_URL='jdbc:postgresql://localhost:5432/report_service' REPORT_DB_USERNAME='report_service' \
  REPORT_DB_PASSWORD='<password>' JWT_SECRET='<standard Base64 of at least 64 random bytes>' \
  INTERNAL_SERVICE_KEY='<standard Base64 of at least 32 random bytes>' ./gradlew bootRun
```

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `spring.datasource.url` (`REPORT_DB_URL`) | none (the `dev` profile sets one) | JDBC URL of the report_service database, without credentials |
| `spring.datasource.username` (`REPORT_DB_USERNAME`) | none (the `dev` profile sets one) | Database role |
| `spring.datasource.password` (`REPORT_DB_PASSWORD`) | none (`.env` with the `dev` profile) | Database password; never logged |
| `app.jwtSecret` (`JWT_SECRET`) | none (`.env` with the `dev` profile) | Shared HS512 secret, standard Base64 of at least 64 bytes; never logged |
| `app.internal-service-key` (`INTERNAL_SERVICE_KEY`) | none (`.env` with the `dev` profile) | Key for auth-service's project status (C7), standard Base64 of at least 32 bytes; never logged |
| `app.auth-service.base-url` (`AUTH_SERVICE_URL`) | `http://localhost:8081` | auth-service, called directly (not through the gateway) for the access check (C5) and project status (C7); connect 1 s, read 2 s |
| `app.scenario-service.base-url` (`SCENARIO_SERVICE_URL`) | `http://localhost:8082` | scenario-service, called directly for manual-run planning; connect 1 s, read 5 s |
| `app.logging.enabled`, `app.logging.slow-call-threshold` | `true`, `500ms` | The service logging aspect (D17) |

- A missing or blank `REPORT_DB_*` value stops start-up with a message that names the variable and never shows a
  value. `REPORT_DB_*` values must not contain `${`: Spring would treat it as a placeholder.
- `JWT_SECRET` and `INTERNAL_SERVICE_KEY` must be set and be standard Base64 of at least 64 and 32 bytes; otherwise
  start-up fails with a message that names the variable and never shows the value.
- No setting value is ever logged, at any level, including a failed start-up.

## Dev database

The `dev` profile expects PostgreSQL 17 on `localhost:5432` with the database and role `report_service`. It lives in
the same `cucumber-postgres` container as auth-service's database (see `auth-service/README.md`, "Running locally
with PostgreSQL"), whose superuser is `auth_service`. One-time setup:

1. Open `psql` in the container:

   ```sh
   docker exec -it cucumber-postgres psql -U auth_service -d postgres
   ```

2. Create the role and set its password at the prompt (type the `REPORT_DB_PASSWORD` value from `.env`, so it never
   appears in the shell history or a process list):

   ```sql
   CREATE ROLE report_service LOGIN;
   \password report_service
   ```

3. Create the database with UTF-8 and the builtin `C.UTF-8` locale, so lists sort by code point on every machine:

   ```sql
   CREATE DATABASE report_service OWNER report_service TEMPLATE template0 ENCODING 'UTF8'
       LOCALE_PROVIDER builtin BUILTIN_LOCALE 'C.UTF-8';
   ```

4. Check it: `SELECT datlocprovider, datlocale FROM pg_database WHERE datname = 'report_service';` (expected: `b`,
   `C.UTF-8`).

Flyway creates the schema at the first start.

## Database and migrations

- **Flyway owns the schema.** Migrations are `src/main/resources/db/migration/V<n>__<snake_case_description>.sql`,
  applied once, in version order, at start-up. Hibernate only validates (`ddl-auto: validate`).
- **Append-only:** once a migration has passed review, it is never edited; a fix is a new version. No `R__` or Java
  migrations, no `IF [NOT] EXISTS`. Never run Flyway `clean`, `repair` or `baseline`.
- **Rules:** the "Database pattern for every service" in `docs/specs/auth-service-persistence.md`, with this
  service's decisions in `docs/specs/report-service.md` D14. V1 creates `environments`, `builds`, `upload_tokens`,
  `test_runs`, `test_case_results` and `step_results`; phase 1 has no `attachments` table. Every foreign key restricts
  deletes, and no column has a database default.
- **Writes** take a transaction-scoped advisory lock keyed by the project id first (`TestRunRepository.lockProject`).
- **Connections:** every connection waits at most 5 s for a lock (`lock_timeout`; tests use 1 s), and database error
  details are kept out of exception messages (`logServerErrorDetail=false`).
- The ERD is `docs/erd/cucumber.dbml`, group `report_service`.

## Tests

```sh
cd report-service
./gradlew build
./gradlew test --tests 'org.reportservice.repository.SchemaTest'
```

Tests use TEST-ONLY secrets defined in test code (`support/TestSettings`) and a PostgreSQL 17 Testcontainers
container (one per test run, shared by every test). **Docker must run for `./gradlew build`**; without it the build
fails, and nothing is skipped. Tests need no environment variables, no local database and no `.env`; no test
activates the `dev` profile. Start-up tests bind to `127.0.0.1` on a free port.
