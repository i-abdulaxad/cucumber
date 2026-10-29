# report-service

Test runs and their results: CI uploads of Cucumber JSON with per-project upload tokens, manual runs planned from
scenario-service features, and run summaries (spec: `docs/specs/report-service.md`, phase 1). The API contract is
`docs/api/report-service.openapi.yaml`.

**Status (batch A, T2):** build, settings, schema, entities and test support are in place. The HTTP API (security,
tokens, uploads, runs) comes with tasks T3 to T10; until then the service starts but serves no endpoint of the
contract.

## Running locally

Requirements: JDK 17 (the Gradle toolchain), PostgreSQL 17 with the database `report_service` (see "Dev database"
below). Later tasks also need auth-service on `http://localhost:8081` and scenario-service on `http://localhost:8082`.

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
