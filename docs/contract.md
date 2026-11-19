# The contract scenario and the browser engine

The GitHub Pages build has no server. `web/src/engine/engine.ts` is a TypeScript port of the ledger API that answers the same routes with the same status codes, error codes and response shapes. `shared/contract/scenario.json` keeps the two honest: it is one long story of HTTP steps that runs, unchanged, against:

| Runner | Target |
|---|---|
| `api/.../ContractScenarioTest` | Spring API on H2 |
| `api/.../PostgresContractTest` | Spring API on PostgreSQL via Testcontainers (CI, or locally with Docker) |
| `web/src/engine/contract.test.ts` | The in-browser engine |

The steps share state: a token or id saved in step 3 is used in step 40. A failure partway through therefore often cascades. Fix the first failing step first.

## Step format

```json
{ "name": "an officer can't pay members",
  "as": "teo",
  "method": "POST", "path": "/api/teams/{team}/treasury/payout",
  "body": { "membershipId": "{m:sol}", "amount": 10, "memo": null },
  "expect": { "status": 403, "code": "team.forbidden", "json": { "$.x": 1 } },
  "save": { "payoutTxn": "$.txn" } }
```

- `as`: whose token to send (`token:<name>`, saved earlier from a register or login response). Omit it for anonymous calls.
- `{var}` in `path` or in string values is replaced with a saved value. A string that is exactly `"{var}"` is replaced with the saved value **keeping its JSON type**, so numbers stay numbers.
- `expect.status` is always checked. `expect.code` checks the problem's `code`. `expect.json` maps paths to exact values.
- Paths are a minimal JSONPath: `$`, `.field`, `[n]`, and a trailing `#` for the length of an array or object (`"$.members#": 3`).
- `save` maps names to paths in the response.

Both runners implement this format (`ContractRunner.java` and `contract.test.ts`). If you extend it, extend both.

## Changing a rule

Any change to API behavior that the client can observe follows the same checklist:

1. **Java**: change the service in `api/`.
2. **TypeScript**: make the same change in `web/src/engine/engine.ts`, and in `payout.ts` for split logic. Keep the check order the same as in Java when a request can fail more than one way: the scenario asserts *which* error comes first.
3. **Scenario**: add or adjust steps in `shared/contract/scenario.json`. Cover the new error code as well as the happy path.
4. **Seed**: if the rule affects the demo data, update `web/src/engine/seed.ts`. `seed.test.ts` builds the demo history through the engine's routes and reconciles it, so an invalid seed fails the tests.
5. Run `cd api && ./mvnw verify` and `cd web && npm test`.

A rule changed on one side only shows up as a red contract test on the other. That is the mechanism working: fix the other side rather than loosening the step.

## How the engine mirrors the API

- **Routes** are registered in `defineRoutes()` as `(method, regex, auth, handler)`. Path parameters are captured as numbers.
- **Transactions**: each mutating request runs on a `structuredClone` snapshot of the state that is thrown away if the handler throws. This is the engine's equivalent of a rolled-back transaction, so an error must never leave partial changes behind.
- **Errors**: handlers throw the engine's own `ApiError(status, code, message)` through the same helper names as the Java factories (`notFound`, `forbidden`, `conflict`, `invalid`, `badRequest`).
- **Ledger**: the engine posts transactions as legs with the same netting, the same negative-balance check and the same `balanceAfter` bookkeeping, so its `/ledger/reconcile` gives the same answers.
- **Time**: `engine.now` is replaceable, which the seed uses to build a history that spans several weeks.
- **Auth**: tokens are random ids mapped to user ids in `state.sessions`, and passwords use a non-cryptographic hash. This is acceptable only because the data never leaves the browser. Don't copy it anywhere else.

## Limits

The scenario only covers what it exercises. Behavior outside it can drift between the two implementations, so a new rule without scenario steps is unprotected. Response fields that no step asserts can also differ. When the UI starts relying on a field, add an assertion for it.

The engine's persisted state has a version key (`guild-ledger.browser-state.v2` in `api/client.ts`). If you change the shape of `State` in a way old saved data can't satisfy, bump the key so existing browsers reseed instead of crashing.
