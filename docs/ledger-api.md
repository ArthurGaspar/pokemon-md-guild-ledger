# Ledger API internals

The game rules (roles, missions, reward split, ledger accounts) are in the [root README](../README.md#rules). This page covers how the code enforces them.

## Packages

`api/src/main/java/dev/arthurgaspar/guildledger/`, one package per feature:

| Package | Holds |
|---|---|
| `auth` | `User`, registration and login, `TokenService` (issues HS256 JWTs), `SecurityConfig`, `CurrentUser` |
| `catalog` | `Catalog`: `shared/catalog.json` loaded once, with lookups that throw `ApiException` on unknown codes |
| `common` | `ApiException`, `ErrorHandler` |
| `team` | Teams, memberships, `Role`, `SplitMode`, `TeamAccess`, the logbook (`EventLog`, `TeamEvent`) |
| `invite` | Invitations, expiry |
| `recruit` | The roster, `Leveling` (the Medium Fast curve) |
| `mission` | The job board state machine, `Payout` (the reward split, a pure function) |
| `ledger` | `LedgerService`, `Accounts`, `Reason`, balances, storage and treasury operations |

The layering is controller → service → repository. Controllers are thin: they validate the request records (`jakarta.validation`), take the user id from the JWT with `CurrentUser.id(jwt)`, and call one service method. Request DTOs are records nested in the controller, and response views are records nested in the service.

## Errors

- Throw `ApiException` through its factories: `notFound(code, msg)`, `forbidden(msg)` (always `team.forbidden`), `conflict(code, msg)`, `invalid(code, msg)` (422).
- `ErrorHandler` turns it into a `ProblemDetail` with a `code` property. Bean validation failures and unreadable bodies become `400 validation`, and optimistic-lock failures become `409 concurrent-change`.
- Codes are `area.kebab-case` (`mission.difficulty-locked`, `bag.over-capacity`). They are part of the contract: the web client and the contract scenario match on them. Add new ones freely; don't rename existing ones.
- Status conventions: `404` for missing or invisible things, `403` for a member without enough rank, `409` for a state conflict (wrong mission status, duplicate), `422` for a rule violation on otherwise valid input (insufficient funds, over capacity, locked difficulty), `400` for malformed input.

## Authorization

Authentication is a stateless bearer token: an HS256 JWT signed with `guild.jwt.secret` (at least 32 bytes, checked at startup), whose subject is the user id. Public routes are register, login, `GET /api/catalog`, health and Swagger.

Team-scoped authorization is done in services with `TeamAccess`:

- `member(teamId, userId)` returns the active membership, or throws **404 `team.not-found`**. Outsiders can't tell whether a team exists.
- `atLeast(teamId, userId, role)` does the same, then throws **403** if the role is below `role` (`MEMBER < OFFICER < LEADER`).

Call one of these first in every team-scoped service method. Rules that depend on the actor (a member may rename *their own* recruits, or depart a mission *they lead*) are checked after it, in the service.

## The ledger

`LedgerService.post(teamId, actor, reason, missionId, memo, legs)` is the only way to move value.

- **Legs** (`Leg(account, asset, delta)`) are netted per account and asset, and must sum to zero per asset. An unbalanced transaction is a programming error (`IllegalStateException`), not a user error. `Leg.transfer(from, to, asset, amount)` builds the common two-legged case.
- **Accounts** are strings built by `Accounts`: `treasury`, `storage`, `world`, `wallet:<membershipId>`, `bag:<missionId>`. The asset is `POKE` or an item code from the catalog.
- **`world`** is the counterpart for anything entering or leaving the team, and the only account allowed to go negative. Every other account that would go negative raises `422 ledger.insufficient-funds` or `ledger.insufficient-stock`.
- **Transactions.** `post` is `@Transactional(propagation = MANDATORY)`: the caller's service method owns the transaction, so the ledger write commits or rolls back together with the domain change (a mission departing, a member leaving).
- **Locking.** `post` locks the team row first (`TeamRepository.lockById`), which serializes all ledger writes per team. This includes the insert of a balance row that doesn't exist yet, which a balance-row lock alone wouldn't cover.
- **Storage.** `ledger_entry` rows are append-only and record `balance_after`. A `balance` table caches current amounts for reads. `reconcile` replays everything and reports any disagreement. New code must keep both tables consistent, and the only way to do that is to go through `post`.
- **Reasons.** Every transaction has a `Reason`. To add one, add it to `Reason.java` and to the `Reason` union in `web/src/api/types.ts`, and handle it in the engine.

The reward split (`mission/Payout.java`, mirrored in `web/src/engine/payout.ts`) is a pure function. Its invariant, that the parts always add up to the reward and that the remainder goes to the treasury, is property-tested over thousands of random inputs on both sides.

## Persistence

- Flyway migrations are in `src/main/resources/db/migration`, named `V<n>__description.sql`. They are append-only: never edit an applied migration; add the next number. Hibernate runs with `ddl-auto: validate`, so the entity mapping must match.
- SQL must work on PostgreSQL and on H2 in PostgreSQL mode (`MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`), because the fast tests and the `local` profile use H2.
- `open-in-view` is off. Load what a view needs inside the service's transaction.
- Profiles: the default profile expects PostgreSQL through `GUILD_DB_*`. `local` uses an H2 file in `api/data/` (git-ignored). `test` uses in-memory H2 with a random name per context.

## Time

Services take a `java.time.Clock` (a bean in `GuildLedgerApplication`). Invitation expiry is evaluated when invitations are read, so there is no scheduler. Tests that need time to pass (`SecurityAndTimeTest`) replace the bean with `MutableClock`.

## Adding a route

1. Add the service method: call `TeamAccess` first, enforce the rule, throw `ApiException` with a stable code, and use `LedgerService.post` for any movement of value.
2. Add the controller method, with a request record and validation annotations.
3. Mirror the route in `web/src/engine/engine.ts` (same path, status, codes and shape), add the call to `web/src/api/client.ts`, and add the types to `api/types.ts`.
4. Add steps to `shared/contract/scenario.json` covering the success path and each error code. See [contract.md](contract.md).
5. If it is user-facing, add it to the API table in the root README.
