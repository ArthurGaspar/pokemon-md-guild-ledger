# Configuration

Every setting has a local-development default in `application.yml`, overridden by an environment variable. Defaults that look like secrets end in `change-me` and are for local use only. Set real values in any shared deployment.

## Ledger API (`api/`)

| Variable | Default | Notes |
|---|---|---|
| `GUILD_DB_URL` | `jdbc:postgresql://localhost:5432/guild` | Ignored by the `local` profile (H2 file in `api/data/`) |
| `GUILD_DB_USER` / `GUILD_DB_PASSWORD` | `guild` / `guild` | |
| `GUILD_JWT_SECRET` | local dev value | HS256 key, **at least 32 bytes**, or startup fails. Tokens last 12 h (`guild.jwt.ttl`) |
| `GUILD_CORS_ORIGINS` | `http://localhost:5173` | Comma-separated web origins |

Profiles: none (PostgreSQL), `local` (H2 file), `test` (in-memory H2, used by tests). Swagger UI is at `/swagger-ui.html`, and the only actuator endpoint exposed is `health`.

## Web (`web/`)

| Variable | Effect |
|---|---|
| `VITE_API_URL` | If set at build time, the client talks HTTP to this URL. If unset, it runs the in-browser engine. `web/.env.api` sets it for `--mode api`, and `web/Dockerfile` takes it as the `API_URL` build argument |

## Expeditions (`expeditions/`)

Shared by every service (from `platform/.../expeditions-defaults.properties` and each `application.yml`):

| Variable | Default | Notes |
|---|---|---|
| `PORT` | per service (8081–8086) | |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `localhost:5432/<service>`, `guild`, `guild` | Services with a database |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | From the host, compose exposes `localhost:29092` |
| `AUTH_MODE` | `jwt` | `dev-headers` trusts `X-Adventurer` / `X-Roles`. Tests and local runs only |
| `AUTH_ISSUER` | `http://localhost:8180/realms/guild` | Must match the tokens' `iss` (the browser-facing Keycloak URL) |
| `AUTH_JWKS` | `…/protocol/openid-connect/certs` on the issuer | Where the service fetches keys. In compose, `http://keycloak:8080/...` |
| `ADMISSION_SECRET` | local dev value | HMAC key shared by queue and reservation, 32+ bytes. They must match |
| `OTLP_EXPORT` | `false` | `true` sends traces, metrics and logs to `OTLP_ENDPOINT` |
| `OTLP_ENDPOINT` | `http://localhost:4318` | OTLP over HTTP (Grafana LGTM in compose) |

Service-specific:

| Variable | Service | Default | Meaning |
|---|---|---|---|
| `REDIS_HOST` | queue, gateway | `localhost` | |
| `CATALOG_SEED_OPENS_IN` | catalog | `PT1M` | When the seeded expedition opens after startup |
| `HOLD_TTL` | reservation | `PT10M` | How long a hold in a forming party lasts |
| `READY_CHECK` | reservation | `PT60S` | Ready-check window. The demo uses `PT15S` |
| `SIM_URL` | processing | `http://localhost:8085` | The `dungeon-sim` base URL |
| `RUN_DEADLINE` | processing | `PT2M` | After this, a run that never got an answer fails as `UNAVAILABLE` |
| `SIM_LATENCY_MS`, `SIM_ERROR_RATE`, `SIM_SLOW_RATE`, `SIM_FAINT_RATE` | dungeon-sim | 300, 0.2, 0.1, 0.25 | Starting chaos. Change at runtime with `PUT /api/chaos` |
| `CATALOG_URL`, `QUEUE_URL`, `RESERVATION_URL`, `PROCESSING_URL`, `ISSUANCE_URL` | gateway | `localhost` ports | Route targets |

The authoritative list is each service's `application.yml`. This table covers the settings you are likely to change.

## Ports

| Port | Ledger stack | Expeditions stack |
|---|---|---|
| 5173 | web (Vite dev, or nginx in compose) | |
| 8080 | ledger API | gateway |
| 8081–8086 | | catalog, queue, reservation, processing, dungeon-sim, issuance |
| 8090 | | Kafka UI |
| 8180 | | Keycloak (`admin`/`admin`) |
| 3000 | | Grafana |
| 29092 | | Kafka from the host |

Both stacks use port 8080, so run one at a time.
