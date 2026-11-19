# Testing

## Running

| What | Command | Needs |
|---|---|---|
| Ledger API, everything | `cd api && ./mvnw verify` | Java 21. Docker for `PostgresContractTest` (skipped otherwise) |
| One API test class | `cd api && ./mvnw test -Dtest=PayoutTest` | |
| Web | `cd web && npm test` | Node 22 |
| One web file | `cd web && npx vitest run src/engine/payout.test.ts` | |
| Web type check | `cd web && npm run build` (runs `tsc --noEmit` first) | |
| Expeditions, everything | `cd expeditions && ./mvnw verify` | Java 21. Docker for `PostgresReservationTest` and `QueueRedisTest` |
| One service | `cd expeditions && ./mvnw -pl queue-service -am verify` | `-am` also builds `platform` |
| Load test | see [expeditions/README.md](../expeditions/README.md#load-test) | Full stack + k6 |

On Windows PowerShell, use `.\mvnw.cmd`. In Git Bash, `./mvnw` or `sh mvnw` both work.

Testcontainers suites use `@Testcontainers(disabledWithoutDocker = true)`: they are **skipped, not failed**, without Docker. A green local run without Docker hasn't exercised PostgreSQL-specific behavior (locking, `on conflict`, `SKIP LOCKED`). CI always runs them.

## Kinds of tests

**Contract scenario.** The main protection for the ledger. One JSON story run against the API on H2, the API on PostgreSQL, and the TypeScript engine. See [contract.md](contract.md).

**Property tests.** The reward split (`PayoutTest`, `payout.test.ts`) and the loot split (`LootSplitTest`) run thousands of random inputs and check that nothing is created or lost and that the shares are as even as the rules say. Keep the Java and TypeScript payout tests checking the same invariants.

**Concurrency races.** `ConcurrentPayoutTest` (eight payouts against a treasury that covers three) and `ConcurrentHoldTest` (40 adventurers, 6 slots) fire real threads at real transactions and check that the right number of them win. If you touch locking, run these on PostgreSQL too: H2's locking differs.

**Flow tests with controlled time.** `SecurityAndTimeTest` (API) and `ReservationFlowTest` (reservation) replace the `Clock` bean with `MutableClock`, switch off the schedulers, and drive time themselves (`clock.advance(...)`, then call `sweep()`). Use this pattern for anything with expiry. Don't sleep in tests.

**Infrastructure tests.** `QueueRedisTest` runs the Lua scripts on real Redis. `HttpDungeonSimTest` runs the HTTP client against a local server, including timeouts. `JwtSecurityTest` runs the real `jwt` filter chain with test-signed Keycloak-shaped tokens. `OutboxTracingTest` checks that trace context survives the outbox. `GatewayRoutingTest` uses stand-in backends that echo the requests they receive.

## Test configuration

API (`api/src/test/resources/application-test.yml`, activated with `@ActiveProfiles("test")`): in-memory H2 in PostgreSQL mode with a random database name per context, and a fixed JWT secret.

Expeditions services (`src/test/resources/application-test.yml` in each): H2 in PostgreSQL mode where there is a database, and:

```yaml
expeditions:
  auth:
    mode: dev-headers        # identify callers with X-Adventurer / X-Roles
  outbox:
    relay-enabled: false     # tests read the outbox table instead of needing Kafka
spring:
  kafka:
    listener:
      auto-startup: false    # tests call listener methods directly
```

Schedulers have their own switches (`queue.admitter-enabled`, `reservation.sweeper-enabled`, `processing.worker-enabled`), which tests turn off when they need to control timing.

## Writing tests

- For a ledger rule, add contract scenario steps first. Add a JUnit or Vitest test only for what the scenario can't express (randomized properties, races, time).
- Assert on error `code`, not on the message text.
- When a test needs to see what was published, assert on outbox rows or on the payload records, not on Kafka.
- Test files sit next to the code they test, in the same package. Shared helpers (`MutableClock`, `ContractRunner`, `ReservationScenarios`) live in the test root of their module.
