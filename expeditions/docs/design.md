# Guild Expeditions: design

The guild posts rare **expeditions**: high-difficulty dungeon runs with a fixed number of parties, each needing a specific composition (by default one tank, one healer and two damage dealers). When one opens, thousands of adventurers from every team rush for a few dozen slots. This is a separate system from the ledger: five services, a virtual queue, and a saga that assembles parties, runs the dungeon against a deliberately unreliable simulator and issues the result.

## Services

| Service | Store | Owns | Phase |
|---|---|---|---|
| `catalog` | PostgreSQL | Expedition definitions: dungeon, difficulty, opening time, party count, composition | 1 |
| `queue` | Redis | The waiting room, ordering, admission rate, admission tokens | 1 |
| `reservation` | PostgreSQL + outbox | Parties, role slots, holds with expiry, the ready check, compensation | 1 |
| `processing` | PostgreSQL + outbox + inbox | Runs a locked party through `dungeon-sim` behind Resilience4j | 2 |
| `issuance` | PostgreSQL + outbox + inbox | Completion certificates, loot shares, notifications (SSE) | 2 |
| `dungeon-sim` | none | Slow and failing on purpose (latency, 5xx, timeouts, configurable) | 2 |
| `gateway` | Redis (rate limits) | Spring Cloud Gateway: single entry point, token check, per-adventurer rate limit on the queue | 3 |

