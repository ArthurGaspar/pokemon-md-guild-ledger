# Guild Expeditions

The guild posts rare expeditions: a few dozen party slots, thousands of adventurers wanting them, and parties that only set out with the right composition (by default a tank, a healer and two damage dealers). This folder is a separate system from the ledger: Spring Boot services around a virtual queue in Redis and a saga over Kafka with a transactional outbox. The saga goes from reservation to a run through a deliberately unreliable dungeon simulator, then to issuance.

The design (services, the queue, the ready check, compensation, event contracts and the phase plan) is in [docs/design.md](docs/design.md).

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | `catalog`, `queue` (Redis), `reservation` (holds with expiry, ready check, compensation), outbox → Kafka | done |
| 2 | `processing` behind Resilience4j against a deliberately flaky `dungeon-sim`, `issuance` (certificates, loot, SSE notifications), compensation on failed runs | done |
| 3 | Keycloak, Spring Cloud Gateway with per-adventurer rate limits, OpenTelemetry traces/logs/metrics in Grafana (Tempo, Loki, Prometheus), traces carried across the outbox and the run worker | done |
| 4 | k6: 10,000 users opening the queue at once, results here | next |

## How a party forms

1. **Line up.** `POST :8082/api/expeditions/{id}/queue` puts you in line (joining twice keeps your place). You can line up before the expedition opens.
2. **Admission.** Once a second, the queue admits people from the front, never more than there are open slots. `GET …/queue` then returns `ADMITTED` and a signed admission token, valid for 2 minutes.
3. **Hold a role.** `POST :8083/api/expeditions/{id}/reservations {"role":"HEALER","admissionToken":"…"}` places you in the fullest party with that role open. The hold lasts 10 minutes while the party forms.
4. **Ready check.** When the last slot fills, everyone has 60 seconds to `POST :8083/api/reservations/{id}/confirm`. When everyone has confirmed, the party is `LOCKED`.
5. **Compensation.** If someone declines (`DELETE /api/reservations/{id}`) or misses the check, their reservation ends, their slot reopens to the queue, and the formation is undone: the others go back to holding with a fresh hold, and the party takes a new member.
6. **The run.** Processing sends the locked party into `dungeon-sim`, which is slow and fails on purpose. Quick retries, a circuit breaker and a bulkhead protect each attempt. Failed attempts are retried later with backoff, for up to 2 minutes.
7. **The outcome.** Clearing the dungeon issues a certificate and an equal share of the loot to each member. Fainting is a real outcome: the expedition happened and the slots stay used. If the dungeon never answers, the guild calls the expedition off, every reservation is aborted, and the slots go back to the queue.

Adventurers are notified at each step (ready check, missed check, setting out, outcome) over SSE or by polling.

Everything goes through the gateway on :8080 with a Keycloak token. Reading the catalog is public, publishing an expedition takes the `guild-staff` role, and everything else takes a signed-in adventurer.

## Running

Java 21. Docker for the stack (about 6 GB of RAM with Keycloak and Grafana). If you started an earlier stack, run `docker compose -f expeditions/docker-compose.yml down -v` once: new databases are only created on a fresh volume.

```bash
# from the repository root
READY_CHECK=PT15S docker compose -f expeditions/docker-compose.yml up --build -d
sh expeditions/scripts/demo.sh      # needs curl and jq
```

The catalog seeds **Sky Peak: the Guild's Grand Expedition** (25 parties of four), opening a minute after startup (`CATALOG_SEED_OPENS_IN`). The demo signs everyone in through Keycloak, has Wigglytuff publish a fresh expedition (so it can be rerun), lines four adventurers up, holds their roles, lets one miss the ready check, shows the revert, has them rejoin and lock the party, follows the run attempt by attempt, and prints the certificates and notifications. Kafka UI at http://localhost:8090 shows every event.

