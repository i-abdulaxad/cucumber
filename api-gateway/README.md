# api-gateway

Single entry point for every API call (spec: `docs/specs/api-gateway.md`, edge contract:
`docs/api/api-gateway.openapi.yaml`). Spring Cloud Gateway Server MVC on Spring Boot 4.1, Java 17.

The gateway:

- routes by path prefix, with no path rewriting;
- validates bearer JWTs locally (HS512, shared secret);
- passes `X-Request-Id` and `X-User-Id` downstream;
- answers gateway-generated errors with RFC 9457 Problem Details;
- is the only CORS authority;
- writes one access log entry per request;
- exposes only `GET /actuator/health`.

## Port plan

| Service          | Local port | Status                                  |
|------------------|------------|-----------------------------------------|
| api-gateway      | 8080       | Clients and the frontend use this port. |
| auth-service     | 8081       |                                         |
| scenario-service | 8082       | Reserved.                               |
| report-service   | 8083       | Reserved.                               |

## Routes

| External path prefix              | Service            | Public operations (no token)                                                                   |
|-----------------------------------|--------------------|------------------------------------------------------------------------------------------------|
| `/api/auth/**`                    | auth-service       | `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/invitations/{token}/accept` |
| `/api/orgs`, `/api/orgs/**`       | auth-service       | none                                                                                           |
| `/api/scenarios/**` (provisional) | scenario-service   | none                                                                                           |
| `/api/reports/**` (provisional)   | report-service     | none                                                                                           |
| `GET /actuator/health`            | the gateway itself | yes                                                                                            |

Any other path gets 401 without a valid token and 404 with one. Routes and public operations live in
`src/main/resources/application.yaml`.

## Settings

| Setting                                               | Environment variable       | Default (no profile)                  | `dev` profile                                                               |
|-------------------------------------------------------|----------------------------|---------------------------------------|-----------------------------------------------------------------------------|
| Gateway port                                          | `SERVER_PORT`              | 8080                                  | 8080                                                                        |
| auth-service base URL                                 | `AUTH_SERVICE_URL`         | `http://localhost:8081`               | same                                                                        |
| scenario-service base URL                             | `SCENARIO_SERVICE_URL`     | `http://localhost:8082`               | same                                                                        |
| report-service base URL                               | `REPORT_SERVICE_URL`       | `http://localhost:8083`               | same                                                                        |
| JWT shared secret                                     | `JWT_SECRET`               | none, so the gateway refuses to start | from the gitignored `.env` in the repository root, shared with auth-service |
| CORS allowed origins (comma-separated, exact origins) | `CORS_ALLOWED_ORIGINS`     | empty, so no cross-origin access      | `http://localhost:3000,http://localhost:5173`                               |
| Connect timeout                                       | `GATEWAY_CONNECT_TIMEOUT`  | `2s`                                  | `2s`                                                                        |
| Response timeout                                      | `GATEWAY_RESPONSE_TIMEOUT` | `10s`                                 | `10s`                                                                       |

Invalid values stop the gateway at startup with a message that names the setting. The secret value is never
logged.

### Production JWT secret

`JWT_SECRET` is the standard Base64 (RFC 4648 section 4) of at least 64 random bytes, the HS512 minimum.
auth-service and the gateway must use the same value. Generate one and keep it in your secret store, never in
the repository:

```bash
openssl rand -base64 64 | tr -d '\n'
```

## Run locally

One-time setup: copy `.env.example` to `.env` in the repository root and fill in `JWT_SECRET` and
`AUTH_DB_PASSWORD` (the file shows how to generate them). Both services' `dev` profiles read this gitignored file;
never commit it.

Each service is an independent Gradle build. Start auth-service first, then the gateway, each in its own terminal,
both with the `dev` profile:

```bash
cd auth-service && ./gradlew bootRun --args='--spring.profiles.active=dev'
cd api-gateway  && ./gradlew bootRun --args='--spring.profiles.active=dev'
```

Without the `dev` profile (or without `.env`), both services need `JWT_SECRET` in the environment.

Other ways to activate the `dev` profile for a local run:

- In IntelliJ IDEA, set "Active profiles: dev" in the Spring Boot run configuration.
- Set the environment variable `SPRING_PROFILES_ACTIVE=dev`.

Never set the profile in `application.yaml` (for example `spring.profiles.active: dev`). The base configuration
must activate no profile, otherwise the DEV ONLY secret and the dev CORS origins become the default everywhere.

The services use `spring-boot-devtools`. A failed startup can still end with Gradle's `BUILD SUCCESSFUL`, so check
the log for `Application run failed`.

Logs:

- One access entry per request, from the `org.apigateway.access` logger, for example
  `requestId=… method=POST path=/api/orgs status=201 durationMs=3 service=auth-service userId=…`.
- `service` appears only when a route handled the request. `userId` appears only on authenticated protected
  requests.
- Invitation tokens in paths are logged as `/api/auth/invitations/***/accept`.

## Tests

```bash
cd api-gateway && ./gradlew build
```

The tests start the real gateway on ephemeral ports against in-process stub services built on the JDK HTTP server.
They need no network and no running services. Tests that prove an acceptance criterion are tagged with its ID, for
example `@Tag("AC-2.3")`.

## Manual end-to-end checks (AC-8.6, AC-1.6, AC-2.8)

Start both services as described in [Run locally](#run-locally). Use a fresh auth-service: it keeps users in an
in-memory database, so a restart clears them.

**AC-8.6: the services run side by side.** Both start without a port conflict, auth-service answers on 8081, and
the gateway is healthy:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/v3/api-docs   # expect 200
curl -s -w ' %{http_code}\n' http://localhost:8080/actuator/health          # expect {"status":"UP"} 200
```

**AC-1.6: register and log in through the gateway.** Expect `201`, then `200` with a non-empty `accessToken`.
The register response's `id` is the user ID `U`.

```bash
curl -s -w '\n%{http_code}\n' -H 'Content-Type: application/json' \
  -d '{"email":"ana@example.com","password":"Secret123!","fullName":"Ana Lima"}' \
  http://localhost:8080/api/auth/register

TOKEN=$(curl -s -H 'Content-Type: application/json' \
  -d '{"email":"ana@example.com","password":"Secret123!"}' \
  http://localhost:8080/api/auth/login | python3 -c 'import sys, json; print(json.load(sys.stdin)["accessToken"])')
```

**AC-2.8: the gateway accepts a token issued by auth-service.** The status must not be 401. auth-service itself
currently answers 403 with an empty body, because of a known auth-service defect (spec, "Known issues" 2 and 3).

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'X-Request-Id: ac-2-8' \
  -d '{"name":"Acme QA"}' http://localhost:8080/api/orgs
```

The gateway log then contains one access entry for `requestId=ac-2-8` with `service=auth-service` and `userId=U`.

Don't paste tokens into tickets or logs.
