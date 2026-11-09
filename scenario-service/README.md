# scenario-service

Gherkin features, their versions, scenarios, test cases and tags (spec: `docs/specs/scenario-service.md`, phase 1).
The API contract is `docs/api/scenario-service.openapi.yaml`.

## API overview

The contract is `docs/api/scenario-service.openapi.yaml` (1.0.4). Every operation is project-scoped and needs a bearer
token; through the gateway the base URL is `http://localhost:8080`, directly `http://localhost:8082`.

| Operation                                   | Method and path                                                              | Minimum project role |
|---------------------------------------------|------------------------------------------------------------------------------|----------------------|
| List features (`?archived=true`, `?tag=@x`) | `GET /api/projects/{projectId}/features`                                     | `READER`             |
| Create a feature from Gherkin text          | `POST /api/projects/{projectId}/features`                                    | `MEMBER`             |
| View a feature (text and structure)         | `GET /api/projects/{projectId}/features/{featureId}`                         | `READER`             |
| Save a new version, or change the path      | `PUT /api/projects/{projectId}/features/{featureId}` (with `baseVersion`)    | `MEMBER`             |
| Archive, restore                            | `POST /api/projects/{projectId}/features/{featureId}/archive`, `.../restore` | `MEMBER`             |
| Version history, one version's text         | `GET .../features/{featureId}/versions`, `.../versions/{version}`            | `READER`             |
| Test cases of the current version           | `GET .../features/{featureId}/test-cases`                                    | `READER`             |
| Tag catalogue                               | `GET /api/projects/{projectId}/tags`                                         | `READER`             |

- Every error is an RFC 9457 Problem with a stable `code` (for example `GHERKIN_INVALID` with `parseErrors`,
  `VERSION_CONFLICT` with `currentVersion`, `FEATURE_PATH_TAKEN`). The order of checks is in the contract.
- The Gherkin text is the source of truth: it is stored exactly as sent, and the structure, tags and test cases are
  derived from the current version. Scenario and example-row ids stay stable across edits as the spec's D9 says.
- Paths are unique per project among active features, ignoring case as PostgreSQL's ICU root `lower()` does.

### Limits

