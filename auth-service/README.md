# auth-service

Accounts, login, organizations, invitations and members (spec: `docs/specs/auth-service.md`, phase 1).
The API contract is `docs/api/auth-service.openapi.yaml`; the runtime `/v3/api-docs` is not the contract.

## Run locally

Requirements: JDK 17 (the Gradle toolchain) and a PostgreSQL 17 database; see "Running locally with PostgreSQL" below.
Data survives restarts, and the schema comes from Flyway migrations ("Database and migrations").

```sh
cd auth-service
./gradlew bootRun --args='--spring.profiles.active=dev'
```

- Port: **8081** (`server.port`). Normal clients call it through the API gateway on port 8080; direct calls on 8081
  are for local development only.
- The `dev` profile supplies the local database URL and username, reads `JWT_SECRET` and `AUTH_DB_PASSWORD` from the
  gitignored `.env` in the repository root (or from the environment), and logs SQL statements through the logger
  `org.hibernate.SQL` (with request and user ids, `?` placeholders only). Never use the `dev` profile outside a
  developer machine.
- One-time setup for `dev`: copy `.env.example` to `.env` in the repository root and fill in both values (the file
  shows how to generate them). The gateway reads the same file, so both services share the JWT secret. Never commit
  `.env`; without it (and without the environment variables) start-up fails naming the missing variable.
- Without the `dev` profile you must set `AUTH_DB_URL`, `AUTH_DB_USERNAME`, `AUTH_DB_PASSWORD` and `JWT_SECRET`
  (setting `app.jwtSecret`). `JWT_SECRET` is the standard Base64 encoding of at least 64 random bytes, the same value
  the gateway uses. The service refuses to start without a usable secret.
  For example, generate one with `openssl rand -base64 64 | tr -d '\n'` and keep it out of version control.
- `AUTH_DB_*` values must not contain `${`: Spring would treat it as a placeholder (FU-24).

```sh
AUTH_DB_URL='jdbc:postgresql://localhost:5432/auth_service' AUTH_DB_USERNAME='auth_service' \
  AUTH_DB_PASSWORD='<password>' JWT_SECRET='<standard Base64 of at least 64 random bytes>' ./gradlew bootRun
```

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `app.jwtSecret` (`JWT_SECRET`) | none (`.env` with the `dev` profile) | Shared HS512 secret, standard Base64 of at least 64 bytes |
| `app.jwtExpirationInMs` | `86400000` (24 h) | Lifetime of issued tokens |
| `app.orgs.free-seat-limit` | `5` | Seat limit of every organization created in phase 1 (plan `FREE`); must be positive |
| `spring.datasource.url` (`AUTH_DB_URL`) | none (the `dev` profile sets one) | JDBC URL of the auth_service database, without credentials |
| `spring.datasource.username` (`AUTH_DB_USERNAME`) | none (the `dev` profile sets one) | Database role |
| `spring.datasource.password` (`AUTH_DB_PASSWORD`) | none (`.env` with the `dev` profile) | Database password; never logged |
| `app.internal-service-key` (`INTERNAL_SERVICE_KEY`) | empty (disabled) | Key other services send as `X-Service-Key` to `GET /internal/service/projects/{projectId}` (C7, report-service spec D5). Empty: every `/internal/service/**` request is 401. When set: standard Base64 of at least 32 bytes, else start-up fails naming the variable; compared in constant time; never logged |

A missing or blank `AUTH_DB_*` value stops start-up with a message that names the variable and never shows a value.

## Running locally with PostgreSQL

The `dev` profile expects PostgreSQL 17 on `localhost:5432`, database and role `auth_service`, and the password from
`AUTH_DB_PASSWORD` in `.env`. The database must use UTF-8 and the builtin
`C.UTF-8` locale, which exists from PostgreSQL 17 on, so lists sort by code point on every machine.

Recommended: a Docker container (Docker Desktop runs for the tests anyway). One-time setup:

1. Free port 5432. If Postgres.app (or another server) holds it, quit it, or use another host port and override the URL
   (see below).
2. Start the container:

   ```sh
   set -a; . ./.env; set +a   # from the repository root: loads AUTH_DB_PASSWORD
   docker run -d --name cucumber-postgres --restart unless-stopped -p 5432:5432 \
     -e POSTGRES_USER=auth_service -e POSTGRES_PASSWORD="$AUTH_DB_PASSWORD" -e POSTGRES_DB=auth_service \
     -e POSTGRES_INITDB_ARGS="--encoding=UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8" \
     -v cucumber-postgres-data:/var/lib/postgresql/data postgres:17
   ```

