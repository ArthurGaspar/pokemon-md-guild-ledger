# Guild Expeditions

The guild posts rare expeditions: a few dozen party slots, thousands of adventurers wanting them, and parties that only set out with the right composition (by default a tank, a healer and two damage dealers). This folder is a separate system from the ledger: Spring Boot services around a virtual queue in Redis and a reservation saga over Kafka with a transactional outbox.

The design (services, the queue, the ready check, compensation, event contracts and the phase plan) is in [docs/design.md](docs/design.md).

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | `catalog`, `queue` (Redis), `reservation` (holds with expiry, ready check, compensation), outbox → Kafka | done |
| 2 | `processing` behind Resilience4j against a deliberately flaky `dungeon-sim`, `issuance` + notifications, compensation on failed runs | next |
| 3 | Keycloak, Spring Cloud Gateway, OpenTelemetry traces/logs/metrics in Grafana (Tempo, Loki, Prometheus) | |
| 4 | k6: 10,000 users opening the queue at once, results here | |

## How a party forms

1. **Line up.** `POST :8082/api/expeditions/{id}/queue` puts you in line (joining twice keeps your place). You can line up before the expedition opens.
2. **Admission.** Once a second, the queue admits people from the front, never more than there are open slots. `GET …/queue` then returns `ADMITTED` and a signed admission token, valid for 2 minutes.
3. **Hold a role.** `POST :8083/api/expeditions/{id}/reservations {"role":"HEALER","admissionToken":"…"}` places you in the fullest party with that role open. The hold lasts 10 minutes while the party forms.
4. **Ready check.** When the last slot fills, everyone has 60 seconds to `POST :8083/api/reservations/{id}/confirm`. When everyone has confirmed, the party is `LOCKED`.
5. **Compensation.** If someone declines (`DELETE /api/reservations/{id}`) or misses the check, their reservation ends, their slot reopens to the queue, and the formation is undone: the others go back to holding with a fresh hold, and the party takes a new member.

Until Keycloak arrives in phase 3, callers identify themselves with an `X-Adventurer` header.

## Running

Java 21. Docker for the stack (about 3 GB of RAM in phase 1).

```bash
# from the repository root
READY_CHECK=PT15S docker compose -f expeditions/docker-compose.yml up --build -d
sh expeditions/scripts/demo.sh      # needs curl and jq
```

The catalog seeds **Sky Peak: the Guild's Grand Expedition** (25 parties of four), opening a minute after startup (`CATALOG_SEED_OPENS_IN`). The demo lines four adventurers up, holds their roles, lets one miss the ready check, shows the revert, then has them rejoin and lock the party. Kafka UI at http://localhost:8090 shows every event on `expedition.events` and `reservation.events`.

| Service | Port | Endpoints |
|---|---|---|
| catalog | 8081 | `GET /api/expeditions`, `GET /api/expeditions/{id}`, `POST /api/expeditions` |
| queue | 8082 | `POST`, `GET`, `DELETE /api/expeditions/{id}/queue` |
| reservation | 8083 | `POST /api/expeditions/{id}/reservations`, `GET /api/expeditions/{id}/capacity`, `GET /api/reservations`, `GET /api/reservations/{id}`, `POST /api/reservations/{id}/confirm`, `DELETE /api/reservations/{id}` |

Errors are RFC 7807 problem details with a stable `code`, like the ledger API: `admission.invalid`, `reservation.role-full`, `expedition.sold-out`, `reservation.not-in-ready-check`, `ready-check.missed`, …

## Tests

```bash
cd expeditions && ./mvnw verify
```

- `ReservationFlowTest`: the saga through the HTTP API with a controllable clock: a missed ready check reverting the party and a newcomer completing it, declining, lapsed holds, cancelling, fullest-party-first placement, admission rules, ownership, duplicate events.
- `ConcurrentHoldTest`: 40 adventurers race for 6 slots. Exactly 6 win, no party goes over its composition, and capacity events come out of the outbox with strictly increasing versions, which the queue relies on to drop stale ones.
- `PostgresReservationTest`: the same scenarios on PostgreSQL 17 via Testcontainers.
- `QueueRedisTest`: the queue's Lua scripts on a real Redis: ordering, the waiting room, admission capped by open slots and batch size, versioned capacity, lapsed admissions.
- `PartyTest`, `AdmissionTokensTest`, `ExpeditionApiTest`.

The Testcontainers tests are skipped without Docker and run in CI.

## Structure

```text
platform/              shared library: event contracts, outbox + relay, admission tokens, problem details
catalog-service/       expedition definitions, validated against ../shared/catalog.json
queue-service/         Redis line and admission (src/main/resources/scripts/*.lua)
reservation-service/   parties, holds, ready check, compensation, sweeper
docs/design.md         the design
infra/                 PostgreSQL init
scripts/demo.sh        the walkthrough above
```
