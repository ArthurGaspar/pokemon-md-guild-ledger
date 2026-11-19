# Architecture

## The parts

```text
                 shared/catalog.json (species, items, dungeons, ranks, difficulties)
          ┌─────────────────────────┬─────────────────────────────┐
          ▼                         ▼                             ▼
 ┌──────────────────┐  HTTP  ┌───────────────────────┐   ┌─────────────────────────┐
 │ api/             │◀───────│ web/                  │   │ expeditions/            │
 │ Spring Boot, JPA │ (mode  │ React client          │   │ catalog-service, queue, │
 │ PostgreSQL / H2  │  api)  │ engine/ = TS port of  │   │ reservation, … gateway  │
 └──────────────────┘        │ api/ (Pages build)    │   └─────────────────────────┘
          ▲                  └───────────────────────┘
          │                             ▲
          └── shared/contract/scenario.json runs against both
```

There are three independent deliverables:

- **Guild Ledger API** (`api/`). A single Spring Boot application, organized by feature package. It owns accounts, teams, invitations, the roster, the job board and a double-entry ledger of Poké and items. See [ledger-api.md](ledger-api.md).
- **Web client** (`web/`). React, no router or state library: `App.tsx` holds the session and the selected team, and `views/` holds the screens. All server access goes through `api/client.ts`, which takes a `Transport`:
  - `HttpTransport` calls the Spring API. It is used when `VITE_API_URL` is set (`--mode api`, or the Docker build).
  - `BrowserTransport` runs `engine/engine.ts` in the tab and saves its state to `localStorage`. This is the GitHub Pages build, seeded with a demo team by `engine/seed.ts`.
- **Guild Expeditions** (`expeditions/`). A separate distributed system: a Maven multi-module project with a shared `platform` library, six services and a gateway, wired together by Kafka and fronted by Keycloak. It does not call the ledger API and shares no code with it. The only thing they share is `shared/catalog.json`, read by `catalog-service`. See [expeditions.md](expeditions.md) and [the design](../expeditions/docs/design.md).

## What `shared/` is for

| File | Consumers | How it gets there |
|---|---|---|
| `shared/catalog.json` | `api` (`catalog/Catalog.java`), `web` (`api/catalog.ts`), `expeditions/catalog-service` (`GameData.java`) | Maven copies it into the jar as `shared/catalog.json` (see each pom's `<resources>`). Vite imports it through the `@shared` alias. |
| `shared/contract/scenario.json` | `api` tests (`ContractRunner`), `web` tests (`contract.test.ts`) | A Maven test resource at `contract/`, and a Vite import |

Because the Maven builds read `../shared`, every Dockerfile is built with the **repository root** as its context (`docker build -f api/Dockerfile .`).

The catalog's numbers (rank thresholds, exp, bag sizes) are game-balance data, not code. Changing a value is safe. Renaming a code or a field breaks every consumer and usually the contract scenario.

## Design choices that hold across the repo

- **Errors are problem details** (RFC 7807) with a stable machine-readable `code` property. Clients branch on `code`, never on the message. Both the ledger API and every expeditions service follow this.
- **Correctness under concurrency comes from row locks, not optimistic retries.** Ledger writes lock the team row. Reservation locks the expedition row. Both choices serialize writes per aggregate and are tested with concurrent races (`ConcurrentPayoutTest`, `ConcurrentHoldTest`).
- **Time is injected.** Each Spring application has a `Clock` bean, and tests swap in `MutableClock`. Expiry is evaluated when data is read where possible (invitations), or by a sweeper that goes through the same code paths as the API (reservation holds).
- **Schemas are owned by Flyway** with `ddl-auto: validate`. H2 in PostgreSQL mode is used for fast tests, and Testcontainers repeats the important suites on real PostgreSQL.
- **Each expeditions service owns its database.** They share one PostgreSQL server in compose only to keep the stack small (`expeditions/infra/postgres/init.sql`).

## Build and delivery

| Target | How |
|---|---|
| GitHub Pages | CI builds `web/` in browser mode and deploys `web/dist` on pushes to `main` |
| Ledger stack | `docker-compose.yml`: PostgreSQL, `api`, and `web` served by nginx with `VITE_API_URL` baked in at build time |
| Expeditions stack | `expeditions/docker-compose.yml`: infrastructure plus one image per service from `expeditions/Dockerfile` (`--build-arg SERVICE=<module>`) |

CI (`.github/workflows/ci.yml`) runs four jobs on every push and PR: `api` (`mvnw verify`), `expeditions` (`mvnw verify`), `web` (`npm test` + `npm run build`) and `images` (builds both compose files). Docker is available there, so the Testcontainers suites always run in CI even when they are skipped locally.
