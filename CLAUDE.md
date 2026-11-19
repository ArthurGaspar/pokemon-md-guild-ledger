# CLAUDE.md

Guidance for working in this repository. Deeper reference lives in [`docs/`](docs/README.md).

## What this is

Three things in one repo, sharing only `shared/`:

| Path | What | Stack |
|---|---|---|
| `api/` | Guild Ledger API: teams, roster, job board, storage, double-entry ledger | Spring Boot 4.1, Java 21, JPA, Flyway, PostgreSQL (H2 locally/tests), HS256 JWT |
| `web/` | React client. Default build runs an **in-browser port of the API** (`web/src/engine/`); `--mode api` talks HTTP to `api/` | React 19, Vite, TypeScript, Vitest |
| `expeditions/` | Separate microservice system: Redis queue, reservation saga over Kafka with transactional outbox, flaky simulator behind Resilience4j, Keycloak, gateway, OTel | Maven multi-module, Spring Boot 4.1 (gateway on 4.0) |
| `shared/` | `catalog.json` (game data read by api, web and catalog-service) and `contract/scenario.json` | JSON |

## Commands

```bash
# API (Java 21). On Windows PowerShell use .\mvnw.cmd instead of ./mvnw
cd api && ./mvnw verify                                           # all tests
cd api && ./mvnw test -Dtest=ContractScenarioTest                 # one test class
cd api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local # :8080, H2 file DB, no Postgres needed

# Web (Node 22)
cd web && npm ci
cd web && npm test                  # vitest run
cd web && npx vitest run src/engine/contract.test.ts
cd web && npm run build             # tsc --noEmit + vite build (this is the type check)
cd web && npm run dev               # browser-only build
cd web && npm run dev:api           # against the API on :8080

# Expeditions
cd expeditions && ./mvnw verify                                   # all modules
cd expeditions && ./mvnw -pl reservation-service -am verify       # one service (+ platform)
docker compose -f expeditions/docker-compose.yml up --build -d    # full stack, ~6 GB RAM; gateway :8080

# Ledger stack
docker compose up --build           # web :5173, API :8080, Postgres
```

Testcontainers tests (`PostgresContractTest`, `PostgresReservationTest`, `QueueRedisTest`) are skipped without Docker and always run in CI. Dockerfiles must be built **from the repo root** because they copy `shared/`.

## Rules that are easy to break

- **A ledger rule lives in two implementations.** The Java API and `web/src/engine/engine.ts` must answer identically (status, error `code`, response shape). Any behavior change touches both plus `shared/contract/scenario.json`, which runs against H2, PostgreSQL and the TS engine. See [docs/contract.md](docs/contract.md).
- **`shared/catalog.json` has three consumers** (api, web, catalog-service). Changing a code or field name breaks all three builds.
- **Errors are RFC 7807 problem details with a stable `code`** (`area.kebab-case`, e.g. `ledger.insufficient-funds`). Throw `ApiException` with the right factory; never return ad-hoc error bodies. Codes are contract: don't rename them.
- **Non-members get 404, not 403**, for team resources (`TeamAccess.member`/`atLeast`). Keep it that way.
- **Ledger writes go through `LedgerService.post`**, which requires an existing transaction (`Propagation.MANDATORY`), locks the team row, and rejects legs that don't net to zero per asset. Only `world` may go negative. Entries are append-only.
- **Flyway migrations are append-only.** Add `V<n+1>__name.sql`; never edit an applied one. `ddl-auto: validate`, so entities must match the schema. SQL must run on both H2 (PostgreSQL mode) and PostgreSQL.
- **Time comes from an injected `Clock`** (tests use `MutableClock`; the TS engine uses `engine.now`). Don't call `Instant.now()` directly in services.
- **Expeditions events:** state change + `Outbox.append` in one transaction (it throws otherwise). Consumers must be idempotent: call `Inbox.firstDelivery` first in the handler's transaction, or guard on state/version. Kafka key is always the expedition id. New event types go in `platform/.../events/Events.java` **and** the event table in `expeditions/docs/design.md`.
- **The gateway is not a child of the expeditions parent pom** (different Boot line for Spring Cloud). Don't make it depend on `platform`.
- Secrets in config files are local defaults (`*-change-me-*`), overridden by env vars. Don't add real ones.

## Conventions

- Java: constructor injection, package-private where possible, records for request/response DTOs nested in controllers, one feature per package (`team/`, `ledger/`, …). Jackson 3 (`tools.jackson.*`).
- Comments explain *why* (invariants, races, trade-offs), in full sentences; match the surrounding density.
- Commits: Conventional Commits with scope, e.g. `feat(expeditions): …`, `fix(api): …`, with a bulleted body.
- When behavior visible to users changes, update the relevant README (root or `expeditions/`). When design changes, update `expeditions/docs/design.md` or `docs/`.