| Limit                                                                                                                                                                                | Value                                     | Answer when exceeded                              |
|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------|---------------------------------------------------|
| `source` length                                                                                                                                                                      | 1,000,000 code points                     | 400 `VALIDATION_FAILED` (`source`)                |
| Elements per feature: Rules, Backgrounds, Scenarios, Outlines, Examples blocks, steps, example rows, and each tag on each of them (data-table cells and doc-string text don't count) | 10,000                                    | 400 `VALIDATION_FAILED` (`source`), after parsing |
| `path` length                                                                                                                                                                        | 1,024 code points                         | 400 `VALIDATION_FAILED` (`path`)                  |
| `message` length                                                                                                                                                                     | 500 code points (counted before trimming) | 400 `VALIDATION_FAILED` (`message`)               |
| `tag` filter                                                                                                                                                                         | one value, at most 1,000 code points      | 400 `VALIDATION_FAILED` (`tag`)                   |
| `# encoding:` pragma                                                                                                                                                                 | UTF-8 only                                | 422 `GHERKIN_INVALID`, one parse error at 1:1     |

Parsing and the element count happen before the database transaction; under the project's write lock only the
inserts run, as JDBC batches (`hibernate.jdbc.batch_size` and the driver's `reWriteBatchedInserts`). A 10,000-element
create or save takes about 1 s locally.

## Access check (D4)

Every request is checked against auth-service's `GET /internal/projects/{projectId}/access`, called directly at
`AUTH_SERVICE_URL` (never through the gateway), with the caller's `Authorization` header and the request's
`X-Request-Id`. There is **no caching**: a removed member or a changed role applies on the next request.

- 403 from auth-service (no access, unknown project) is 403 `FORBIDDEN`; 401 (for example a suspended user) is 401
  `UNAUTHENTICATED`.
- Anything else fails closed with **503 `ACCESS_CHECK_UNAVAILABLE`** and changes nothing: auth-service unreachable,
  slower than the 1 s connect / 2 s read timeouts, a 5xx or other status, an unreadable body, an unknown role or
  another project id. One WARN line names the failure type only (never a token or a URL). There is no retry.
- Writes on an archived project are 409 `PROJECT_ARCHIVED`; reads still work.
- The check runs before any database transaction, so no connection is held while auth-service answers.

## Running locally

Requirements: JDK 17 (the Gradle toolchain), PostgreSQL 17 with the database `scenario_service` (see "Dev database"
below), and auth-service running on `http://localhost:8081` for the access check.

One-time setup: copy `.env.example` to `.env` in the repository root and fill in the values (the file shows how to
generate them). `.env` is gitignored; never commit it. scenario-service reads two keys from it:

- `JWT_SECRET`: the HS512 secret shared with auth-service and the gateway (standard Base64 of at least 64 bytes);
- `SCENARIO_DB_PASSWORD`: the password of the `scenario_service` database role.

With the `dev` profile (local URL and username, the two keys from `.env`, and SQL logged through `org.hibernate.SQL`
with request and user ids and `?` placeholders only):

```sh
cd scenario-service
./gradlew bootRun --args='--spring.profiles.active=dev'
```

- Port: **8082** (`server.port`). Normal clients call it through the API gateway on port 8080; direct calls on 8082
  are for local development only.
- Never use the `dev` profile outside a developer machine.

Without the `dev` profile, set all four variables yourself, for example:

```sh
SCENARIO_DB_URL='jdbc:postgresql://localhost:5432/scenario_service' SCENARIO_DB_USERNAME='scenario_service' \
  SCENARIO_DB_PASSWORD='<password>' JWT_SECRET='<standard Base64 of at least 64 random bytes>' ./gradlew bootRun
```

## Settings

| Setting                                               | Default                              | Meaning                                                                      |
|-------------------------------------------------------|--------------------------------------|------------------------------------------------------------------------------|
| `spring.datasource.url` (`SCENARIO_DB_URL`)           | none (the `dev` profile sets one)    | JDBC URL of the scenario_service database, without credentials               |
| `spring.datasource.username` (`SCENARIO_DB_USERNAME`) | none (the `dev` profile sets one)    | Database role                                                                |
| `spring.datasource.password` (`SCENARIO_DB_PASSWORD`) | none (`.env` with the `dev` profile) | Database password; never logged                                              |
| `app.jwtSecret` (`JWT_SECRET`)                        | none (`.env` with the `dev` profile) | Shared HS512 secret, standard Base64 of at least 64 bytes; never logged      |
| `app.auth-service.base-url` (`AUTH_SERVICE_URL`)      | `http://localhost:8081`              | auth-service, called directly (not through the gateway) for the access check |

- A missing or blank `SCENARIO_DB_*` value stops start-up with a message that names the variable and never shows a
  value.
- `SCENARIO_DB_*` values must not contain `${`: Spring would treat it as a placeholder (FU-24).
- No setting value is ever logged, at any level, including a failed start-up.

## Dev database

The `dev` profile expects PostgreSQL 17 on `localhost:5432` with the database and role `scenario_service`. It lives
in the same `cucumber-postgres` container as auth-service's database (see `auth-service/README.md`, "Running locally
with PostgreSQL"), whose superuser is `auth_service`. One-time setup:

1. Open `psql` in the container:

   ```sh
   docker exec -it cucumber-postgres psql -U auth_service -d postgres
   ```

2. Create the role and set its password at the prompt (type the `SCENARIO_DB_PASSWORD` value from `.env`, so it never
   appears in the shell history or a process list):

   ```sql
   CREATE ROLE scenario_service LOGIN;
   \password scenario_service
   ```

3. Create the database with UTF-8 and the builtin `C.UTF-8` locale, so lists sort by code point on every machine:

   ```sql
   CREATE DATABASE scenario_service OWNER scenario_service TEMPLATE template0 ENCODING 'UTF8'
       LOCALE_PROVIDER builtin BUILTIN_LOCALE 'C.UTF-8';
   ```

4. Check it: `SELECT datlocprovider, datlocale FROM pg_database WHERE datname = 'scenario_service';` (expected:
   `b`, `C.UTF-8`).

Flyway creates the schema at the first start. If the container uses another host port, for example 5433, override
the URL:

```sh
./gradlew bootRun --args='--spring.profiles.active=dev --spring.datasource.url=jdbc:postgresql://localhost:5433/scenario_service'
```

## Database and migrations

- **Flyway owns the schema.** Migrations are `src/main/resources/db/migration/V<n>__<snake_case_description>.sql`,
  applied once, in version order, at start-up. Hibernate only validates (`ddl-auto: validate`).
- **Append-only:** once a migration has passed review, it is never edited; a fix is a new version. No `R__` or Java
  migrations, no `IF [NOT] EXISTS`. Never run Flyway `clean`, `repair` or `baseline`.
- **Rules:** the "Database pattern for every service" in `docs/specs/auth-service-persistence.md`, with this service's
  decisions in `docs/specs/scenario-service.md` D14. Paths are unique per project among active features, ignoring
  case by ICU root lower case; the path and tag unique indexes store an `md5` of the compared value, because valid
  values can exceed PostgreSQL's B-tree entry size.
- **Connections:** every connection waits at most 5 s for a lock (`lock_timeout`; tests use 1 s), and database error
  details are kept out of exception messages (`logServerErrorDetail=false`).
- The ERD is `docs/erd/cucumber.dbml`, group `scenario_service`.

## Tests

```sh
cd scenario-service
./gradlew build
./gradlew test --tests 'org.scenarioservice.repository.SchemaTest'
```

Tests use a TEST-ONLY JWT secret defined in test code and a PostgreSQL 17 Testcontainers container (one per test
run, shared by every test). **Docker must run for `./gradlew build`**; without it the build fails, and nothing is
skipped. Tests need no environment variables, no local database and no `.env`; no test activates the `dev` profile.
auth-service is never started: an in-process stub answers the access check.

## Troubleshooting

- **Start-up fails naming `SCENARIO_DB_URL`, `SCENARIO_DB_USERNAME` or `SCENARIO_DB_PASSWORD`:** the variable is unset
  or blank (with `dev`, check that `.env` has `SCENARIO_DB_PASSWORD`). The message never shows a value.
- **Start-up fails naming `app.jwtSecret` / `JWT_SECRET`:** it must be standard Base64 of at least 64 bytes, the same
  value auth-service and the gateway use.
- **`password authentication failed`:** the `scenario_service` role's password differs from `SCENARIO_DB_PASSWORD`;
  set it again with `\password scenario_service` (see "Dev database").
- **Every request is 503 `ACCESS_CHECK_UNAVAILABLE`:** auth-service isn't reachable at `AUTH_SERVICE_URL`
  (default `http://localhost:8081`), or it answers slowly or with errors. Start auth-service first.
- **Every request is 401 although the token works at auth-service:** scenario-service and auth-service use different
  `JWT_SECRET` values.
- **409 `CONFLICT` under heavy concurrent editing:** a write waited longer than the 5 s lock timeout for another write
  in the same project. Retry.
- **`./gradlew build` fails at once in every database test:** Docker isn't running (tests use a Testcontainers
  PostgreSQL 17).
- **Measuring list and view latency:** `SCENARIO_MEASURE=true ./gradlew test --tests '*ListAndViewLatencyMeasurement'`
  (50 features of about 100 lines, through the access check stub; skipped in a normal build).

## Operations note: framework DEBUG logs

scenario-service's own log lines never contain feature content, tokens or personal data. Framework loggers are
different: at DEBUG, Spring Security and Spring Web (`org.springframework.security`, `org.springframework.web`) log
request URLs and query strings, including tag filter values, and Hibernate's `org.hibernate.orm.jdbc.bind` would log
bound values. Don't enable those loggers at DEBUG or TRACE in production; `org.hibernate.SQL` (statements with `?`
placeholders) is the only framework DEBUG logger the `dev` profile turns on.

## Manual runs by agents

Agents (backend-swe, qa) never read `.env` and never use the `dev` profile (spec G10a). For manual checks they:

- start their own throwaway `postgres:17` container (with the initdb arguments
  `--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8`) on a port other than 5432, under a name that
  isn't `cucumber-postgres`, and remove it afterwards;
- run the services without the `dev` profile, passing their own throwaway values (`SCENARIO_DB_*`, `AUTH_DB_*`, one
  shared generated `JWT_SECRET`) as environment variables or command-line properties, never written to a file in the
  repository and never printed in a handoff.

Alternatively, the user starts the services with `dev` and their own `.env`, and the agent only sends requests.