3. Run auth-service with the `dev` profile (see "Run locally"). Flyway applies the migrations at the first start; data
   lives in the `cucumber-postgres-data` volume and survives restarts.

On another host port, for example 5433 (`-p 5433:5432`), override the URL on the command line:

```sh
./gradlew bootRun --args='--spring.profiles.active=dev --spring.datasource.url=jdbc:postgresql://localhost:5433/auth_service'
```

Alternative: a PostgreSQL 17 server of your own (for example Postgres.app with a version 17 server), with:

```sql
CREATE ROLE auth_service LOGIN PASSWORD '<AUTH_DB_PASSWORD from .env>';
CREATE DATABASE auth_service OWNER auth_service TEMPLATE template0 ENCODING 'UTF8'
    LOCALE_PROVIDER builtin BUILTIN_LOCALE 'C.UTF-8';
```

Check the database with `SELECT datlocprovider, datlocale FROM pg_database WHERE datname = 'auth_service'`
(expected: `b`, `C.UTF-8`). Other services add their own role and database to the same server.

## Database and migrations

- **Flyway owns the schema.** Migrations are `src/main/resources/db/migration/V<n>__<snake_case_description>.sql`,
  applied once, in version order, at start-up; `flyway_schema_history` records them. Hibernate only validates
  (`spring.jpa.hibernate.ddl-auto: validate`), so a schema that doesn't match the entities stops start-up, and so does
  an applied migration whose file was edited (checksum mismatch).
- **Append-only:** once a migration has passed review, it is never edited; a fix is a new version. One migration per
  approved change; no `R__` or Java migrations, no `IF [NOT] EXISTS`. Never run Flyway `clean`, `repair` or `baseline`.
- **Names:** `pk_<table>`, `fk_<table>_<column>`, `uk_<table>_<columns>`, `ix_<table>_<columns>`,
  `ck_<table>_<column>_<rule>`.
- **Types and rules:** ids `uuid` (generated by the application); timestamps `timestamptz` mapped to `Instant`, set
  from the service clock (UTC, microseconds); enums `varchar(32)` with a `CHECK` listing the Java constants; NOT NULL
  for every value the application always sets; emails stored lower case (`CHECK`); project names unique per
  organization ignoring case (`lower(name COLLATE "und-x-icu")`); every foreign-key column indexed, every foreign key
  `ON DELETE RESTRICT`.
- **Migrations so far:** V1 baseline (the original entities), V2 timestamps with time zone, V3 required columns and
  lengths, V4 enum value checks, V5 case-insensitive uniqueness, V6 foreign-key indexes and delete rules, V7 drop
  `audit_log`.
- **Connections:** every connection waits at most 5 s for a lock (`lock_timeout`; tests use 1 s), and database error
  details, which can contain stored values, are kept out of exception messages (`logServerErrorDetail=false`).
- The ERD is `docs/erd/cucumber.dbml`; the rules shared with the other services are in
  `docs/specs/auth-service-persistence.md`, section "Database pattern for every service".

## Public operations

Exactly three operations work without a token: `POST /api/auth/register`, `POST /api/auth/login` and
`POST /api/auth/invitations/{token}/accept`. Swagger UI (`/swagger-ui/**`) and `/v3/api-docs` are public on port
8081 only. Every other path needs `Authorization: Bearer <accessToken>` and otherwise gets a 401 Problem.

## Projects

Projects belong to one organization and live under `/api/orgs/{orgId}/projects` (spec:
`docs/specs/auth-service-projects.md`, phase 2). The gateway's existing `orgs` route covers these paths.

- **Project roles**, with an explicit rank: `ADMIN` 4 > `MEMBER` 3 > `TESTER` 2 > `READER` 1. Each role includes
  the ones below it. Code compares roles only with `ProjectRole.isAtLeast`, never by declaration order.
- **Effective role**, computed from the database on every request (no cache), in `ProjectRoleService` only:
  - not a member of the project's organization: no access;
  - organization `OWNER` or `ADMIN`: project `ADMIN` on every project of the organization (implicit admin, no
    membership row);
  - otherwise the explicit project role, or no access.

  An organization `READER` can only hold project `READER` (the cap). `myRole` in project responses is the
  effective role. A removal or demotion applies to the next request.
