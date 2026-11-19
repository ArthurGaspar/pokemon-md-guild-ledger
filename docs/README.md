# Documentation

Reference for people (and agents) changing the code. The root [README](../README.md) and [expeditions/README.md](../expeditions/README.md) describe what the software does and how to use it; these pages describe how it is built and how to change it safely.

| Page | Read it when |
|---|---|
| [architecture.md](architecture.md) | You need the big picture: the three parts, what they share, how they are built and deployed |
| [ledger-api.md](ledger-api.md) | You are changing `api/`: packages, errors, authorization, the ledger, persistence, time |
| [contract.md](contract.md) | You are changing any ledger rule, route or response (the API and the browser engine must stay in step) |
| [expeditions.md](expeditions.md) | You are changing `expeditions/`: the platform library, outbox and inbox, events, security modes, adding a service |
| [testing.md](testing.md) | You want to run, write or debug tests |
| [configuration.md](configuration.md) | You need an environment variable, port or profile |

The expeditions system's design (queue, saga, compensation, resilience settings, tracing, load test, event contracts) is in [expeditions/docs/design.md](../expeditions/docs/design.md). That file is the source of truth for the design; [expeditions.md](expeditions.md) covers the coding conventions around it.

The PNG files in this folder are screenshots used by the root README.

## Keeping these pages stable

These pages describe structure, invariants and procedures, which change rarely. They avoid things that change often (dependency versions, test counts, phase status, full lists of error codes): the code, the poms and the READMEs hold those. When you change something a page states as a rule, update the page in the same commit.
