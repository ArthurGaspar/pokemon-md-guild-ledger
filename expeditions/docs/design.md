# Guild Expeditions: design

The guild posts rare **expeditions**: high-difficulty dungeon runs with a fixed number of parties, each needing a specific composition (by default one tank, one healer and two damage dealers). When one opens, thousands of adventurers from every team rush for a few dozen slots. This is a separate system from the ledger: five services, a virtual queue, and a saga that assembles parties, runs the dungeon against a deliberately unreliable simulator and issues the result.

## Services

| Service | Store | Owns | Phase |
|---|---|---|---|
| `catalog` | PostgreSQL | Expedition definitions: dungeon, difficulty, opening time, party count, composition | 1 |
| `queue` | Redis | The waiting room, ordering, admission rate, admission tokens | 1 |
| `reservation` | PostgreSQL + outbox | Parties, role slots, holds with expiry, the ready check, compensation | 1 |
| `processing` | PostgreSQL + outbox | Runs a locked party through `dungeon-sim` behind Resilience4j | 2 |
| `issuance` | PostgreSQL + outbox | Completion certificates, rewards, notifications | 2 |
| `dungeon-sim` | none | Slow and failing on purpose (latency, 5xx, timeouts, configurable) | 2 |
| `gateway` | none | Spring Cloud Gateway, single entry point, token relay | 3 |

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
Reservation   HELD ──confirm──▶ CONFIRMED ──party locks──▶ LOCKED ──(phase 2)──▶ COMPLETED | FAILED
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

- `processing` consumes `party.locked`, records a run, calls `dungeon-sim` with a Resilience4j circuit breaker, retry (exponential backoff with jitter, idempotency key = run id), bulkhead and time limiter. Outcomes: `run.completed` or `run.failed` (the simulator said the party fainted, or it's still failing after retries and the run deadline passes).
- `reservation` consumes `run.failed`: the reservations become `FAILED` and the slots **do not** reopen (the expedition happened, just badly); for an infrastructure failure (`reason = unavailable`), the party is released and the slots reopen. Which failures compensate how is a deliberate rule, written down in the event.
- `issuance` consumes `run.completed`: issues a certificate per member (idempotent per reservation), and publishes `notification.requested`; a notifier logs and pushes over SSE/WebSocket.

## Phase 3: edge and observability

- Keycloak realm `guild` with `adventurer` and `guild-staff` roles. Services become OAuth2 resource servers; the adventurer id is the token's `sub`. Until then (phase 1), the `X-Adventurer` header identifies the caller. It is read in one place (`AdventurerResolver`), so the swap touches nothing else.
- Spring Cloud Gateway in front, rate limiting the join endpoint per user.
- OpenTelemetry (Micrometer tracing + OTLP) into Grafana LGTM: Tempo for traces, Loki for logs, Prometheus for metrics. Trace context crosses Kafka through record headers, so one adventurer's request can be followed from the gateway through queue → reservation → processing → issuance.

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
| | `reservation.released` | reservationId, expeditionId, partyId, adventurerId, role, reason (`EXPIRED`, `DECLINED`, `CANCELLED`) |
| | `party.assembled` | expeditionId, partyId, readyBy, members |
| | `party.reverted` | expeditionId, partyId, failed (adventurer ids), kept (adventurer ids) |
| | `party.locked` | expeditionId, partyId, members (adventurer id + role) |
