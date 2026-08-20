# service-cp-crime-informant-register

The informant register is the daily record of hearing outcomes sent to prosecuting authorities.
Today it is produced by a Node.js Azure Function App triggered by Event Grid, which has no retries,
no dead-letter queue and no alerting — a register that fails to reach Results is lost silently, with
nothing for support to see or replay. This service is the lift-and-shift of that function app onto
AKS: it consumes thin hearing-resulted messages from a dedicated Azure Service Bus queue, fetches the
hearing payload, rebuilds the register for each prosecuting authority, and POSTs the result to the
Results context's existing `add-informant-register` command. The logic is ported bug-for-bug; what
changes is that failure is now loud, retried, dead-lettered and alertable. Everything downstream —
the `informant_register` table, the 19:00 CSV sweep, GOV.UK Notify — is untouched.

| Field     | Value                                                 |
|-----------|-------------------------------------------------------|
| Team      | Resulting Assistant                                   |
| Programme | Crime Common Platform (CPP) — Modern by Default (MbD) |
| Stack     | Spring Boot 4.1, Java 25, Gradle                      |
| Package   | `uk.gov.hmcts.cp.informantregister`                   |
| Ports     | 8082 local / 4550 Kubernetes                          |

## Status — CRA-220 "Informant register — Initial POC"

The current increment is a **walking skeleton**: the Service Bus consumer with peek-lock settlement,
the `(source, requestId)` idempotency guard, and the port interfaces with **stub adapters** (payload
fetch and register submission as logging no-ops), plus actuator and a container build. The ported
transformation pipeline, the Redis payload adapter and the Results submission adapter are later
stories. A stub is not a defect here — it is the agreed shape of this increment.

This service exposes **no REST API**. The only HTTP surface is Spring Boot Actuator.

## Prerequisites

- ☕️ **Java 25** on your `PATH`
- 🐳 **Docker**, for the Testcontainers suites and the local dependencies in `docker-compose.yml`

The Gradle wrapper is committed; the project defines its own Gradle version, so use `./gradlew`
rather than a system Gradle.

## Quickstart

```bash
./gradlew build              # Compile + the full test suite (no static analysis)
./gradlew test               # The whole suite: unit and *IT alike. There is no separate
                             # integrationTest task; the Testcontainers suites run here and
                             # need Docker only when those tests are in the selection
./gradlew pmdMain            # PMD static analysis — explicit only; `build` does not run it
./gradlew jacocoTestReport   # Coverage report
./gradlew bootRun            # Run locally on port 8082
```

`bootRun` needs its local dependencies — Postgres and the Azure Service Bus emulator — running
first:

```bash
docker compose up -d postgres servicebus-emulator
```

The emulator's queues are declared in `docker/servicebus-emulator/config.json`, and the local
connection string uses `UseDevelopmentEmulator=true`. `docker compose up app` runs the service in
its container against the same pair. Everything in the compose file is local-only: the credentials
there are development defaults and must never be reused anywhere else.

There is no Checkstyle in this build.

## Documentation

| Document                                 | Location                        |
|------------------------------------------|---------------------------------|
| Solution brief                           | [doc/SOLUTION_BRIEF.md](doc/SOLUTION_BRIEF.md)     |
| Technical design                         | [doc/TECHNICAL_DESIGN.md](doc/TECHNICAL_DESIGN.md) |
| Contracts (inbound message and outbound command) | [doc/API_CONTRACTS.md](doc/API_CONTRACTS.md) |
| Deviations register (parity)             | [doc/DEVIATIONS.md](doc/DEVIATIONS.md)             |
| Changelog                                | [doc/CHANGELOG.md](doc/CHANGELOG.md)               |
| Pipeline overview                        | [docs/PIPELINE.md](docs/PIPELINE.md)               |

`CLAUDE.md` is the working agreement for this repository; the binding rules live in
[.specify/memory/constitution.md](.specify/memory/constitution.md) and `.claude/rules/`. Where the
constitution and any other document disagree, the constitution wins.

### Contribute to this repository

Please see [CONTRIBUTING.md](.github/CONTRIBUTING.md) for guidelines.

## Licence

This project is licensed under the MIT Licence — see the [LICENSE](LICENSE) file for details.