Every service that changes state and publishes events does both in one database transaction through a transactional **outbox**. A relay polls the outbox (`FOR UPDATE SKIP LOCKED`) and publishes to Kafka. Consumers are idempotent: they ignore an event they already applied (event id, or a version that isn't newer).

```text
                  ┌──────────┐ expedition.events ┌──────────┐
   staff ───────▶ │ catalog  │ ─────────────────▶│  queue   │◀──── adventurers (join, poll)
                  └──────────┘        │          └──────────┘
                                      │               ▲  admission token (HMAC)
                                      ▼               │ reservation.events (capacity, held)
                               ┌─────────────┐ ───────┘
   adventurers ──── token ───▶ │ reservation │
                               └─────────────┘
                                      │ party.locked              ▲ compensation
                                      ▼                           │ (run failed → release)
                               ┌─────────────┐  Resilience4j  ┌─────────────┐
                               │ processing  │ ─────────────▶ │ dungeon-sim │
                               └─────────────┘                └─────────────┘
                                      │ run.completed
                                      ▼
                               ┌─────────────┐
                               │  issuance   │ ── certificate, notification
                               └─────────────┘
```

## The queue

Most visitors never touch PostgreSQL; that's the point of the queue. Reservation serializes writes per expedition (see below), so the queue admits only as many people as there are open slots.

- **Join** (`POST /expeditions/{id}/queue`). The adventurer gets a ticket number from a Redis counter (`INCR`), used as the score in a sorted set. Joining twice keeps the original place. People can join before the expedition opens; they wait in the waiting room.
- **Admit**. Once a second, for each open expedition, a Lua script atomically: drops admissions that have lapsed, computes `room = openSlots − activeAdmissions`, pops up to `min(room, batch)` adventurers from the front, and records each admission with an expiry (default 2 minutes).
- **Token**. `GET /expeditions/{id}/queue` returns the position, or for an admitted adventurer, an admission token: `expeditionId.adventurerId.expiry`, signed with HMAC-SHA256. Reservation checks the signature with the shared key, so it never calls the queue or Redis.
- **Capacity feedback**. Reservation publishes `capacity.changed` (open slots + a version) after every hold or release. Queue applies it only if the version is newer, so out-of-order delivery can't raise or lower capacity wrongly. `reservation.held` removes that adventurer's admission, which frees room for the next person.
- **Sold out**. When there are no open slots and no active admissions, `GET` reports `SOLD_OUT` with the adventurer still in line: a released slot (an expired hold, a failed ready check, a failed run) reopens the queue in order.

A missed admission (no reservation within the window) is lost; the adventurer can rejoin at the back.

## Reservation and the ready check

Data: `expeditions` (inventory: composition, party count, `open_slots`, `capacity_version`), `parties`, `reservations`.

```text
Reservation   HELD ──confirm──▶ CONFIRMED ──party locks──▶ LOCKED ──run──▶ COMPLETED | FAILED | ABORTED
                │                   │
                │                   └── party reverted (someone else failed the check) ──▶ HELD
                ├── hold lapses while the party is still forming ──▶ EXPIRED
                ├── misses the ready check / declines ──▶ EXPIRED | DECLINED
                └── cancels ──▶ CANCELLED

Party         FORMING ──last slot held──▶ ASSEMBLED ──everyone confirmed──▶ LOCKED
                 ▲                            │
                 └──── someone missed or declined the ready check (compensation)
```

1. **Hold**. `POST /expeditions/{id}/reservations {role, admissionToken}` verifies the token, then places the adventurer in the most-filled forming party with that role open, so parties complete quickly instead of all being three-quarters full. The hold lasts 10 minutes. No role open: `409 reservation.role-full`. One active reservation per adventurer per expedition.
2. **Assembled**. When the last slot is held, the party gets a ready check: everyone must confirm within 60 seconds (`POST /reservations/{id}/confirm`).
3. **Locked**. When everyone has confirmed, the party locks and `party.locked` starts phase 2.
4. **Compensation**. If anyone declines or the deadline passes, their reservation ends (`DECLINED` / `EXPIRED`), their slot reopens, and the formation is undone: the members who did confirm go back to `HELD` with a fresh hold (they did nothing wrong), and the party returns to `FORMING`. Events: `reservation.released`, `party.reverted`, `capacity.changed`. The queue then admits someone for the reopened slot.

A hold in a party that never fills lapses after 10 minutes (`EXPIRED`), which also reopens the slot.

**Concurrency.** Every change to an expedition's parties takes a row lock on that expedition (`SELECT … FOR UPDATE`). That serializes allocation, the ready check and the capacity counter per expedition, and makes check-then-insert rules (one reservation per adventurer, role limits) safe without unique partial indexes. The cost is per-expedition write throughput, and the queue exists to keep it within that budget. The k6 test (phase 4) measures it.

**Time.** A sweeper runs every second: lapsed holds in forming parties, then expired ready checks. Both go through the same code paths as the API.

## Phase 2: processing and issuance (saga continuation)

```text
Run   PENDING ──answer: CLEARED──▶ COMPLETED   → run.completed
         │  ──answer: FAINTED──▶ FAILED (FAINTED)   → run.failed
         └── no answer until the deadline ──▶ FAILED (UNAVAILABLE)   → run.failed
```

**Processing** consumes `party.locked` (deduplicated by event id through the inbox: a party can lock again after an aborted run, so the party id isn't a usable key) and records a `PENDING` run with a deadline (2 minutes). A worker polls every 500 ms, leases due runs with a conditional update (`lease_until`, so two instances never attempt the same run at once and a crashed worker's runs come back), and attempts each on a virtual thread. The call happens outside any transaction; only recording the answer is transactional.

Each attempt goes through Resilience4j, configured in `application.yml`:

| | Setting | Why |
|---|---|---|
| Retry (outermost) | 3 attempts, 200 ms × 2ⁿ with ±50% jitter; not on 4xx, open circuit or full bulkhead | Absorbs a flaky call without waiting for the next poll. Every attempt sends the run id as `Idempotency-Key`, so the simulator never runs a party twice |
| Circuit breaker | 20-call window, opens at 50% failures or 80% slow calls (> 1.5 s), 10 s open, 3 trial calls | Stops piling onto a simulator that is down; open-circuit attempts fail at once and cost nothing |
| Bulkhead (innermost) | 8 concurrent calls, no waiting | A slow simulator can't take every thread |
| Time limit | HTTP read timeout 2 s | A hang counts as a failure (and as slow) instead of holding a thread for the simulator's 5 s stalls |

When an attempt still fails, the run is retried later on a backoff of 1 s doubling to 15 s, until the deadline. Then it fails as `UNAVAILABLE`.

**dungeon-sim** stands in for an outside partner: 300–1000 ms per call, 20% 503s, 10% 5-second hangs, 25% of parties faint. All of it can be changed at runtime with `PUT /api/chaos`, including a full outage. The outcome is derived from the idempotency key and remembered, so retries get the first answer.

**Compensation in reservation** (consuming `run.events`). Each handler acts only on a `LOCKED` party, so redeliveries do nothing:

- `run.completed`: reservations and party `COMPLETED`. The slots stay used.
- `run.failed` / `FAINTED`: `FAILED`. The expedition happened, so the slots stay used and nobody gets a second try at the same expedition.
- `run.failed` / `UNAVAILABLE`: the guild's fault, not the party's. Every reservation is `ABORTED`, the slots reopen (`capacity.changed`, so the queue admits people again), the party starts forming again empty, and `party.released` is published. The members can line up again. They get no priority; giving them some is an open question.

**Issuance** consumes `run.events` and the reservation events adventurers care about, each once (inbox):

- `run.completed`: one certificate per member (also unique on run + adventurer), the loot split equally with the remainder going one Poké each to the first members, so the shares always add up. It publishes `certificate.issued` per member, the hook for paying shares into a team's ledger later.
- Notifications for the ready check, a missed check (each side gets a different message), setting out, clearing, fainting and a called-off run. They're stored, and pushed over SSE (`GET /api/notifications/stream`) after the transaction commits. SSE connections are per instance, and clients catch up with `GET /api/notifications?after=`.

## Phase 3: edge and observability

```text
 browser / script ──token──▶ Keycloak (realm guild)
        │ Bearer
        ▼
     gateway :8080 ── checks the token, rate-limits the queue per adventurer (Redis) ──▶ service (checks it again)
```

**Identity.** Keycloak realm `guild` (imported from `infra/keycloak/guild-realm.json`) has the realm roles `adventurer` and `guild-staff`, and a public client `expeditions`: authorization code with PKCE for a browser, plus password grant for the demo script and the load test. Every service is an OAuth2 resource server. The adventurer is the token's `preferred_username` (usernames can't be edited in the realm), so names in parties, notifications and certificates are readable. Realm roles come from `realm_access.roles` and become `ROLE_*` authorities. Publishing an expedition takes `guild-staff` (`@PreAuthorize`). Reading the catalog is public. Everything else takes a signed-in adventurer.

