# Expeditions: code conventions

The design (services, queue, saga, compensation, Resilience4j settings, tracing, load test, event contracts) is in [expeditions/docs/design.md](../expeditions/docs/design.md). How to run and use the stack is in [expeditions/README.md](../expeditions/README.md). This page covers the conventions the code follows, so that a change fits in.

## Modules

```text
expeditions/pom.xml           parent (Spring Boot 4.1); lists every module
  platform/                   shared library, consumed by every service except the gateway
  catalog-service/            :8081  PostgreSQL
  queue-service/              :8082  Redis (Lua scripts in src/main/resources/scripts/)
  reservation-service/        :8083  PostgreSQL + outbox
  processing-service/         :8084  PostgreSQL + outbox + inbox, Resilience4j
  dungeon-sim/                :8085  no store; unreliable on purpose
  issuance-service/           :8086  PostgreSQL + outbox + inbox, SSE
  gateway/                    :8080  Spring Cloud Gateway (reactive), Redis rate limits
```

**The gateway is deliberately outside the parent's Boot line.** It has its own `spring-boot-starter-parent` (4.0.x), because the Spring Cloud release train it needs doesn't support Boot 4.1 yet. It must not depend on `platform`. When Spring Cloud catches up, it can move under the parent.

Base package: `dev.arthurgaspar.expeditions.<service>`. Each service is a flat package, with the controller, the service, the listeners, the entities and the repositories side by side.

## The platform library

`platform` is wired in through Spring Boot auto-configuration (`META-INF/spring/...AutoConfiguration.imports`). A service gets all of it by depending on the module:

| Package | Provides |
|---|---|
| `events` | `Events` (type names + payload records), `Topics`, `EventEnvelope`, `EventCodec` (JSON both ways), a `Clock` bean |
| `outbox` | `Outbox`, `Inbox`, `OutboxRelay`. Only active when the service has a `JdbcTemplate` |
| `security` | One `SecurityFilterChain` for every servlet service: `jwt` mode (Keycloak) or `dev-headers` mode |
| `web` | `ApiException`, `ErrorHandler` (problem details), the `Adventurer` controller argument |
| `admission` | `AdmissionTokens`: HMAC-signed admission tokens shared by queue (signs) and reservation (verifies) |
| `tracing` | `TraceContext`, which carries a `traceparent` across the outbox and the run worker |
| `PlatformDefaults` | Adds `expeditions-defaults.properties` as the **lowest-precedence** property source (auth, OTLP export, Kafka observation). Any `application.yml` or environment variable overrides it |

If several services need the same thing, put it in `platform` with auto-configuration rather than copying it.

## Publishing events: the outbox

- Change the state **and** call `outbox.append(topic, key, type, payload)` in the same transaction. `append` throws if no transaction is active. The relay publishes committed rows later, so an event goes out if and only if the change committed.
- **Never** call `KafkaTemplate.send` directly from business code.
- The key is always the **expedition id** as a string, so that one expedition's events stay in order on one partition.
- Services with an outbox add `classpath:db/outbox` to `spring.flyway.locations`. Those migrations are numbered `V0_x` so they sort before the service's own `V1+`.
- The relay can send a row twice (a crash between the send and the update), so every consumer must tolerate duplicates.

## Consuming events: idempotency

Every `@KafkaListener` reads the envelope with `EventCodec`, switches on `envelope.type()`, and ignores types it doesn't handle. Kafka delivers at least once, so each handler must be idempotent in one of these ways:

- **Inbox**: call `inbox.firstDelivery(envelope)` as the **first** statement inside the handler's transaction, and return if it is false. If the handler rolls back, the inbox record rolls back too, and the redelivery is processed. Use this when the effect isn't naturally idempotent (creating a run, issuing certificates).
- **State guard**: act only if the aggregate is in the expected state (reservation's `RunListener` only touches a `LOCKED` party).
- **Version guard**: apply only if the version is newer (queue's handling of `capacity.changed`).

## Adding or changing an event

1. Add the type constant and payload record to `platform/.../events/Events.java`. If it needs a new topic, add it to `Topics.java`.
2. Append it through the outbox in the producer, and handle it idempotently in each consumer.
3. Add it to the **Event contracts** table in `expeditions/docs/design.md`.
4. Payloads are a contract between independently deployed services. Adding a field is safe, because consumers ignore unknown fields. Renaming or removing a field, or changing what a type means, needs every consumer updated in the same change.

## Security

- Controllers take an `Adventurer` argument to know who is calling. In `jwt` mode it comes from the token's `preferred_username`. In `dev-headers` mode it comes from `X-Adventurer`. Controllers can't tell the two modes apart and must not try to.
- Role checks use `@PreAuthorize("hasRole('guild-staff')")`. Realm roles from `realm_access.roles` become `ROLE_<role>` authorities with the Keycloak name kept as is (`KeycloakRoles`).
- Public GET paths are configured per service with `expeditions.auth.public-get-paths`. Everything else needs authentication. Security failures are problem details too (`auth.required`, `auth.invalid`, `auth.forbidden`).
- `dev-headers` trusts the caller completely. Use it only in tests (each service's `application-test.yml` sets it) and when running one service alone. It logs a warning at startup.

## Resilience and background work

- Calls to `dungeon-sim` go through Resilience4j (retry → circuit breaker → bulkhead), configured in `processing-service/src/main/resources/application.yml`. The design doc explains the numbers. The HTTP call happens **outside** any transaction; only recording the result is transactional.
- Pollers are `@Scheduled`. Work is claimed with `FOR UPDATE SKIP LOCKED` or a conditional lease update, so several instances can run. Each poller has an on/off property, which the tests turn off so that they can drive time and work themselves: `expeditions.outbox.relay-enabled`, `queue.admitter-enabled`, `reservation.sweeper-enabled` and `processing.worker-enabled`. A new poller should follow the same pattern (`@ConditionalOnProperty(..., matchIfMissing = true)`).
- Reservation serializes every change to an expedition with `SELECT … FOR UPDATE` on the expedition row. New code that changes parties or capacity must take that lock first.

## Adding a service

1. Create the module and add it to `<modules>` in `expeditions/pom.xml`. Depend on `platform`.
2. Give it `application.yml` with `server.port: ${PORT:<port>}`, `spring.application.name`, the datasource and Kafka from env vars, and `classpath:db/outbox` in the Flyway locations if it publishes.
3. Add `src/test/resources/application-test.yml`: H2 in PostgreSQL mode, `expeditions.auth.mode: dev-headers`, `expeditions.outbox.relay-enabled: false`, and Kafka listeners off.
4. Add it to `expeditions/docker-compose.yml` (with the `*service-build` and `*service-env` anchors, and `SERVICE: <module>`), create its database in `infra/postgres/init.sql`, and add routes in `gateway/src/main/resources/application.yml` and in `GatewayRoutingTest`.
5. Document it in `expeditions/docs/design.md` and the port table in `expeditions/README.md`.

Note that `init.sql` only runs on a fresh volume. After adding a database, recreate the stack with `docker compose -f expeditions/docker-compose.yml down -v`.