- **Who can do what:** organization `OWNER` and `ADMIN` create projects; any organization member lists the
  projects they have access to; anyone with access views a project and its members; an effective project `ADMIN`
  renames, archives, restores and manages members; any explicit member can leave.
- **Members** are current organization members, added by user id; there are no project invitations. A project
  membership uses no organization seat. Leaving or being removed from the organization deletes the user's project
  memberships there; becoming organization `READER` lowers their project roles to `READER`.
- **Archive** sets `archivedAt`. An archived project stays readable but is read-only: renaming and every membership
  change (leaving included) get 409 `PROJECT_ARCHIVED` until it is restored. Projects can't be deleted.
- **Names** are 1–100 characters as sent, stored trimmed, and unique per organization ignoring case, archived
  projects included (409 `PROJECT_NAME_TAKEN`).
- No access, an unknown project and a project of another organization all get the same 403 `FORBIDDEN`.

## Access check for other services

`GET /internal/projects/{projectId}/access` tells another service (scenario-service, report-service) the calling
user's effective role in a project (contract C5).

- **How to call it:** directly on auth-service's base URL (`AUTH_SERVICE_URL`, `http://localhost:8081` locally),
  never through the gateway: the gateway never routes `/internal/**`. Forward the end user's
  `Authorization: Bearer <jwt>` header unchanged; no service credential is needed.
- **Answer:** 200 with exactly `projectId`, `orgId`, `role` (`ADMIN`, `MEMBER`, `TESTER` or `READER`) and `archived`.
  401 `UNAUTHENTICATED` for a missing or invalid token; 403 `FORBIDDEN` for no access or an unknown project; 400
  `MALFORMED_REQUEST` for an id that isn't a UUID.
- **Consumer rules:**
  - compare `role` by rank (`ADMIN` > `MEMBER` > `TESTER` > `READER`), never by string order;
  - refuse content writes when `archived` is true;
  - treat a timeout or a 5xx as "deny" (fail closed);
  - don't cache the answer, unless your service's spec accepts a staleness window of at most 30 s, documented as a
    known delay for removals.
- **Stability:** fields are only ever added; a breaking change gets a new path.
- **Not yet available:** calls without an end user (CI uploads, background jobs) need a service credential, which
  comes with the result-ingestion spec.

## Tests

```sh
cd auth-service
./gradlew build      # compile, all tests, package
./gradlew test --tests 'com.example.authservice.controller.MembersTest'
```

Tests use a TEST-ONLY secret defined in test code and a PostgreSQL 17 Testcontainers container (one per test run,
shared by every test). **Docker must be running**; a build without it fails, nothing is skipped. They need no
environment variables and no local database. The first build downloads the `postgres:17` image.

## Manual end-to-end check through the gateway

Start both services with the `dev` profile (they share the dev secret):

```sh
(cd auth-service && ./gradlew bootRun --args='--spring.profiles.active=dev')
(cd api-gateway && ./gradlew bootRun --args='--spring.profiles.active=dev')
```

Then, against `http://localhost:8080` (expected status in brackets):

1. Register Ana: `POST /api/auth/register` `{"email":"ana@example.com","password":"Secret123!","fullName":"Ana Lima"}` [201]
2. Log in as Ana: `POST /api/auth/login` `{"email":"ana@example.com","password":"Secret123!"}` [200]; keep `accessToken`.
3. Create "Acme QA": `POST /api/orgs` `{"name":"Acme QA"}` with Ana's token [201]; keep `id`.
4. Invite Fay: `POST /api/orgs/{id}/invitations` `{"email":"fay@example.com","role":"MEMBER"}` with Ana's token
   [201]; keep `token` (shown only here).
5. Accept anonymously: `POST /api/auth/invitations/{token}/accept` `{"password":"Secret123!","fullName":"Fay Lin"}` [201]; keep `user.id`.
6. Log in as Fay [200].
7. Ana changes Fay to `READER`: `PATCH /api/orgs/{id}/members/{fayId}` `{"role":"READER"}` [200].
8. Ana removes Fay: `DELETE /api/orgs/{id}/members/{fayId}` [204].
9. An error passes through unchanged, for example `POST /api/orgs` `{"name":""}` with Ana's token: a 400
   `application/problem+json` body with `code` `VALIDATION_FAILED` and the gateway's `requestId`.

Stop both applications afterwards (Ctrl+C), and run `./gradlew --stop` in each service if you want the Gradle
daemons gone.