Tokens are issued for `http://localhost:8180` (where people sign in) while services fetch keys from `http://keycloak:8080` inside the network. Each service sets both `issuer-uri` and `jwk-set-uri`, so it checks the issuer claim without needing discovery to reach the public URL.

All of this lives in `platform/security`, a single filter chain shared by every service, and its failures are problem details too (`auth.required`, `auth.invalid`, `auth.forbidden`). The `dev-headers` mode keeps the phase 1 `X-Adventurer` (+ `X-Roles`) headers for tests and for running one service alone. It builds the same kind of authentication, so controllers can't tell the modes apart. It logs a warning at startup.

**Gateway.** Spring Cloud Gateway (reactive), on Boot 4.0 because Spring Cloud's release train for Boot 4.1 isn't out yet; it shares no code with the services. It routes by path, checks tokens itself so junk never reaches a service, and passes the `Authorization` header on unchanged. The queue routes carry a Redis token bucket per adventurer (5/s, burst 10): enough to poll, not enough to hammer, and keyed by subject, not IP. If Redis is down, the limiter lets requests through rather than taking the queue down with it. `EventSource` can't set headers, so `GET /api/notifications/stream` also accepts `?access_token=`, at the gateway and in issuance only.

**Tracing across the gaps.** HTTP, Kafka (`observation-enabled` on template and listeners), Redis and the RestClient to the simulator are instrumented by Micrometer + OpenTelemetry. Two places break a trace by design, and both are bridged:

- **The outbox.** An event is written in a request but published later by the relay. `Outbox.append` stores the current `traceparent` with the row. The relay publishes each row inside a span that continues it, so Kafka's producer instrumentation puts that trace in the record headers and the consumer carries on.
- **The run worker.** A run is created by a consumer but attempted later by a poller. The run stores the `traceparent` of the `party.locked` that created it, and each attempt (with its HTTP call to `dungeon-sim`) is a child span.

So a single confirm click can be followed from the gateway, through reservation, the outbox and Kafka, into processing and every attempt against `dungeon-sim` (retries and circuit-breaker rejections included), and back out to reservation and issuance.

**Export.** OTLP over HTTP to `grafana/otel-lgtm` (collector, Tempo, Loki, Prometheus and Grafana in one container): traces, metrics every 10 s, and logs through the OpenTelemetry logback appender, so log lines link to their trace. Export is off unless `OTLP_EXPORT=true` (compose sets it), so a service run alone doesn't spam connection errors. These shared settings live in `platform`'s `expeditions-defaults.properties`, added as the lowest-precedence property source, so any service or environment variable can override them.

## Phase 4: load test

k6: 10,000 virtual users join one expedition within seconds of it opening, poll their position, reserve when admitted, and pass the ready check (with a share deliberately failing it). Reported in the README: join latency percentiles, time to admission, reservations per second, how many slots were sold, ready-check reverts, and that no slot was sold twice (checked with SQL after the run).

## Event contracts

All events share an envelope: `{ id, type, aggregateId, occurredAt, payload }`, JSON, Kafka key = expedition id (so one expedition's events stay in order on one partition).

| Topic | Type | Payload |
|---|---|---|
| `expedition.events` | `expedition.published` | id, code, name, dungeon, difficulty, opensAt, parties, composition |
| `reservation.events` | `capacity.changed` | expeditionId, openSlots, version |
| | `reservation.held` | reservationId, expeditionId, partyId, adventurerId, role, heldUntil |
| | `reservation.confirmed` | reservationId, expeditionId, partyId, adventurerId |
| | `reservation.released` | reservationId, expeditionId, partyId, adventurerId, role, reason (`EXPIRED`, `DECLINED`, `CANCELLED`, `ABORTED`) |
| | `party.assembled` | expeditionId, partyId, readyBy, members |
| | `party.reverted` | expeditionId, partyId, failed (adventurer ids), kept (adventurer ids) |
| | `party.locked` | expeditionId, partyId, members (adventurer id + role) |
| | `party.released` | expeditionId, partyId, released (adventurer ids), reason |
| `run.events` | `run.completed` | runId, expeditionId, partyId, members, floorsCleared, pokeFound |
| | `run.failed` | runId, expeditionId, partyId, members, reason (`FAINTED`, `UNAVAILABLE`), detail |
| `issuance.events` | `certificate.issued` | certificateId, runId, expeditionId, adventurerId, role, pokeShare |