Accounts in the `guild` realm, all with the password `lantern-123` (Keycloak admin console: http://localhost:8180, `admin`/`admin`):

| Account | Roles |
|---|---|
| `wigglytuff` | guild-staff, adventurer |
| `chatot` | guild-staff |
| `mira`, `teo`, `sol`, `nina`, `kai`, `ines` | adventurer |

```bash
TOKEN=$(curl -s localhost:8180/realms/guild/protocol/openid-connect/token \
  -d grant_type=password -d client_id=expeditions -d username=mira -d password=lantern-123 | jq -r .access_token)
curl -s -X POST localhost:8080/api/expeditions/1/queue -H "Authorization: Bearer $TOKEN"
```

**Following a request.** Grafana at http://localhost:3000. In Explore, open Tempo and search for `service.name = processing`. A run's trace starts at the gateway request that locked the party and shows reservation, the outbox span, the Kafka hop, each attempt against `dungeon-sim` (retries and open-circuit rejections included), then reservation and issuance consuming the outcome. Loki has the logs, linked by trace id, and Prometheus has the metrics (Resilience4j, HTTP, Kafka, JVM).

To watch the resilience work, make the dungeon worse while it runs:

```bash
# full outage: the circuit opens (GET :8084/api/circuit), runs wait, and after 2 minutes they're called off
curl -X PUT localhost:8085/api/chaos -H 'Content-Type: application/json'   -d '{"latencyMs":300,"jitterMs":700,"errorRate":0.2,"slowRate":0.1,"slowMs":5000,"faintRate":0.25,"outage":true}'
```

`/actuator/circuitbreakers` and `/actuator/retries` on processing show the Resilience4j state.

Each service's own port is open for debugging and still wants a token. To run one alone without Keycloak, set `AUTH_MODE=dev-headers` and identify yourself with `X-Adventurer` (and `X-Roles: guild-staff` to publish).

| Service | Port | Endpoints (all also on the gateway, :8080) |
|---|---|---|
| catalog | 8081 | `GET /api/expeditions`, `GET /api/expeditions/{id}`, `POST /api/expeditions` |
| queue | 8082 | `POST`, `GET`, `DELETE /api/expeditions/{id}/queue` |
| reservation | 8083 | `POST /api/expeditions/{id}/reservations`, `GET /api/expeditions/{id}/capacity`, `GET /api/reservations`, `GET /api/reservations/{id}`, `POST /api/reservations/{id}/confirm`, `DELETE /api/reservations/{id}` |
| processing | 8084 | `GET /api/runs/{id}`, `GET /api/expeditions/{e}/parties/{p}/runs`, `GET /api/circuit` |
| dungeon-sim | 8085 | `POST /api/runs` (with `Idempotency-Key`), `GET`/`PUT /api/chaos` |
| issuance | 8086 | `GET /api/certificates`, `GET /api/notifications?after=`, `GET /api/notifications/stream` (SSE) |

Errors are RFC 7807 problem details with a stable `code`, like the ledger API: `auth.required`, `auth.forbidden`, `admission.invalid`, `reservation.role-full`, `expedition.sold-out`, `reservation.not-in-ready-check`, `ready-check.missed`, …

## Tests

```bash
cd expeditions && ./mvnw verify
```

- `ReservationFlowTest`: the saga through the HTTP API with a controllable clock: a missed ready check reverting the party and a newcomer completing it, declining, lapsed holds, cancelling, fullest-party-first placement, admission rules, ownership, duplicate events, and the three run outcomes (completed, fainted, aborted with slots reopened).
- `ConcurrentHoldTest`: 40 adventurers race for 6 slots. Exactly 6 win, no party goes over its composition, and capacity events come out of the outbox with strictly increasing versions, which the queue relies on to drop stale ones.
- `PostgresReservationTest`: the same scenarios on PostgreSQL 17 via Testcontainers.
- `QueueRedisTest`: the queue's Lua scripts on a real Redis: ordering, the waiting room, admission capped by open slots and batch size, versioned capacity, lapsed admissions.
- `RunServiceTest`: processing against a scripted simulator: cleared, fainted, quick retries absorbing a flaky call with one idempotency key, a dead simulator retried with backoff until the deadline and then `UNAVAILABLE`, the circuit opening and the simulator no longer being called, redelivered events, leases.
- `HttpDungeonSimTest`: the HTTP client against a local server: answers, 503s, and a hang cut off by the read timeout.
- `IssuanceServiceTest`: certificates issued once per member even when redelivered, loot shares, `certificate.issued` events, notifications per kind, and an SSE push after commit.
- `LootSplitTest`: 5,000 random splits always add up, and shares differ by at most one.
- `JwtSecurityTest`: the real `jwt`-mode filter chain with Keycloak-shaped tokens signed by a test key: staff publish as themselves, adventurers get 403, missing and forged tokens get 401, and dev headers mean nothing.
- `OutboxTracingTest`: an outbox row keeps the trace it was written in, and the relay's span continues it.
- `GatewayRoutingTest`: every path reaches the right service (stand-ins that echo what they received), the token is relayed, the catalog is public, the rest needs a token, and the notification stream takes `?access_token=`.
- `SimulatorTest`, `PartyTest`, `AdmissionTokensTest`, `ExpeditionApiTest`.

The Testcontainers tests are skipped without Docker and run in CI.

## Structure

```text
platform/              shared library: event contracts, outbox + relay, inbox, admission tokens, problem details,
                       security (Keycloak / dev headers), trace propagation, shared defaults
catalog-service/       expedition definitions, validated against ../shared/catalog.json
queue-service/         Redis line and admission (src/main/resources/scripts/*.lua)
reservation-service/   parties, holds, ready check, compensation, sweeper
dungeon-sim/           the unreliable partner, with runtime chaos settings
processing-service/    runs, the worker, Resilience4j around the simulator
issuance-service/      certificates, loot split, notifications over SSE
gateway/               Spring Cloud Gateway: routes, token check, rate limits (Boot 4.0, see its pom)
docs/design.md         the design
infra/                 PostgreSQL init, Keycloak realm
scripts/demo.sh        the walkthrough above
```
